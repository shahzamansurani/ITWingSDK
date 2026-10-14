package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.itwingtech.itwingsdk.core.StartupTrace
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.utils.SDKMediaView
import java.util.WeakHashMap
import kotlin.math.roundToInt
import androidx.core.view.isVisible
import androidx.core.view.isNotEmpty

class BannerLoader(private val configProvider: () -> ITWingConfig) {

    private val bannerAds = WeakHashMap<ViewGroup, BannerAd>()
    private val adViews = WeakHashMap<ViewGroup, AdView>()
    private val loadTokens = WeakHashMap<ViewGroup, Int>()
    private val activeLoadKeys = WeakHashMap<ViewGroup, String>()
    private val inlineLoadStates = WeakHashMap<ViewGroup, InlineLoadState>()
    private val terminalRealTokens = WeakHashMap<ViewGroup, Int>()
    private val layoutWaiters = WeakHashMap<ViewGroup, View.OnLayoutChangeListener>()

    @MainThread
    fun load(
        activity: Activity,
        container: ViewGroup,
        placementName: String,
        bannerType: BannerType? = null,
        shimmerView: View? = null,
        onPresented: () -> Unit = {},
    ) {
        if (!activity.isUsable() || !container.isAttachedToWindow) {
            destroy(container)
            return
        }

        if (container.width <= 0) {
            waitForMeasuredHost(activity, container, placementName, bannerType, shimmerView, onPresented)
            return
        }

        val config = configProvider()

        if (!config.ads.globalEnabled) {
            AdPriorityTrace.blocked(activity, "banner", placementName, "global_ads_disabled")
            destroy(container)
            return
        }

        if (!NetworkState.isOnline(activity)) {
            AdPriorityTrace.blocked(activity, "banner", placementName, "offline")
            destroy(container)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "banner"
        } ?: run {
            AdPriorityTrace.blocked(activity, "banner", placementName, "missing_or_disabled_placement")
            destroy(container)
            return
        }

        if (!AdLoadBackoff.canRequest(placement)) {
            destroy(container)
            return
        }

        val activeKey = listOf(placementName, bannerType?.name.orEmpty()).joinToString("|")
        val canReuse = synchronized(activeLoadKeys) {
            activeLoadKeys[container] == activeKey && InlineLoadReusePolicy.canReuse(
                state = inlineLoadStates[container],
                requestedKey = activeKey,
                hasVisibleContent = container.isVisible && container.isNotEmpty(),
            )
        }
        if (canReuse) {
            container.visibility = View.VISIBLE
            return
        }

        val hasStaleState = synchronized(activeLoadKeys) { inlineLoadStates.containsKey(container) }
        if (container.isNotEmpty() || hasStaleState) {
            destroy(container)
        }

        synchronized(activeLoadKeys) {
            activeLoadKeys[container] = activeKey
            inlineLoadStates[container] = InlineLoadState(activeKey, InlineLoadPhase.LOADING)
        }

        val token = nextToken(container)
        val requestId = "banner-${System.identityHashCode(container)}-$token"

        val loadingView = shimmerView ?: createDefaultShimmer(activity, container)

        showShimmer(container, loadingView, placement.metadata)

        val unit = placement.adMobUnitOrNull()
        val customAd = if (unit == null || !ITWingSDK.isGoogleAdsInitialized()) {
            selectedCustomAd(config, placement)
        } else null

        if (customAd != null) {
            AdPriorityTrace.request(activity, "banner", placementName, "custom_fallback")
            AdEventTracker.log("ad_load_requested", placement, mapOf("provider" to "custom", "request_reason" to "fallback"))
            preloadCustomBanner(
                activity = activity,
                container = container,
                ad = customAd,
                placement = placement,
                loadingView = loadingView,
                token = token,
                onPresented = onPresented
            )
            return
        }

        unit ?: run {
            AdPriorityTrace.blocked(activity, "banner", placementName, "missing_admob_unit_and_custom_fallback")
            synchronized(activeLoadKeys) {
                activeLoadKeys.remove(container)
                inlineLoadStates.remove(container)
            }
            if (isCurrentLoad(container, token)) {
                stopShimmer(loadingView)
                container.visibility = View.GONE
            }
            return
        }

        val resolvedBannerType = resolveBannerType(placement, bannerType)
        StartupTrace.event(
            activity,
            "BANNER_TYPE_RESOLVED",
            "placement=$placementName type=${resolvedBannerType.name} metadataType=${placement.metadata["banner_type"]} metadataPosition=${placement.metadata["collapsible_position"]}",
        )

        try {
            AdPriorityTrace.request(activity, "banner", placementName, "admob")
            destroyLoadedAd(container)

            val adView = AdView(activity)

            synchronized(adViews) {
                adViews[container] = adView
            }

            val resolvedAdSize = getAdaptiveAdSize(activity, container) ?: run {
                waitForMeasuredHost(activity, container, placementName, bannerType, shimmerView, onPresented)
                return
            }
            val density = activity.resources.displayMetrics.density
            val parent = container.parent as? ViewGroup
            StartupTrace.event(
                activity,
                "BANNER_MEASURED",
                "placement=$placementName outer_width_px=${parent?.width ?: 0} inner_width_px=${container.width} inner_width_dp=${(container.width / density).roundToInt()} padding_px=${container.paddingLeft + container.paddingRight} requested_width_dp=${resolvedAdSize.width}",
            )
            val requestBuilder = BannerAdRequest.Builder(
                adUnitId = unit.adUnitId,
                adSize = resolvedAdSize
            )

            val collapsibleDirection = BannerRequestSizing.collapsibleDirection(resolvedBannerType)
            collapsibleDirection?.let { direction ->
                requestBuilder.setGoogleExtrasBundle(Bundle().apply {
                    putString("collapsible", direction)
                })
            }

            val request = requestBuilder.build()

            val loadMethod = if (collapsibleDirection == null) {
                "ADVIEW_LOAD_AD"
            } else {
                "BANNER_AD_LOAD_REGISTER"
            }
            StartupTrace.event(
                activity,
                "BANNER_LOAD_ROUTED",
                "requestId=$requestId placement=$placementName resolvedBannerType=${resolvedBannerType.name} " +
                    "requestSize=${resolvedAdSize.width}x${resolvedAdSize.height} " +
                    "direction=${collapsibleDirection ?: "none"} loadMethod=$loadMethod",
            )
            if (collapsibleDirection != null) {
                StartupTrace.event(
                    activity,
                    "BANNER_COLLAPSIBLE_REQUEST",
                    "placement=$placementName direction=$collapsibleDirection size=${resolvedAdSize.width}x${resolvedAdSize.height} api=BannerAdRequest.Builder.setGoogleExtrasBundle",
                )
                AdEventTracker.log(
                    "banner_collapsible_requested",
                    placement,
                    mapOf(
                        "requested_collapsible" to true,
                        "requested_direction" to collapsibleDirection,
                        "request_method" to "BannerAdRequest.Builder.setGoogleExtrasBundle",
                        "resolved_size" to "${resolvedAdSize.width}x${resolvedAdSize.height}:${resolvedAdSize.formatString}",
                        "ad_unit_id" to unit.adUnitId,
                    ),
                )
            }

            AdEventTracker.log(
                "ad_load_requested",
                placement,
                mapOf(
                    "provider" to "admob",
                    "request_reason" to "initial_or_refresh",
                    "requested_collapsible" to (collapsibleDirection != null),
                    "requested_direction" to (collapsibleDirection ?: "none"),
                ),
            )

            // Keep the Google AdView in the real measured host before loading.
            // Ordinary banners use AdView.loadAd(). On ads-mobile-sdk 1.5.0,
            // the explicit BannerAd.load() -> registerBannerAd() path is
            // required for the collapsible overlay to remain visible. The
            // AdView still owns the attached BannerAd; the SDK does not copy,
            // resize, or reparent Google's child view.
            adView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // A collapsible BannerAd establishes its expanded presentation
            // during registration. Keep that real AdView visible while the
            // current callback is registered; ordinary banners retain the
            // existing fade-in behavior.
            adView.alpha = if (collapsibleDirection != null) 1f else 0f
            adView.visibility = View.VISIBLE
            adView.clipChildren = false
            adView.clipToPadding = false
            adView.clipToOutline = false
            container.addView(adView)
            loadingView?.takeIf { it.parent === container }?.bringToFront()
            container.requestLayout()
            StartupTrace.event(
                activity,
                "BANNER_AD_VIEW_ATTACHED_BEFORE_LOAD",
                "placement=$placementName direction=${collapsibleDirection ?: "none"} width=${container.width}",
            )

            val loadCallback = object : AdLoadCallback<BannerAd> {

                    override fun onAdLoaded(ad: BannerAd) {
                        activity.runOnUiThread {
                            if (!isCurrentLoad(container, token) || !resolveRealLoad(container, token)) {
                                runCatching { ad.destroy() }
                                return@runOnUiThread
                            }
                            if (
                                !activity.isUsable() ||
                                !container.isAttachedToWindow ||
                                !isCurrentLoad(container, token)
                            ) {
                                runCatching { ad.destroy() }
                                return@runOnUiThread
                            }

                            synchronized(bannerAds) {
                                bannerAds.remove(container)?.let { oldAd ->
                                    runCatching { oldAd.destroy() }
                                }
                                bannerAds[container] = ad
                            }

                             val servedCollapsible = runCatching { ad.isCollapsible() }.getOrDefault(false)
                            val adIdentity = System.identityHashCode(ad)
                             StartupTrace.event(
                                 activity,
                                 "BANNER_AD_LOADED_DIRECT",
                                 "requestId=$requestId adIdentity=$adIdentity placement=$placementName direction=${collapsibleDirection ?: "none"} " +
                                     "isCollapsible=$servedCollapsible requested_size=${resolvedAdSize.width}x${resolvedAdSize.height} " +
                                     "container_width=${container.width} container_height=${container.height}",
                             )
                            AdEventTracker.log("ad_loaded", placement)
                            AdPriorityTrace.loaded(activity, "banner", placementName, "admob")
                            AdLoadBackoff.recordSuccess(placement)
                            AdEventTracker.log(
                                "banner_collapsible_result",
                                placement,
                                mapOf(
                                    "requested" to (collapsibleDirection != null),
                                     "served" to servedCollapsible,
                                    "direction" to (collapsibleDirection ?: "none"),
                                ),
                            )
                            if (collapsibleDirection != null) {
                                StartupTrace.event(
                                    activity,
                                    "BANNER_COLLAPSIBLE_RESULT",
                                     "placement=$placementName direction=$collapsibleDirection served=$servedCollapsible",
                                )
                                runCatching {
                                    StartupTrace.event(
                                        activity,
                                        "BANNER_COLLAPSIBLE_REGISTER_START",
                                        "requestId=$requestId adIdentity=$adIdentity placement=$placementName direction=$collapsibleDirection",
                                    )
                                    adView.registerBannerAd(ad, activity)
                                }.onFailure { error ->
                                    StartupTrace.event(
                                        activity,
                                        "BANNER_COLLAPSIBLE_REGISTER_FAILED",
                                        "placement=$placementName direction=$collapsibleDirection message=${error.message}",
                                    )
                                    runCatching { ad.destroy() }
                                    return@runOnUiThread
                                }
                                StartupTrace.event(
                                    activity,
                                    "BANNER_COLLAPSIBLE_REGISTERED",
                                    "requestId=$requestId adIdentity=$adIdentity placement=$placementName direction=$collapsibleDirection",
                                )
                            }

                            val realView = adView

                            val isSameAdView = collapsibleDirection != null || synchronized(adViews) {
                                adViews[container] === adView
                            }

                            if (!isSameAdView) {
                                runCatching { ad.destroy() }
                                return@runOnUiThread
                            }

                            // The requested AdSize is the permanent compact
                            // slot size.  A collapsible response may report a
                            // taller Google-owned presentation; do not let
                            // that measured result resize the SDK slot or its
                            // CardView.  The host and all relevant parents
                            // remain non-clipping so Google's overlay can
                            // draw outside the compact slot.
                            replaceShimmerWithView(
                                container = container,
                                loadingView = loadingView,
                                realView = realView,
                                metadata = placement.metadata
                            )
                            onPresented()
                            ad.adEventCallback = object : BannerAdEventCallback {
                                override fun onAdImpression() {
                                    AdEventTracker.log("ad_impression", placement)
                                    AdPriorityTrace.impression(activity, "banner", placementName)
                                }

                                override fun onAdPaid(value: AdValue) {
                                    AdEventTracker.log(
                                        "ad_paid",
                                        placement,
                                        mapOf(
                                            "revenue_micros" to value.valueMicros,
                                            "currency" to value.currencyCode,
                                            "precision" to value.precisionType,
                                            "ad_unit_id" to unit.adUnitId,
                                        ),
                                    )
                                }
                            }

                            ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                                override fun onAdRefreshed() {}

                                override fun onAdFailedToRefresh(adError: LoadAdError) {}
                            }
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        activity.runOnUiThread {
                            if (!isCurrentLoad(container, token) || !resolveRealLoad(container, token)) return@runOnUiThread
                            if (
                                !activity.isUsable() ||
                                !container.isAttachedToWindow ||
                                !isCurrentLoad(container, token)
                            ) {
                                return@runOnUiThread
                            }

                            synchronized(adViews) {
                                if (adViews[container] === adView) {
                                    adViews.remove(container)
                                }
                            }
                            val fallback = config.customFallbackFor(placement)
                            if (fallback != null) {
                                AdEventTracker.log("ad_custom_fallback", placement, mapOf("reason" to adError.message))
                                preloadCustomBanner(activity, container, fallback, placement, loadingView, token, onPresented)
                                return@runOnUiThread
                            }
                            stopShimmer(loadingView)
                            container.visibility = View.GONE
                            synchronized(activeLoadKeys) {
                                activeLoadKeys.remove(container)
                                inlineLoadStates.remove(container)
                            }

                            runCatching { adView.destroy() }

                            AdEventTracker.log(
                                "ad_load_failed",
                                placement,
                                mapOf("message" to adError.message)
                            )
                            AdPriorityTrace.blocked(activity, "banner", placementName, "admob_${adError.code}")
                            AdLoadBackoff.recordFailure(placement, adError.message)
                        }
                    }
                }

            if (collapsibleDirection != null) {
                // This explicit attachment path preserves the Google-owned
                // collapsible presentation on ads-mobile-sdk 1.5.0.
                StartupTrace.event(
                    activity,
                    "BANNER_COLLAPSIBLE_LOAD_START",
                    "requestId=$requestId placement=$placementName direction=$collapsibleDirection loadMethod=BANNER_AD_LOAD_REGISTER",
                )
                BannerAd.load(request, loadCallback)
            } else {
                StartupTrace.event(
                    activity,
                    "BANNER_NORMAL_LOAD_START",
                    "requestId=$requestId placement=$placementName loadMethod=ADVIEW_LOAD_AD",
                )
                adView.loadAd(request, loadCallback)
            }

        } catch (_: Exception) {
            if (isCurrentLoad(container, token)) {
                stopShimmer(loadingView)
                container.visibility = View.GONE
                synchronized(activeLoadKeys) {
                    activeLoadKeys.remove(container)
                    inlineLoadStates.remove(container)
                }
                AdEventTracker.log(
                    "ad_load_failed",
                    placement,
                    mapOf("message" to "banner_exception")
                )
                AdLoadBackoff.recordFailure(placement, "banner_exception")
            }
        }
    }

    private fun showShimmer(container: ViewGroup, loadingView: View?, metadata: Map<String, Any?>) {
        container.visibility = View.VISIBLE
        container.alpha = 1f
        container.removeAllViews()

        loadingView?.let { view ->
            if (view.parent !== container) {
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view)
            }

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

        if (realView.parent === container) {
            for (index in container.childCount - 1 downTo 0) {
                if (container.getChildAt(index) !== realView) {
                    container.removeViewAt(index)
                }
            }
            realView.visibility = View.VISIBLE
            realView.requestLayout()
        } else {
            AdTheme.attachCardSurface(container, realView, "banner", metadata, clipContent = false)
        }

        stopShimmer(loadingView)
        synchronized(activeLoadKeys) {
            inlineLoadStates[container]?.let { state ->
                inlineLoadStates[container] = state.copy(phase = InlineLoadPhase.RENDERED)
            }
        }

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
        loadingView: View?,
        onPresented: () -> Unit = {}
    ) {
        destroyLoadedAd(container)

        val root = LayoutInflater.from(activity)
            .inflate(R.layout.custom_banner, container, false)

        val headlineView = root.findViewById<TextView>(R.id.ad_headline)
        val bodyView = root.findViewById<TextView>(R.id.ad_body)
        val advertiserView = root.findViewById<TextView>(R.id.ad_advertiser)
        val ctaView = root.findViewById<Button>(R.id.ad_call_to_action)
        val mediaView = root.findViewById<SDKMediaView>(R.id.ad_media)
        val iconView = root.findViewById<ImageView>(R.id.ad_app_icon)
        val ratingView = root.findViewById<RatingBar>(R.id.ad_stars)
        val adTag = root.findViewById<TextView>(R.id.ad_ic)

        val displayText = ad.displayText()
        headlineView.text = displayText.headline ?: ad.name.ifBlank { "Sponsored" }
        bodyView.text = ad.body?.takeIf { it.isNotBlank() } ?: "Promoted content"
        advertiserView.text = displayText.advertiser ?: "Sponsored"
        ctaView.text = ad.cta?.takeIf { it.isNotBlank() } ?: "Install"
        ratingView.rating = ad.brandRating()
        adTag.text = ad.adIcon()
        val nativeTextColor = sdkColor("native_text_color", "banner_text_color", "text_color")
            ?: placement.metadata.stringValue("native_text_color", "banner_text_color")
        val secondaryTextColor = sdkColor("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: placement.metadata.stringValue("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: nativeTextColor
        headlineView.setTextColor(parseColorSafe(sdkColor("native_headline_text_color", "headline_text_color") ?: placement.metadata.stringValue("native_headline_text_color", "headline_text_color") ?: nativeTextColor, Color.rgb(17, 24, 39)))
        bodyView.setTextColor(parseColorSafe(sdkColor("native_body_text_color", "body_text_color") ?: placement.metadata.stringValue("native_body_text_color", "body_text_color") ?: secondaryTextColor, Color.rgb(71, 85, 105)))
        advertiserView.setTextColor(parseColorSafe(sdkColor("native_meta_text_color", "meta_text_color") ?: placement.metadata.stringValue("native_meta_text_color", "meta_text_color") ?: secondaryTextColor, Color.rgb(100, 116, 139)))

        (ctaView.background?.mutate() as? GradientDrawable)?.setColor(
            parseColorSafe(sdkColor("banner_cta_color", "banner_cta_background_color", "native_cta_color", "native_cta_background_color", "ad_cta_color", "ad_cta_background_color") ?: ad.primaryColor(), Color.rgb(37, 99, 235))
        )
        ctaView.setTextColor(parseColorSafe(sdkColor("banner_cta_text_color", "native_cta_text_color", "cta_text_color") ?: placement.metadata.stringValue("banner_cta_text_color", "native_cta_text_color", "cta_text_color"), Color.WHITE))

        (adTag.background?.mutate() as? GradientDrawable)?.setColor(
            parseColorSafe(sdkColor("native_ad_label_color", "native_ad_label_background_color", "ad_label_color", "ad_label_background_color", "ad_badge_color", "ad_badge_background_color") ?: ad.primaryColor(), Color.rgb(37, 99, 235))
        )
        adTag.setTextColor(parseColorSafe(sdkColor("native_ad_label_text_color", "ad_label_text_color") ?: placement.metadata.stringValue("native_ad_label_text_color", "ad_label_text_color"), Color.WHITE))

        mediaView.apply {
            render(ad.resolvedMediaUrl(), ad.isVideoMedia())
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
            metadata = placement.metadata
        )

        ITWingSDK.trackCustomAdImpression(
            ad.id,
            mapOf("placement" to "banner")
        )

        onPresented()
    }

    private fun preloadCustomBanner(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        placement: AdPlacementConfig,
        loadingView: View?,
        token: Int,
        onPresented: () -> Unit
    ) {
        val media = ad.resolvedMediaUrl()

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
                    loadingView = loadingView,
                    onPresented = onPresented
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
                    loadingView = loadingView,
                    onPresented = onPresented
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
        if (!placement.allowsCustomFallback()) return null

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
            layoutWaiters.forEach { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
            layoutWaiters.clear()
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
                inlineLoadStates.clear()
            }

            return
        }

        layoutWaiters.remove(container)?.let { container.removeOnLayoutChangeListener(it) }
        nextToken(container)
        destroyLoadedAd(container)
        synchronized(terminalRealTokens) {
            terminalRealTokens.remove(container)
        }
        synchronized(activeLoadKeys) {
            activeLoadKeys.remove(container)
            inlineLoadStates.remove(container)
        }

        container.let {
            releaseMediaViews(it)
            it.removeAllViews()
        }
    }

    fun pause(container: ViewGroup) {
        container.visibility = View.GONE
    }

    fun resume(container: ViewGroup) {
        if (container.isNotEmpty()) {
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

    }

    private fun releaseMediaViews(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            when (val child = parent.getChildAt(i)) {
                is SDKMediaView -> child.release()
                is ViewGroup -> releaseMediaViews(child)
            }
        }
    }

    private fun getAdaptiveAdSize(
        activity: Activity,
        container: ViewGroup,
    ): AdSize? {
        // A positive measured width is the contract required by the Google
        // size API. `isLaidOut` can still be false during the same traversal
        // that has already produced a valid width, which otherwise leaves a
        // visible host permanently on shimmer.
        if (container.width <= 0) return null

        val density = activity.resources.displayMetrics.density
        val adWidth = BannerRequestSizing.widthDp(container.width, density) ?: return null

        // The requested size is the regular/collapsed Banner size for every
        // Banner request. Google owns the larger collapsible overlay; the SDK
        // must not request a large Banner merely because a collapsible
        // direction is present.
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
            activity,
            adWidth
        )
    }

    private fun waitForMeasuredHost(
        activity: Activity,
        container: ViewGroup,
        placementName: String,
        bannerType: BannerType?,
        shimmerView: View?,
        onPresented: () -> Unit,
    ) {
        if (layoutWaiters.containsKey(container) || !container.isAttachedToWindow) return

        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (container.width > 0) {
                layoutWaiters.remove(container)?.let { container.removeOnLayoutChangeListener(it) }
                load(activity, container, placementName, bannerType, shimmerView, onPresented = onPresented)
            }
        }
        layoutWaiters[container] = listener
        container.addOnLayoutChangeListener(listener)
        container.post {
            if (container.width > 0) {
                container.removeOnLayoutChangeListener(listener)
                if (layoutWaiters[container] === listener) {
                    layoutWaiters.remove(container)
                }
                load(activity, container, placementName, bannerType, shimmerView, onPresented = onPresented)
            }
        }
    }

    private fun resolveBannerType(
        placement: AdPlacementConfig,
        override: BannerType?
    ): BannerType {
        if (override != null) return override
        return BannerType.fromMetadata(placement.metadata)
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

    private fun CustomAdConfig.mediaUrl(): String? =
        mediaUrl?.takeIf { it.isNotBlank() }
            ?: videoUrl?.takeIf { it.isNotBlank() }
            ?: imageUrl?.takeIf { it.isNotBlank() }

    private fun CustomAdConfig.isVideo(): Boolean =
        mediaType.equals("video", ignoreCase = true) ||
                (!videoUrl.isNullOrBlank() && mediaUrl == videoUrl)

    private fun CustomAdConfig.primaryColor(): String? =
        ITWingSDK.getColor("primary").takeIf { it.isNotBlank() }
            ?: ITWingSDK.getColor("primary_color").takeIf { it.isNotBlank() }
            ?: (metadata["ad_primary_color"] as? String)?.takeIf { it.isNotBlank() }
            ?: ((metadata["brand"] as? Map<*, *>)?.get("primary_color") as? String)
                ?.takeIf { it.isNotBlank() }

    private fun CustomAdConfig.brandName(): String? =
        (metadata["brand"] as? Map<*, *>)?.get("name") as? String ?: campaignGroup

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
