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
import androidx.annotation.LayoutRes
import com.bumptech.glide.Glide
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoController
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaContent
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.utils.SDKMediaView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import java.util.WeakHashMap

class NativeLoader(
    private val configProvider: () -> ITWingConfig
) {

    private val nativeAds = WeakHashMap<ViewGroup, NativeAd>()
    private val loadTokens = WeakHashMap<ViewGroup, Int>()
    private val activeLoadKeys = WeakHashMap<ViewGroup, String>()

    /*
    |--------------------------------------------------------------------------
    | Load
    |--------------------------------------------------------------------------
    */

    fun load(
        activity: Activity,
        container: ViewGroup,
        placementName: String,
        nativeTypeOverride: NativeType? = null,
        shimmerView: View? = null,
    ) {
        if (!container.isAttachedToWindow) {
            destroy(container)
            return
        }

        val config =
            configProvider()

        if (!config.ads.globalEnabled) {
            destroy(container)
            return
        }

        if (!NetworkState.isOnline(activity)) {
            destroy(container)
            return
        }

        val placement = config.ads.placements.firstOrNull {
                it.name == placementName &&
                        it.enabled &&
                        it.format == "native"
            } ?: run {

                destroy(container)

                return
            }

        if (!AdLoadBackoff.canRequest(placement)) {
            destroy(container)
            return
        }
        val resolvedNativeType =
            resolveNativeType(
                placement,
                nativeTypeOverride
            )

        val activeKey = listOf(placementName, resolvedNativeType.name).joinToString("|")
        synchronized(activeLoadKeys) {
            if (activeLoadKeys[container] == activeKey && container.childCount > 0) {
                container.visibility = View.VISIBLE
                return
            }
            activeLoadKeys[container] = activeKey
        }

        val token = nextToken(container)

        /*
        |--------------------------------------------------------------------------
        | Shimmer
        |--------------------------------------------------------------------------
        */

        val loadingView =
            shimmerView ?: createDefaultShimmer(
                activity,
                container,
                resolvedNativeType
            )

        loadingView?.let {
            container.removeAllViews()
            container.addView(it)
            it.visibility = View.VISIBLE
            (it as? ShimmerFrameLayout)?.startShimmer()
        }

        /*
        |--------------------------------------------------------------------------
        | Custom Native
        |--------------------------------------------------------------------------
        */

        val customAd = selectedCustomAd(config, placement)

        if (customAd != null) {
            AdEventTracker.log("ad_load_requested", placement)
            preloadCustomAd(
                activity = activity,
                container = container,
                ad = customAd,
                placement = placement,
                type = resolvedNativeType,
                loadingView = loadingView,
                token = token
            )

            return
        }

        /*
        |--------------------------------------------------------------------------
        | AdMob
        |--------------------------------------------------------------------------
        */

        val unit =
            placement.units.firstOrNull {
                it.network == "admob"
            } ?: run {
                synchronized(activeLoadKeys) {
                    activeLoadKeys.remove(container)
                }
                stopShimmer(
                    loadingView
                )

                destroy(container)

                return
            }

        if (
            activity.isFinishing ||
            activity.isDestroyed
        ) {

            stopShimmer(
                loadingView
            )

            return
        }

        try {

            val request =
                NativeAdRequest.Builder(
                    adUnitId = unit.adUnitId,
                    nativeAdTypes = listOf(
                        NativeAd.NativeAdType.NATIVE
                    )
                ).build()

            AdEventTracker.log("ad_load_requested", placement)

            NativeAdLoader.load(
                request,
                object : NativeAdLoaderCallback {

                    override fun onNativeAdLoaded(
                        nativeAd: NativeAd
                    ) {

                        activity.runOnUiThread {
                            if (
                                activity.isFinishing ||
                                activity.isDestroyed ||
                                !container.isAttachedToWindow ||
                                !isCurrentLoad(container, token)
                            ) {
                                nativeAd.destroy()
                                return@runOnUiThread
                            }

                            synchronized(nativeAds) {
                                nativeAds.remove(container)?.destroy()
                                nativeAds[container] = nativeAd
                            }
                            AdEventTracker.log("ad_loaded", placement)
                            AdLoadBackoff.recordSuccess(placement)

                            nativeAd.adEventCallback = object : NativeAdEventCallback {
                                override fun onAdPaid(adValue: AdValue) {
                                    AdEventTracker.log(
                                        "ad_paid",
                                        placement,
                                        mapOf(
                                            "revenue_micros" to adValue.valueMicros,
                                            "currency" to adValue.currencyCode,
                                            "precision" to adValue.precisionType,
                                            "ad_unit_id" to unit.adUnitId,
                                        ),
                                    )
                                }
                            }

                            @LayoutRes
                            val layoutRes =
                                when (
                                    resolvedNativeType
                                ) {

                                    NativeType.LARGE ->
                                        R.layout.native_admob_large

                                    NativeType.SMALL ->
                                        R.layout.native_admob_small
                                }

                            val adView =
                                LayoutInflater.from(activity)
                                    .inflate(
                                        layoutRes,
                                        container,
                                        false
                                    ) as NativeAdView
                            adView.applyTransparentNativeRoot()
                            adView.applyNativePlacementStyle(placement.metadata)

                            stopShimmer(
                                loadingView
                            )

                            container.removeAllViews()

                            container.addView(adView)

                            container.visibility =
                                View.VISIBLE

                            populateNativeAdView(
                                nativeAd,
                                adView,
                                placement.metadata
                            )
                            AdEventTracker.log("ad_impression", placement)

                            adView.alpha = 0f

                            adView.animate()
                                .alpha(1f)
                                .setDuration(250)
                                .start()
                        }
                    }

                    override fun onAdFailedToLoad(
                        adError: LoadAdError
                    ) {

                            activity.runOnUiThread {

                            if (!isCurrentLoad(container, token)) {
                                return@runOnUiThread
                            }

                            stopShimmer(
                                loadingView
                            )

                            container.visibility =
                                View.GONE
                            synchronized(activeLoadKeys) {
                                activeLoadKeys.remove(container)
                            }
                            AdEventTracker.log(
                                "ad_load_failed",
                                placement,
                                mapOf("message" to adError.message),
                            )
                            AdLoadBackoff.recordFailure(placement, adError.message)
                            val fallback = config.customFallbackFor(placement)
                            if (fallback != null) {
                                AdEventTracker.log("ad_custom_fallback", placement, mapOf("reason" to adError.message))
                                preloadCustomAd(activity, container, fallback, placement, resolvedNativeType, loadingView, token)
                                return@runOnUiThread
                            }
                        }
                    }
                }
            )

        } catch (exception: Exception) {

            if (!isCurrentLoad(container, token)) return

            stopShimmer(loadingView)

            container.visibility =
                View.GONE
            synchronized(activeLoadKeys) {
                activeLoadKeys.remove(container)
            }
            val message = exception.message
                ?.take(180)
                ?: exception::class.java.simpleName
            AdEventTracker.log("ad_load_failed", placement, mapOf("message" to message))
            AdLoadBackoff.recordFailure(placement, message)
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Destroy
    |--------------------------------------------------------------------------
    */

    fun destroy(
        container: ViewGroup? = null
    ) {

        if (container == null) {
            val ads = synchronized(nativeAds) {
                nativeAds.values.toList().also { nativeAds.clear() }
            }
            ads.forEach { ad -> runCatching { ad.destroy() } }
        } else {
            val ad = synchronized(nativeAds) { nativeAds.remove(container) }
            runCatching { ad?.destroy() }
            synchronized(loadTokens) {
                loadTokens.remove(container)
            }
            synchronized(activeLoadKeys) {
                activeLoadKeys.remove(container)
            }
        }

        container?.let {

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

    /*
    |--------------------------------------------------------------------------
    | AdMob Populate
    |--------------------------------------------------------------------------
    */

    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView, metadata: Map<String, Any?>) {

        val ad_tag = adView.findViewById<TextView>(R.id.ad_ic)


        adView.headlineView = adView.findViewById(R.id.ad_headline)

        adView.bodyView =
            adView.findViewById(
                R.id.ad_body
            )

        adView.callToActionView =
            adView.findViewById(
                R.id.ad_call_to_action
            )

        adView.iconView =
            adView.findViewById(
                R.id.ad_app_icon
            )


        adView.priceView =
            adView.findViewById(
                R.id.ad_price
            )

        adView.starRatingView =
            adView.findViewById(
                R.id.ad_stars
            )

        adView.storeView =
            adView.findViewById(
                R.id.ad_store
            )

        adView.advertiserView =
            adView.findViewById(
                R.id.ad_advertiser
            )

        (adView.headlineView as? TextView)
            ?.text = nativeAd.headline
        val nativeTextColor = metadata.stringValue("native_text_color", "banner_text_color")
            ?: sdkColor("native_text_color", "banner_text_color", "text_color")
        val secondaryTextColor = metadata.stringValue("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: sdkColor("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: nativeTextColor
        (adView.headlineView as? TextView)?.setTextColor(parseColorSafe(metadata.stringValue("native_headline_text_color", "headline_text_color") ?: nativeTextColor, Color.rgb(17, 24, 39)))
        (adView.bodyView as? TextView)?.setTextColor(parseColorSafe(metadata.stringValue("native_body_text_color", "body_text_color") ?: secondaryTextColor, Color.rgb(71, 85, 105)))
        listOf(adView.priceView, adView.storeView, adView.advertiserView).forEach { view ->
            (view as? TextView)?.setTextColor(parseColorSafe(metadata.stringValue("native_meta_text_color", "meta_text_color") ?: secondaryTextColor, Color.rgb(100, 116, 139)))
        }

        nativeAd.body?.let { (adView.bodyView as? TextView)?.text = it
            adView.bodyView?.visibility =
                View.VISIBLE

        } ?: run {

            adView.bodyView?.visibility = View.INVISIBLE
        }

        nativeAd.callToAction?.let {
            (adView.callToActionView as? Button)?.text = it
            adView.callToActionView?.visibility = View.VISIBLE
        } ?: run {
            adView.callToActionView?.visibility = View.INVISIBLE
        }

        val ctaDrawable = adView.callToActionView?.background?.mutate() as? GradientDrawable
        ctaDrawable?.setColor(parseColorSafe(ITWingSDK.getColor("primary"), Color.rgb(37, 99, 235)))
        (adView.callToActionView as? TextView)?.setTextColor(
            parseColorSafe(metadata.stringValue("native_cta_text_color", "banner_cta_text_color", "cta_text_color") ?: sdkColor("native_cta_text_color", "banner_cta_text_color", "cta_text_color"), Color.WHITE)
        )

        val adTagColor = ad_tag?.background?.mutate() as? GradientDrawable
        adTagColor?.setColor(parseColorSafe(ITWingSDK.getColor("primary"), Color.rgb(37, 99, 235)))
        ad_tag?.setTextColor(parseColorSafe(metadata.stringValue("native_ad_label_text_color", "ad_label_text_color"), Color.WHITE))


        nativeAd.icon?.drawable?.let {
            (adView.iconView as? ImageView)?.setImageDrawable(it)
            adView.iconView?.visibility =
                View.VISIBLE

        } ?: run { adView.iconView?.visibility = View.GONE }

        nativeAd.price?.let {
            (adView.priceView as? TextView)?.text = it
            adView.priceView?.visibility = View.VISIBLE
        } ?: run {
            adView.priceView?.visibility = View.INVISIBLE
        }

        nativeAd.store?.let {
            (adView.storeView as? TextView)?.text = it
            adView.storeView?.visibility = View.VISIBLE

        } ?: run {

            adView.storeView?.visibility =
                View.INVISIBLE
        }

        nativeAd.starRating?.let {
            (adView.starRatingView as? RatingBar)?.rating = it.toFloat()
            adView.starRatingView?.visibility = View.VISIBLE
        } ?: run {
            adView.starRatingView?.visibility = View.INVISIBLE
        }

        nativeAd.advertiser?.let {

            (adView.advertiserView as? TextView)
                ?.text = it

            adView.advertiserView?.visibility =
                View.VISIBLE

        } ?: run {

            adView.advertiserView?.visibility =
                View.INVISIBLE
        }

        adView.registerNativeAd(nativeAd, adView.findViewById(R.id.ad_media))
    }

    /*
    |--------------------------------------------------------------------------
    | Custom Native
    |--------------------------------------------------------------------------
    */

    private fun renderCustomNative(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        placement: AdPlacementConfig,
        type: NativeType
    ) {

        destroy(container)

        @LayoutRes
        val layoutRes =
            when (type) {

                NativeType.LARGE ->
                    R.layout.custom_native_large

                NativeType.SMALL ->
                    R.layout.custom_native_small
            }

        val root =
            LayoutInflater.from(activity)
                .inflate(
                    layoutRes,
                    container,
                    false
                )
        root.applyTransparentNativeRoot()
        root.applyNativePlacementStyle(placement.metadata)

        /*
        |--------------------------------------------------------------------------
        | Views
        |--------------------------------------------------------------------------
        */

        val headlineView =
            root.findViewById<TextView?>(
                R.id.ad_headline
            )

        val bodyView =
            root.findViewById<TextView?>(
                R.id.ad_body
            )

        val ctaView =
            root.findViewById<Button?>(
                R.id.ad_call_to_action
            )

        val adIcon =
            root.findViewById<ImageView?>(
                R.id.ad_app_icon
            )

        val advertiserView =
            root.findViewById<TextView?>(
                R.id.ad_advertiser
            )

        val mediaView =
            root.findViewById<SDKMediaView?>(
                R.id.ad_media
            )

        val ratingView =
            root.findViewById<RatingBar?>(
                R.id.ad_stars
            )

        val storeView =
            root.findViewById<TextView?>(
                R.id.ad_store
            )

        val priceView =
            root.findViewById<TextView?>(
                R.id.ad_price
            )

        val adTag =
            root.findViewById<TextView?>(
                R.id.ad_ic
            )

        /*
        |--------------------------------------------------------------------------
        | Text
        |--------------------------------------------------------------------------
        */

        headlineView?.text =
            ad.headline?.takeIf {
                it.isNotBlank()
            }
                ?: ad.name.ifBlank {
                    "Sponsored"
                }

        bodyView?.text =
            ad.body?.takeIf {
                it.isNotBlank()
            }
                ?: "Promoted content"

        ctaView?.text =
            ad.cta?.takeIf {
                it.isNotBlank()
            }
                ?: "Install"

        advertiserView?.text =
            ad.brandName()
                ?: "Sponsored"

        storeView?.text = ""

        priceView?.text = ""

        ratingView?.rating =
            ad.brandRating()

        adTag?.text =
            ad.adIcon()

        /*
        |--------------------------------------------------------------------------
        | Colors
        |--------------------------------------------------------------------------
        */

        val adTagDrawable =
            adTag?.background
                ?.mutate() as? GradientDrawable

        adTagDrawable?.setColor(
            parseColorSafe(
                ad.primaryColor(),
                Color.rgb(
                    37,
                    99,
                    235
                )
            )
        )

        val ctaDrawable =
            ctaView?.background
                ?.mutate() as? GradientDrawable

        ctaDrawable?.setColor(
            parseColorSafe(
                ad.primaryColor(),
                Color.rgb(
                    37,
                    99,
                    235
                )
            )
        )
        ctaView?.setTextColor(parseColorSafe(placement.metadata.stringValue("native_cta_text_color", "cta_text_color") ?: sdkColor("cta_text_color"), Color.WHITE))
        adTag?.setTextColor(parseColorSafe(placement.metadata.stringValue("native_ad_label_text_color", "ad_label_text_color"), Color.WHITE))

        /*
        |--------------------------------------------------------------------------
        | Icon
        |--------------------------------------------------------------------------
        */

        loadImage(
            ad.brandLogoUrl(),
            adIcon,
            activity
        )

        /*
        |--------------------------------------------------------------------------
        | Visibility
        |--------------------------------------------------------------------------
        */

        headlineView?.visibility =
            View.VISIBLE

        bodyView?.visibility =
            View.VISIBLE

        ctaView?.visibility =
            View.VISIBLE

        advertiserView?.visibility =
            View.VISIBLE

        storeView?.visibility =
            View.VISIBLE

        ratingView?.visibility =
            View.VISIBLE

        /*
        |--------------------------------------------------------------------------
        | Media
        |--------------------------------------------------------------------------
        */

        mediaView?.apply {

            render(
                ad.mediaUrl(),
                ad.isVideo()
            )

            play()
        }

        /*
        |--------------------------------------------------------------------------
        | Click
        |--------------------------------------------------------------------------
        */

        val clickListener =
            View.OnClickListener {

                ITWingSDK.trackCustomAdClick(
                    ad.id,
                    mapOf(
                        "placement" to "native",
                        "native_type" to type.name.lowercase()
                    )
                )

                (ad.androidTargetUrl ?: ad.targetUrl)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let { url ->

                        runCatching {

                            /*
                            |--------------------------------------------------------------------------
                            | Pause ONLY clicked media
                            |--------------------------------------------------------------------------
                            */

                            mediaView?.pauseForExternalNavigation()

                            /*
                            |--------------------------------------------------------------------------
                            | Open Browser
                            |--------------------------------------------------------------------------
                            */

                            activity.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    url.toUri()
                                )
                            )

                            /*
                            |--------------------------------------------------------------------------
                            | Resume ONLY clicked media
                            |--------------------------------------------------------------------------
                            */

                            activity.application
                                .registerActivityLifecycleCallbacks(
                                    object : Application.ActivityLifecycleCallbacks {

                                        override fun onActivityResumed(
                                            resumedActivity: Activity
                                        ) {

                                            if (
                                                resumedActivity ==
                                                activity
                                            ) {

                                                mediaView?.resumeFromExternalNavigation()

                                                activity.application.unregisterActivityLifecycleCallbacks(this)
                                            }
                                        }

                                        override fun onActivityCreated(
                                            activity: Activity,
                                            savedInstanceState: Bundle?
                                        ) {
                                        }

                                        override fun onActivityStarted(
                                            activity: Activity
                                        ) {
                                        }

                                        override fun onActivityPaused(
                                            activity: Activity
                                        ) {
                                        }

                                        override fun onActivityStopped(
                                            activity: Activity
                                        ) {
                                        }

                                        override fun onActivitySaveInstanceState(
                                            activity: Activity,
                                            outState: Bundle
                                        ) {
                                        }

                                        override fun onActivityDestroyed(
                                            activity: Activity
                                        ) {
                                        }
                                    }
                                )
                        }
                    }
            }

        root.setOnClickListener(
            clickListener
        )

        ctaView?.setOnClickListener(
            clickListener
        )

        /*
        |--------------------------------------------------------------------------
        | Render
        |--------------------------------------------------------------------------
        */

        container.removeAllViews()

        container.addView(root)

        container.visibility =
            View.VISIBLE

        /*
        |--------------------------------------------------------------------------
        | Impression
        |--------------------------------------------------------------------------
        */

        ITWingSDK.trackCustomAdImpression(
            ad.id,
            mapOf(
                "placement" to "native",
                "native_type" to type.name.lowercase()
            )
        )
    }

    private fun loadImage(url: String?, imageView: ImageView?, activity: Activity) {
        if (url.isNullOrBlank()) {
            return
        }
        activity.runOnUiThread {
            runCatching {
                imageView?.let {
                    Glide.with(it)
                        .load(url)
                        .fitCenter()
                        .into(it)
                }

                imageView?.visibility = View.VISIBLE
            }
        }
    }

    private fun View.applyTransparentNativeRoot() {
        setBackgroundResource(R.drawable.itwing_purchase_dialog_bg)
        findViewById<View?>(R.id.ad_unit_content)?.setBackgroundColor(Color.TRANSPARENT)
        clearNativeChildBackgrounds()
    }

    private fun View.applyNativePlacementStyle(metadata: Map<String, Any?>) {
        val transparent = metadata.booleanValue("native_transparent_background", true)
        if (transparent) {
            setBackgroundResource(R.drawable.itwing_purchase_dialog_bg)
            findViewById<View?>(R.id.ad_unit_content)?.setBackgroundColor(Color.TRANSPARENT)
            clearNativeChildBackgrounds()
        } else {
            val background = parseColorSafe(metadata.stringValue("native_background_color", "background_color"), Color.TRANSPARENT)
            applyBackgroundRecursively(background)
        }
        val nativeTextColor = metadata.stringValue("native_text_color", "banner_text_color")
            ?: sdkColor("native_text_color", "banner_text_color", "text_color")
        val secondaryTextColor = metadata.stringValue("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: sdkColor("native_secondary_text_color", "banner_secondary_text_color", "secondary_text_color")
            ?: nativeTextColor
        val headline = parseColorSafe(metadata.stringValue("native_headline_text_color", "headline_text_color") ?: nativeTextColor, Color.rgb(248, 250, 252))
        val body = parseColorSafe(metadata.stringValue("native_body_text_color", "body_text_color") ?: secondaryTextColor, Color.rgb(203, 213, 225))
        val meta = parseColorSafe(metadata.stringValue("native_meta_text_color", "meta_text_color") ?: secondaryTextColor, Color.rgb(203, 213, 225))
        listOf(R.id.ad_headline).forEach { findViewById<TextView?>(it)?.setTextColor(headline) }
        listOf(R.id.ad_body).forEach { findViewById<TextView?>(it)?.setTextColor(body) }
        listOf(R.id.ad_advertiser, R.id.ad_store, R.id.ad_price).forEach { findViewById<TextView?>(it)?.setTextColor(meta) }
    }

    private fun View.clearNativeChildBackgrounds() {
        if (this !is ViewGroup) return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id != R.id.ad_call_to_action && child.id != R.id.ad_ic) {
                if (child is ViewGroup) {
                    child.setBackgroundColor(Color.TRANSPARENT)
                    child.clearNativeChildBackgrounds()
                }
            }
        }
    }

    private fun View.applyBackgroundRecursively(color: Int) {
        if (id != R.id.ad_call_to_action && id != R.id.ad_ic) {
            setBackgroundColor(color)
        }
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).applyBackgroundRecursively(color)
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Helpers
    |--------------------------------------------------------------------------
    */

    private fun resolveNativeType(
        placement: AdPlacementConfig,
        override: NativeType?,
    ): NativeType {
        if (override != null) {
            return override
        }

        return when ((
                placement.metadata["native_type"]
                    ?: placement.metadata["native_template"]
                )
            ?.toString()
            ?.lowercase()
        ) {

            "small" ->
                NativeType.SMALL

            else ->
                NativeType.LARGE
        }
    }

    private fun createDefaultShimmer(
        activity: Activity,
        container: ViewGroup,
        nativeType: NativeType
    ): View? {

        return runCatching {

            val layoutRes =
                when (nativeType) {

                    NativeType.LARGE ->
                        R.layout.large_shimmer

                    NativeType.SMALL ->
                        R.layout.small_shimmer
                }

            LayoutInflater.from(activity)
                .inflate(
                    layoutRes,
                    container,
                    false
                )

        }.getOrNull()
    }

    private fun stopShimmer(
        loadingView: View?
    ) {

        (loadingView as? ShimmerFrameLayout)
            ?.stopShimmer()

        loadingView?.visibility =
            View.GONE
    }

    private fun selectedCustomAd(
        config: ITWingConfig,
        placement: AdPlacementConfig
    ): CustomAdConfig? {

        val source =
            placement.metadata["source"]
                ?.toString()
                ?.lowercase()

        if (
            source != "custom" &&
            source != "custom_ad" &&
            placement.customAd == null
        ) {

            return null
        }

        placement.customAd?.takeIf { !it.mediaUrl().isNullOrBlank() }?.let {
            return it
        }

        val requestedId =
            placement.metadata["custom_ad_id"]
                ?.toString()
                ?.takeIf {
                    it.isNotBlank()
                }

        return config.ads.customAds
            .filter {
                it.format == "native" ||
                        it.format == "image" ||
                        it.format == "html"
            }
            .filter {
                !it.mediaUrl().isNullOrBlank()
            }
            .filter {

                requestedId == null ||
                        it.id == requestedId
            }
            .minByOrNull {
                it.priority
            }
    }

    private fun preloadCustomAd(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        placement: AdPlacementConfig,
        type: NativeType,
        loadingView: View?,
        token: Int
    ) {

        val media = ad.mediaUrl()

        /*
        |--------------------------------------------------------------------------
        | No Media
        |--------------------------------------------------------------------------
        */

        if (media.isNullOrBlank()) {
            activity.runOnUiThread {
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    !container.isAttachedToWindow ||
                    !isCurrentLoad(container, token)
                ) {
                    stopShimmer(loadingView)
                    return@runOnUiThread
                }
                stopShimmer(loadingView)
                renderCustomNative(activity = activity, container = container, ad = ad, placement = placement, type = type)
            }

            return
        }

        /*
        |--------------------------------------------------------------------------
        | Keep shimmer visible while preloading
        |--------------------------------------------------------------------------
        */

        Glide.with(activity.applicationContext).load(media).preload()

        /*
        |--------------------------------------------------------------------------
        | Simulate real network loading behavior
        |--------------------------------------------------------------------------
        */

        container.postDelayed({

            activity.runOnUiThread {
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    !container.isAttachedToWindow ||
                    !isCurrentLoad(container, token)
                ) {
                    stopShimmer(loadingView)
                    return@runOnUiThread
                }

                renderCustomNative(
                    activity = activity,
                    container = container,
                    ad = ad,
                    placement = placement,
                    type = type
                )

                stopShimmer(
                    loadingView
                )

                container.alpha = 0f

                container.animate()
                    .alpha(1f)
                    .setDuration(250)
                    .start()
            }

        }, 650)
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

    private fun releaseMediaViews(
        parent: ViewGroup
    ) {

        for (i in 0 until parent.childCount) {

            val child =
                parent.getChildAt(i)

            when (child) {

                is SDKMediaView -> {

                    child.release()
                }

                is ViewGroup -> {

                    releaseMediaViews(child)
                }
            }
        }
    }

    private fun CustomAdConfig.mediaUrl(): String? =
        mediaUrl?.takeIf {
            it.isNotBlank()
        }
            ?: videoUrl?.takeIf {
                it.isNotBlank()
            }
            ?: imageUrl?.takeIf {
                it.isNotBlank()
            }

    private fun CustomAdConfig.isVideo(): Boolean = mediaType.equals(
        "video",
        ignoreCase = true
    ) || (!videoUrl.isNullOrBlank() && mediaUrl == videoUrl)

    private fun CustomAdConfig.primaryColor(): String? =
        (metadata["ad_primary_color"] as? String)?.takeIf { it.isNotBlank() }
        ?: ((metadata["brand"] as? Map<*, *>)?.get("primary_color") as? String)?.takeIf { it.isNotBlank() }
        ?: ITWingSDK.getColor("primary").takeIf { it.isNotBlank() }
        ?: ITWingSDK.getColor("primary_color").takeIf { it.isNotBlank() }

    private fun sdkColor(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> ITWingSDK.getColor(name).takeIf { it.isNotBlank() } }

    private fun CustomAdConfig.brandName(): String? =
        (
                metadata["brand"]
                        as? Map<*, *>
                )?.get("name")
                as? String
            ?: campaignGroup

    private fun CustomAdConfig.brandRating(): Float {
        val value = metadata["brand_rating"] ?: (metadata["brand"] as? Map<*, *>)?.get("rating")
        return when (value) {
            is Number ->
                value.toFloat()

            is String ->
                value.toFloatOrNull()

            else ->
                null
        }?.coerceIn(0f, 5f)
            ?: 4.5f
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

    private fun Map<String, Any?>.booleanValue(key: String, default: Boolean): Boolean =
        when (val value = this[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "1", "true", "yes", "on", "enabled" -> true
                "0", "false", "no", "off", "disabled" -> false
                else -> default
            }
            else -> default
        }


    private fun parseColorSafe(value: String?, fallback: Int): Int =
        runCatching {
            if (value.isNullOrBlank()) {
                fallback
            } else {
                value.toColorInt()
            }

        }.getOrDefault(fallback)
}
