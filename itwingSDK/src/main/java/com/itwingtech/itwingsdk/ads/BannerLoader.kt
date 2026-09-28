package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.utils.SDKMediaView
import java.util.WeakHashMap
import kotlin.math.roundToInt

class BannerLoader(private val configProvider: () -> ITWingConfig) {

    private val bannerAds = WeakHashMap<ViewGroup, BannerAd>()
    private val adViews = WeakHashMap<ViewGroup, AdView>()
    private val loadTokens = WeakHashMap<ViewGroup, Int>()
    private val terminalRealTokens = WeakHashMap<ViewGroup, Int>()
    private val activeLoadKeys = WeakHashMap<ViewGroup, String>()

    @MainThread
    fun load(
        activity: Activity,
        container: ViewGroup,
        placementName: String,
        bannerType: BannerType? = null,
        shimmerView: View? = null
    ) {
        if (!activity.isUsable() || !container.isAttachedToWindow) {
            destroy(container)
            return
        }

        val config = configProvider()

        if (!config.ads.globalEnabled) {
            destroy(container)
            return
        }

        if (!NetworkState.isOnline(activity)) {
            destroy(container)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "banner"
        } ?: run {
            destroy(container)
            return
        }

        if (!AdLoadBackoff.canRequest(placement)) {
            destroy(container)
            return
        }

        val activeKey = listOf(placementName, bannerType?.name.orEmpty()).joinToString("|")
        synchronized(activeLoadKeys) {
            if (activeLoadKeys[container] == activeKey && container.childCount > 0) {
                container.visibility = View.VISIBLE
                return
            }
            activeLoadKeys[container] = activeKey
        }

        val token = nextToken(container)

        val loadingView = shimmerView ?: createDefaultShimmer(activity, container)

        showShimmer(container, loadingView, placement.metadata)

        val unit = placement.adMobUnitOrNull()
        val fallbackAd = config.customFallbackFor(placement)
        AdPriorityTrace.decision(placement, fallbackAd != null)
        val customAd = if (unit == null) selectedCustomAd(config, placement) else null

        if (customAd != null) {
            AdPriorityTrace.event(placement, "CUSTOM_ONLY_START")
            AdEventTracker.log("ad_load_requested", placement)
            preloadCustomBanner(
                activity = activity,
                container = container,
                ad = customAd,
                placement = placement,
                loadingView = loadingView,
                token = token
            )
            return
        }

        val admobUnit = unit ?: run {
            synchronized(activeLoadKeys) {
                activeLoadKeys.remove(container)
            }
            if (isCurrentLoad(container, token)) {
                stopShimmer(loadingView)
                container.visibility = View.GONE
            }
            return
        }

        val resolvedBannerType = resolveBannerType(placement, bannerType)
        // The card host applies its content inset and shadow margins while the
        // shimmer is installed. Wait for that layout pass before requesting an
        // adaptive size, otherwise the AdView can be a few dp wider than its
        // final card content area and clip the creative's CTA at the edge.
        container.postDelayed({
            if (
                activity.isUsable() &&
                container.isAttachedToWindow &&
                isCurrentLoad(container, token)
            ) {
                requestRealBanner(
                    activity = activity,
                    container = container,
                    config = config,
                    placement = placement,
                    adUnitId = admobUnit.adUnitId,
                    bannerType = resolvedBannerType,
                    loadingView = loadingView,
                    token = token,
                )
            }
        }, 32L)
    }

    private fun requestRealBanner(
        activity: Activity,
        container: ViewGroup,
        config: ITWingConfig,
        placement: AdPlacementConfig,
        adUnitId: String,
        bannerType: BannerType,
        loadingView: View?,
        token: Int,
    ) {
        try {
            AdPriorityTrace.event(placement, "REAL_REQUEST_START")
            destroyLoadedAd(container)
            val adView = AdView(activity)
            synchronized(adViews) { adViews[container] = adView }

            val extras = Bundle()
            when (bannerType) {
                BannerType.COLLAPSIBLE_TOP -> extras.putString("collapsible", "top")
                BannerType.COLLAPSIBLE_BOTTOM -> extras.putString("collapsible", "bottom")
                BannerType.ADAPTIVE -> Unit
            }

            val request = BannerAdRequest.Builder(
                adUnitId = adUnitId,
                adSize = getAdaptiveAdSize(activity, container)
            ).setGoogleExtrasBundle(extras).build()

            AdEventTracker.log("ad_load_requested", placement)
            adView.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    activity.runOnUiThread {
                        if (!activity.isUsable() || !container.isAttachedToWindow || !isCurrentLoad(container, token)) {
                            runCatching { ad.destroy() }
                            return@runOnUiThread
                        }
                        if (!resolveRealLoad(container, token)) {
                            runCatching { ad.destroy() }
                            return@runOnUiThread
                        }

                        synchronized(bannerAds) {
                            bannerAds.remove(container)?.let { oldAd -> runCatching { oldAd.destroy() } }
                            bannerAds[container] = ad
                        }
                        AdPriorityTrace.event(placement, "REAL_LOAD_SUCCESS")
                        AdEventTracker.log("ad_loaded", placement)
                        AdLoadBackoff.recordSuccess(placement)
                        adView.registerBannerAd(ad, activity)

                        ad.adEventCallback = object : BannerAdEventCallback {
                            override fun onAdImpression() {
                                AdEventTracker.log("ad_impression", placement)
                            }

                            override fun onAdPaid(adValue: AdValue) {
                                AdEventTracker.log(
                                    "ad_paid",
                                    placement,
                                    mapOf(
                                        "revenue_micros" to adValue.valueMicros,
                                        "currency" to adValue.currencyCode,
                                        "precision" to adValue.precisionType,
                                        "ad_unit_id" to adUnitId,
                                    ),
                                )
                            }
                        }

                        val isSameAdView = synchronized(adViews) { adViews[container] === adView }
                        if (!isSameAdView) {
                            runCatching { ad.destroy() }
                            return@runOnUiThread
                        }

                        AdPriorityTrace.event(placement, "DISPLAY_SOURCE=ADMOB")
                        replaceShimmerWithView(container, loadingView, adView, placement.metadata)
                        ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                            override fun onAdRefreshed() {}
                            override fun onAdFailedToRefresh(adError: LoadAdError) {}
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    activity.runOnUiThread {
                        if (!activity.isUsable() || !container.isAttachedToWindow || !isCurrentLoad(container, token)) {
                            return@runOnUiThread
                        }
                        if (!resolveRealLoad(container, token)) return@runOnUiThread
                        synchronized(adViews) {
                            if (adViews[container] === adView) adViews.remove(container)
                        }
                        AdPriorityTrace.event(placement, "REAL_LOAD_FAILED", "${adError.code}:${adError.message.take(120)}")
                        val fallback = config.customFallbackFor(placement)
                        if (fallback != null) {
                            AdPriorityTrace.event(placement, "CUSTOM_FALLBACK_START", "real_load_failed")
                            AdEventTracker.log("ad_custom_fallback", placement, mapOf("reason" to adError.message, "network" to "custom"))
                            runCatching { adView.destroy() }
                            preloadCustomBanner(activity, container, fallback, placement.withCustomFallback(fallback), loadingView, token)
                            return@runOnUiThread
                        }
                        stopShimmer(loadingView)
                        container.visibility = View.GONE
                        synchronized(activeLoadKeys) { activeLoadKeys.remove(container) }
                        runCatching { adView.destroy() }
                        AdEventTracker.log("ad_load_failed", placement, mapOf("message" to adError.message))
                        AdLoadBackoff.recordFailure(placement, adError.message)
                    }
                }
            })
        } catch (_: Exception) {
            if (isCurrentLoad(container, token) && resolveRealLoad(container, token)) {
                val fallback = config.customFallbackFor(placement)
                if (fallback != null && activity.isUsable() && container.isAttachedToWindow) {
                    AdPriorityTrace.event(placement, "CUSTOM_FALLBACK_START", "real_request_exception")
                    AdEventTracker.log("ad_custom_fallback", placement, mapOf("reason" to "admob_request_exception", "network" to "custom"))
                    preloadCustomBanner(activity, container, fallback, placement.withCustomFallback(fallback), loadingView, token)
                    return
                }
                stopShimmer(loadingView)
                container.visibility = View.GONE
                synchronized(activeLoadKeys) { activeLoadKeys.remove(container) }
                AdEventTracker.log("ad_load_failed", placement, mapOf("message" to "banner_exception"))
                AdLoadBackoff.recordFailure(placement, "banner_exception")
            }
        }
    }

    private fun showShimmer(container: ViewGroup, loadingView: View?, metadata: Map<String, Any?>) {
        container.visibility = View.VISIBLE
        container.alpha = 1f
        container.removeAllViews()

        loadingView?.let { view ->
            view.visibility = View.VISIBLE
            view.alpha = 1f
            AdTheme.styleShimmer(view)
            AdTheme.attachCardSurface(container, view, "banner", metadata, clipContent = false)
            (view as? ShimmerFrameLayout)?.startShimmer()
        }
    }

    private fun replaceShimmerWithView(
        container: ViewGroup,
        loadingView: View?,
        realView: View,
        metadata: Map<String, Any?>,
    ) {
        container.visibility = View.VISIBLE
        container.alpha = 1f

        AdTheme.attachCardSurface(container, realView, "banner", metadata, clipContent = false)

        stopShimmer(loadingView)

        realView.alpha = 0f
        realView.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun renderCustomBanner(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        placement: AdPlacementConfig,
        loadingView: View?
    ) {
        AdPriorityTrace.event(placement, "DISPLAY_SOURCE=CUSTOM")
        destroyLoadedAd(container)

        val root = LayoutInflater.from(activity)
            .inflate(R.layout.custom_banner, container, false)
        val contentRoot = root.findViewById<View>(R.id.ad_content_root)
        val cardStyle = AdTheme.cardStyle("banner", placement.metadata)
        if (cardStyle.explicitlyConfigured) contentRoot?.setBackgroundColor(Color.TRANSPARENT)

        val headlineView = root.findViewById<TextView>(R.id.ad_headline)
        val bodyView = root.findViewById<TextView>(R.id.ad_body)
        val advertiserView = root.findViewById<TextView>(R.id.ad_advertiser)
        val ctaView = root.findViewById<Button>(R.id.ad_call_to_action)
        val mediaView = root.findViewById<SDKMediaView>(R.id.ad_media)
        val iconView = root.findViewById<ImageView>(R.id.ad_app_icon)
        val ratingView = root.findViewById<RatingBar>(R.id.ad_stars)
        val adTag = root.findViewById<TextView>(R.id.ad_ic)

        val displayText = ad.displayText()
        headlineView.text = displayText.headline.orEmpty()
        headlineView.visibility = if (displayText.headline == null) View.GONE else View.VISIBLE
        bodyView.text = ad.body?.takeIf { it.isNotBlank() } ?: "Promoted content"
        advertiserView.text = displayText.advertiser.orEmpty()
        advertiserView.visibility = if (displayText.advertiser == null) View.GONE else View.VISIBLE
        ctaView.text = ad.cta?.takeIf { it.isNotBlank() } ?: "Install"
        ratingView.rating = ad.brandRating()
        adTag.text = ad.adIcon()
        val nativeTextColor = sdkColor("native_text_color", "banner_text_color", "text_color")
            ?: placement.metadata.stringValue("native_text_color", "banner_text_color")
        val secondaryTextColor = sdkColor("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: placement.metadata.stringValue("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: nativeTextColor
        headlineView.setTextColor(parseColorSafe(sdkColor("native_headline_text_color", "headline_text_color") ?: placement.metadata.stringValue("native_headline_text_color", "headline_text_color") ?: nativeTextColor, Color.BLACK))
        bodyView.setTextColor(parseColorSafe(sdkColor("native_body_text_color", "body_text_color") ?: placement.metadata.stringValue("native_body_text_color", "body_text_color") ?: secondaryTextColor, Color.BLACK))
        advertiserView.setTextColor(parseColorSafe(sdkColor("native_meta_text_color", "meta_text_color") ?: placement.metadata.stringValue("native_meta_text_color", "meta_text_color") ?: secondaryTextColor, Color.BLACK))

        (ctaView.background?.mutate() as? GradientDrawable)?.setColor(
            parseColorSafe(sdkColor("banner_cta_color", "banner_cta_background_color", "native_cta_color", "native_cta_background_color", "ad_cta_color", "ad_cta_background_color") ?: ad.primaryColor(), Color.rgb(37, 99, 235))
        )
        ctaView.setTextColor(parseColorSafe(sdkColor("banner_cta_text_color", "native_cta_text_color", "cta_text_color") ?: placement.metadata.stringValue("banner_cta_text_color", "native_cta_text_color", "cta_text_color"), Color.WHITE))

        (adTag.background?.mutate() as? GradientDrawable)?.setColor(
            parseColorSafe(sdkColor("native_ad_label_color", "native_ad_label_background_color", "ad_label_color", "ad_label_background_color", "ad_badge_color", "ad_badge_background_color") ?: ad.primaryColor(), Color.rgb(37, 99, 235))
        )
        adTag.setTextColor(parseColorSafe(sdkColor("native_ad_label_text_color", "ad_label_text_color") ?: placement.metadata.stringValue("native_ad_label_text_color", "ad_label_text_color"), Color.WHITE))

        mediaView.apply {
            render(ad.mediaUrl(), ad.isVideo())
            play()
        }

        loadImage(
            ad.brandLogoUrl() ?: ad.imageUrl ?: ad.mediaUrl(),
            iconView,
            activity
        )

        val clickListener = View.OnClickListener {
            ITWingSDK.trackCustomAdClick(
                ad.id,
                mapOf("placement" to "banner")
            )

            (ad.androidTargetUrl ?: ad.targetUrl)?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching {
                    mediaView.pauseForExternalNavigation()

                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri())
                    )

                    activity.application.registerActivityLifecycleCallbacks(
                        object : Application.ActivityLifecycleCallbacks {
                            override fun onActivityResumed(resumedActivity: Activity) {
                                if (resumedActivity == activity) {
                                    mediaView.resumeFromExternalNavigation()
                                    activity.application.unregisterActivityLifecycleCallbacks(this)
                                }
                            }

                            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                            override fun onActivityStarted(activity: Activity) {}
                            override fun onActivityPaused(activity: Activity) {}
                            override fun onActivityStopped(activity: Activity) {}
                            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                            override fun onActivityDestroyed(activity: Activity) {}
                        }
                    )
                }
            }
        }

        root.setOnClickListener(clickListener)
        ctaView.setOnClickListener(clickListener)

        replaceShimmerWithView(
            container = container,
            loadingView = loadingView,
            realView = root,
            metadata = placement.metadata,
        )

        ITWingSDK.trackCustomAdImpression(
            ad.id,
            mapOf("placement" to "banner")
        )
    }

    private fun preloadCustomBanner(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        placement: AdPlacementConfig,
        loadingView: View?,
        token: Int
    ) {
        val media = ad.mediaUrl()

        if (media.isNullOrBlank()) {
            activity.runOnUiThread {
                if (
                    !activity.isUsable() ||
                    !container.isAttachedToWindow ||
                    !isCurrentLoad(container, token)
                ) {
                    return@runOnUiThread
                }

                renderCustomBanner(
                    activity = activity,
                    container = container,
                    ad = ad,
                    placement = placement,
                    loadingView = loadingView
                )
            }
            return
        }

        Glide.with(activity.applicationContext)
            .load(media)
            .preload()

        container.postDelayed({
            activity.runOnUiThread {
                if (
                    !activity.isUsable() ||
                    !container.isAttachedToWindow ||
                    !isCurrentLoad(container, token)
                ) {
                    return@runOnUiThread
                }

                renderCustomBanner(
                    activity = activity,
                    container = container,
                    ad = ad,
                    placement = placement,
                    loadingView = loadingView
                )
            }
        }, 650)
    }

    private fun stopShimmer(loadingView: View?) {
        (loadingView as? ShimmerFrameLayout)?.stopShimmer()

        loadingView?.apply {
            visibility = View.GONE
            (parent as? ViewGroup)?.removeView(this)
        }
    }

    private fun loadImage(url: String?, imageView: ImageView, activity: Activity) {
        if (url.isNullOrBlank()) {
            imageView.visibility = View.GONE
            return
        }

        activity.runOnUiThread {
            runCatching {
                Glide.with(activity)
                    .load(url)
                    .fitCenter()
                    .into(imageView)

                imageView.visibility = View.VISIBLE
            }.onFailure {
                imageView.visibility = View.GONE
            }
        }
    }

    private fun selectedCustomAd(
        config: ITWingConfig,
        placement: AdPlacementConfig
    ): CustomAdConfig? {
        val source = placement.metadata["source"]?.toString()?.lowercase()

        if (
            source != "custom" &&
            source != "custom_ad" &&
            placement.customAd == null
        ) {
            return null
        }

        placement.customAd?.takeIf { !it.mediaUrl().isNullOrBlank() }?.let { return it }

        val requestedId = placement.metadata["custom_ad_id"]
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        return config.ads.customAds
            .filter {
                it.format == "banner" ||
                        it.format == "image" ||
                        it.format == "html"
            }
            .filter {
                !it.mediaUrl().isNullOrBlank()
            }
            .filter {
                requestedId == null || it.id == requestedId
            }
            .minByOrNull { it.priority }
    }

    fun destroy(container: ViewGroup? = null) {
        if (container == null) {
            synchronized(bannerAds) {
                bannerAds.values.forEach { ad -> runCatching { ad.destroy() } }
                bannerAds.clear()
            }

            synchronized(adViews) {
                adViews.values.forEach { view -> runCatching { view.destroy() } }
                adViews.clear()
            }

            synchronized(loadTokens) {
                loadTokens.clear()
            }
            synchronized(terminalRealTokens) {
                terminalRealTokens.clear()
            }

            synchronized(activeLoadKeys) {
                activeLoadKeys.clear()
            }

            return
        }

        nextToken(container)
        synchronized(terminalRealTokens) { terminalRealTokens.remove(container) }
        destroyLoadedAd(container)

        container.let {
            releaseMediaViews(it)
            it.removeAllViews()
        }
    }

    fun pause(container: ViewGroup) {
        container.visibility = View.GONE
    }

    fun resume(container: ViewGroup) {
        if (container.childCount > 0) {
            container.visibility = View.VISIBLE
        }
    }

    private fun destroyLoadedAd(container: ViewGroup) {
        synchronized(bannerAds) {
            bannerAds.remove(container)?.let { ad ->
                runCatching { ad.destroy() }
            }
        }

        synchronized(adViews) {
            adViews.remove(container)?.let { view ->
                runCatching { view.destroy() }
            }
        }

        synchronized(activeLoadKeys) {
            activeLoadKeys.remove(container)
        }
    }

    private fun releaseMediaViews(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)

            when (child) {
                is SDKMediaView -> child.release()
                is ViewGroup -> releaseMediaViews(child)
            }
        }
    }

    private fun getAdaptiveAdSize(
        activity: Activity,
        container: ViewGroup
    ): AdSize {
        val displayMetrics: DisplayMetrics = activity.resources.displayMetrics
        val density = displayMetrics.density

        var adWidthPixels = container.width.toFloat()

        if (adWidthPixels <= 0f) {
            adWidthPixels = displayMetrics.widthPixels.toFloat()
        }

        val adWidth = (adWidthPixels / density).roundToInt()

        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
            activity,
            adWidth
        )
    }

    private fun resolveBannerType(
        placement: AdPlacementConfig,
        override: BannerType?
    ): BannerType {
        if (override != null) return override

        val value = (
                placement.metadata["banner_type"]
                    ?: placement.metadata["collapsible_position"]
                )
            ?.toString()
            ?.lowercase()

        return when (value) {
            "top" -> BannerType.COLLAPSIBLE_TOP
            "bottom" -> BannerType.COLLAPSIBLE_BOTTOM
            "collapsible_top" -> BannerType.COLLAPSIBLE_TOP
            "collapsible_bottom" -> BannerType.COLLAPSIBLE_BOTTOM
            else -> BannerType.ADAPTIVE
        }
    }

    private fun createDefaultShimmer(
        activity: Activity,
        container: ViewGroup
    ): View? {
        return runCatching {
            LayoutInflater.from(activity)
                .inflate(R.layout.banner_shimmer, container, false)
        }.getOrNull()
    }

    private fun nextToken(container: ViewGroup): Int {
        return synchronized(loadTokens) {
            val next = (loadTokens[container] ?: 0) + 1
            loadTokens[container] = next
            next
        }
    }

    private fun isCurrentLoad(container: ViewGroup, token: Int): Boolean {
        return synchronized(loadTokens) {
            loadTokens[container] == token
        }
    }

    private fun resolveRealLoad(container: ViewGroup, token: Int): Boolean = synchronized(terminalRealTokens) {
        if (terminalRealTokens[container] == token) return@synchronized false
        terminalRealTokens[container] = token
        true
    }

    private fun CustomAdConfig.mediaUrl(): String? = resolvedMediaUrl()

    private fun CustomAdConfig.isVideo(): Boolean = isVideoMedia()

    private fun CustomAdConfig.primaryColor(): String? =
        ITWingSDK.getColor("primary").takeIf { it.isNotBlank() }
            ?: ITWingSDK.getColor("primary_color").takeIf { it.isNotBlank() }
            ?: (metadata["ad_primary_color"] as? String)?.takeIf { it.isNotBlank() }
            ?: ((metadata["brand"] as? Map<*, *>)?.get("primary_color") as? String)
                ?.takeIf { it.isNotBlank() }

    private fun CustomAdConfig.brandRating(): Float {
        val value = metadata["brand_rating"]
            ?: (metadata["brand"] as? Map<*, *>)?.get("rating")

        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }?.coerceIn(0f, 5f) ?: 4.5f
    }

    private fun CustomAdConfig.brandLogoUrl(): String? {
        val brand = metadata["brand"] as? Map<*, *> ?: return null
        return brand["logo_url"] as? String
    }

    private fun CustomAdConfig.adIcon(): String =
        (metadata["ad_icon"] as? String)?.takeIf { it.isNotBlank() } ?: "AD"

    private fun Map<String, Any?>.stringValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }

    private fun sdkColor(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            ITWingSDK.getColor(key).takeIf { it.isNotBlank() }
        }

    private fun parseColorSafe(value: String?, fallback: Int): Int =
        runCatching {
            if (value.isNullOrBlank()) fallback else value.toColorInt()
        }.getOrDefault(fallback)

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed
}
