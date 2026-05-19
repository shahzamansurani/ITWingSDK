package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.SDKMediaView

class NativeLoader(
    private val configProvider: () -> ITWingConfig
) {
    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var currentNativeAd: NativeAd? =
        null

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
        shimmerView: View? = null
    ) {
        runOnMain {

            if (
                !isActivityUsable(activity) ||
                !isContainerUsable(container)
            ) {
                return@runOnMain
            }

            val config =
                safeConfig()
                    ?: run {
                        destroy(container)
                        return@runOnMain
                    }

            if (!config.ads.globalEnabled) {

                destroy(container)

                return@runOnMain
            }

            val placement =
                config.ads.placements.firstOrNull {

                    it.name == placementName &&
                            it.enabled &&
                            it.format == "native"
                } ?: run {

                    destroy(container)

                    return@runOnMain
                }

            val resolvedNativeType =
                resolveNativeType(
                    placement,
                    nativeTypeOverride
                )

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

                runCatching {

                    container.removeAllViews()

                    detachFromParent(it)

                    container.addView(it)

                    it.visibility =
                        View.VISIBLE

                    (it as? ShimmerFrameLayout)
                        ?.startShimmer()
                }
            }

            /*
            |--------------------------------------------------------------------------
            | Custom Native
            |--------------------------------------------------------------------------
            */

            val customAd =
                selectedCustomAd(
                    config,
                    placement
                )

            if (customAd != null) {

                preloadCustomAd(
                    activity = activity,
                    container = container,
                    ad = customAd,
                    type = resolvedNativeType,
                    loadingView = loadingView
                )

                return@runOnMain
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

                    stopShimmer(
                        loadingView
                    )

                    destroy(container)

                    return@runOnMain
                }

            if (!isActivityUsable(activity)) {

                stopShimmer(
                    loadingView
                )

                return@runOnMain
            }

            val request =
                runCatching {

                    NativeAdRequest.Builder(
                        adUnitId = unit.adUnitId,
                        nativeAdTypes = listOf(
                            NativeAd.NativeAdType.NATIVE
                        )
                    ).build()

                }.getOrElse {

                    stopShimmer(
                        loadingView
                    )

                    container.visibility =
                        View.GONE

                    return@runOnMain
                }

            runCatching {

                NativeAdLoader.load(
                    request,
                    object : NativeAdLoaderCallback {

                        override fun onNativeAdLoaded(
                            nativeAd: NativeAd
                        ) {
                            runOnMain {

                                if (
                                    !isActivityUsable(activity) ||
                                    !isContainerUsable(container)
                                ) {

                                    runCatching {
                                        nativeAd.destroy()
                                    }

                                    return@runOnMain
                                }

                                runCatching {

                                    currentNativeAd?.destroy()

                                    currentNativeAd =
                                        nativeAd

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

                                    stopShimmer(
                                        loadingView
                                    )

                                    container.removeAllViews()

                                    detachFromParent(
                                        adView
                                    )

                                    container.addView(
                                        adView
                                    )

                                    container.visibility =
                                        View.VISIBLE

                                    populateNativeAdView(
                                        nativeAd,
                                        adView
                                    )

                                    adView.alpha =
                                        0f

                                    adView.animate()
                                        .alpha(1f)
                                        .setDuration(250)
                                        .start()

                                }.onFailure {

                                    runCatching {
                                        nativeAd.destroy()
                                    }

                                    stopShimmer(
                                        loadingView
                                    )

                                    container.visibility =
                                        View.GONE
                                }
                            }
                        }

                        override fun onAdFailedToLoad(
                            adError: LoadAdError
                        ) {
                            runOnMain {

                                stopShimmer(
                                    loadingView
                                )

                                if (isContainerUsable(container)) {

                                    container.visibility =
                                        View.GONE
                                }
                            }
                        }
                    }
                )

            }.onFailure {

                stopShimmer(
                    loadingView
                )

                container.visibility =
                    View.GONE
            }
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
        runOnMain {

            runCatching {

                currentNativeAd?.destroy()
            }

            currentNativeAd =
                null

            container?.let {

                runCatching {

                    releaseMediaViews(it)

                    it.removeAllViews()
                }
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | AdMob Populate
    |--------------------------------------------------------------------------
    */

    private fun populateNativeAdView(
        nativeAd: NativeAd,
        adView: NativeAdView
    ) {
        runCatching {

            val adTag =
                adView.findViewById<TextView?>(
                    R.id.ad_ic
                )

            adView.headlineView =
                adView.findViewById(
                    R.id.ad_headline
                )

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
                ?.text =
                nativeAd.headline

            nativeAd.body?.let {

                (adView.bodyView as? TextView)
                    ?.text =
                    it

                adView.bodyView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.bodyView?.visibility =
                    View.INVISIBLE
            }

            nativeAd.callToAction?.let {

                (adView.callToActionView as? Button)
                    ?.text =
                    it

                adView.callToActionView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.callToActionView?.visibility =
                    View.INVISIBLE
            }

            val ctaDrawable =
                adView.callToActionView
                    ?.background
                    ?.mutate() as? GradientDrawable

            ctaDrawable?.setColor(
                parseColorSafe(
                    ITWingSDK.getColor("primary"),
                    Color.rgb(
                        37,
                        99,
                        235
                    )
                )
            )

            val adTagColor =
                adTag
                    ?.background
                    ?.mutate() as? GradientDrawable

            adTagColor?.setColor(
                parseColorSafe(
                    ITWingSDK.getColor("primary"),
                    Color.rgb(
                        37,
                        99,
                        235
                    )
                )
            )

            nativeAd.icon?.drawable?.let {

                (adView.iconView as? ImageView)
                    ?.setImageDrawable(
                        it
                    )

                adView.iconView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.iconView?.visibility =
                    View.GONE
            }

            nativeAd.price?.let {

                (adView.priceView as? TextView)
                    ?.text =
                    it

                adView.priceView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.priceView?.visibility =
                    View.INVISIBLE
            }

            nativeAd.store?.let {

                (adView.storeView as? TextView)
                    ?.text =
                    it

                adView.storeView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.storeView?.visibility =
                    View.INVISIBLE
            }

            nativeAd.starRating?.let {

                (adView.starRatingView as? RatingBar)
                    ?.rating =
                    it.toFloat()

                adView.starRatingView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.starRatingView?.visibility =
                    View.INVISIBLE
            }

            nativeAd.advertiser?.let {

                (adView.advertiserView as? TextView)
                    ?.text =
                    it

                adView.advertiserView?.visibility =
                    View.VISIBLE

            } ?: run {

                adView.advertiserView?.visibility =
                    View.INVISIBLE
            }

            adView.registerNativeAd(
                nativeAd,
                adView.findViewById(
                    R.id.ad_media
                )
            )
        }
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
        type: NativeType
    ) {
        runOnMain {

            if (
                !isActivityUsable(activity) ||
                !isContainerUsable(container)
            ) {
                return@runOnMain
            }

            runCatching {

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
                    ad.headline
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: ad.name.ifBlank {
                            "Sponsored"
                        }

                bodyView?.text =
                    ad.body
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Promoted content"

                ctaView?.text =
                    ad.cta
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Install"

                advertiserView?.text =
                    ad.brandName()
                        ?: "Sponsored"

                storeView?.text =
                    ""

                priceView?.text =
                    ""

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
                    adTag
                        ?.background
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
                    ctaView
                        ?.background
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

                mediaView?.let { media ->

                    runCatching {

                        media.render(
                            ad.mediaUrl(),
                            ad.isVideo()
                        )

                        media.play()
                    }
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

                        openTargetUrl(
                            activity,
                            ad.targetUrl,
                            mediaView
                        )
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

                detachFromParent(
                    root
                )

                container.addView(
                    root
                )

                container.visibility =
                    View.VISIBLE

                root.alpha =
                    0f

                root.animate()
                    .alpha(1f)
                    .setDuration(250)
                    .start()

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
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Image
    |--------------------------------------------------------------------------
    */

    private fun loadImage(
        url: String?,
        imageView: ImageView?,
        activity: Activity
    ) {
        runOnMain {

            if (
                url.isNullOrBlank() ||
                imageView == null ||
                !isActivityUsable(activity)
            ) {
                imageView?.visibility =
                    View.GONE

                return@runOnMain
            }

            runCatching {

                Glide.with(activity)
                    .load(url)
                    .fitCenter()
                    .into(imageView)

                imageView.visibility =
                    View.VISIBLE
            }.onFailure {

                imageView.visibility =
                    View.GONE
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
        override: NativeType?
    ): NativeType {

        if (override != null) {
            return override
        }

        return when (
            (
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
        runOnMain {

            runCatching {

                (loadingView as? ShimmerFrameLayout)
                    ?.stopShimmer()

                loadingView?.apply {

                    visibility =
                        View.GONE

                    (parent as? ViewGroup)
                        ?.removeView(this)
                }
            }
        }
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

        placement.customAd?.let {
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
        type: NativeType,
        loadingView: View?
    ) {
        runOnMain {

            if (
                !isActivityUsable(activity) ||
                !isContainerUsable(container)
            ) {
                stopShimmer(
                    loadingView
                )
                return@runOnMain
            }

            val media =
                ad.mediaUrl()

            /*
            |--------------------------------------------------------------------------
            | No Media
            |--------------------------------------------------------------------------
            */

            if (media.isNullOrBlank()) {

                stopShimmer(
                    loadingView
                )

                renderCustomNative(
                    activity = activity,
                    container = container,
                    ad = ad,
                    type = type
                )

                return@runOnMain
            }

            /*
            |--------------------------------------------------------------------------
            | Keep shimmer visible while preloading
            |--------------------------------------------------------------------------
            */

            runCatching {

                Glide.with(
                    activity.applicationContext
                )
                    .load(media)
                    .preload()
            }

            /*
            |--------------------------------------------------------------------------
            | Simulate real network loading behavior
            |--------------------------------------------------------------------------
            */

            container.postDelayed({

                runOnMain {

                    if (
                        !isActivityUsable(activity) ||
                        !isContainerUsable(container)
                    ) {
                        stopShimmer(
                            loadingView
                        )
                        return@runOnMain
                    }

                    renderCustomNative(
                        activity = activity,
                        container = container,
                        ad = ad,
                        type = type
                    )

                    stopShimmer(
                        loadingView
                    )

                    runCatching {

                        container.alpha =
                            0f

                        container.animate()
                            .alpha(1f)
                            .setDuration(250)
                            .start()
                    }
                }

            }, 650)
        }
    }

    private fun openTargetUrl(
        activity: Activity,
        url: String?,
        mediaView: SDKMediaView?
    ) {
        runOnMain {

            if (
                url.isNullOrBlank() ||
                !isActivityUsable(activity)
            ) {
                return@runOnMain
            }

            var callbackRegistered =
                false

            runCatching {

                /*
                |--------------------------------------------------------------------------
                | Pause ONLY clicked media
                |--------------------------------------------------------------------------
                */

                mediaView?.pauseForExternalNavigation()

                val callbacks =
                    object : Application.ActivityLifecycleCallbacks {

                        override fun onActivityResumed(
                            resumedActivity: Activity
                        ) {

                            if (
                                resumedActivity ==
                                activity
                            ) {

                                runCatching {

                                    mediaView?.resumeFromExternalNavigation()
                                }

                                runCatching {

                                    activity.application
                                        .unregisterActivityLifecycleCallbacks(
                                            this
                                        )
                                }
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

                activity.application
                    .registerActivityLifecycleCallbacks(
                        callbacks
                    )

                callbackRegistered =
                    true

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

            }.onFailure {

                if (!callbackRegistered) {

                    runCatching {

                        mediaView?.resumeFromExternalNavigation()
                    }
                }
            }
        }
    }

    private fun releaseMediaViews(
        parent: ViewGroup
    ) {
        runCatching {

            for (i in 0 until parent.childCount) {

                val child =
                    parent.getChildAt(i)

                when (child) {

                    is SDKMediaView -> {

                        child.release()
                    }

                    is ViewGroup -> {

                        releaseMediaViews(
                            child
                        )
                    }
                }
            }
        }
    }

    private fun safeConfig(): ITWingConfig? {

        return runCatching {

            configProvider()

        }.getOrNull()
    }

    private fun runOnMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {

            block()

        } else {

            mainHandler.post {

                block()
            }
        }
    }

    private fun isActivityUsable(
        activity: Activity
    ): Boolean {
        return !activity.isFinishing &&
                !activity.isDestroyed
    }

    private fun isContainerUsable(
        container: ViewGroup
    ): Boolean {
        return container.context != null
    }

    private fun detachFromParent(
        view: View?
    ) {
        val parent =
            view?.parent as? ViewGroup

        parent?.removeView(
            view
        )
    }

    /*
    |--------------------------------------------------------------------------
    | CustomAdConfig Helpers
    |--------------------------------------------------------------------------
    */

    private fun CustomAdConfig.mediaUrl(): String? =
        mediaUrl
            ?.takeIf {
                it.isNotBlank()
            }
            ?: videoUrl
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: imageUrl
                ?.takeIf {
                    it.isNotBlank()
                }

    private fun CustomAdConfig.isVideo(): Boolean =
        mediaType.equals(
            "video",
            ignoreCase = true
        ) || (
                !videoUrl.isNullOrBlank() &&
                        mediaUrl == videoUrl
                )

    private fun CustomAdConfig.primaryColor(): String? =
        metadata["ad_primary_color"] as? String
            ?: (
                    metadata["brand"]
                            as? Map<*, *>
                    )?.get("primary_color") as? String

    private fun CustomAdConfig.brandName(): String? =
        (
                metadata["brand"]
                        as? Map<*, *>
                )?.get("name")
                as? String
            ?: campaignGroup

    private fun CustomAdConfig.brandRating(): Float {

        val value =
            metadata["brand_rating"]
                ?: (
                        metadata["brand"]
                                as? Map<*, *>
                        )?.get("rating")

        return when (value) {

            is Number ->
                value.toFloat()

            is String ->
                value.toFloatOrNull()

            else ->
                null
        }?.coerceIn(
            0f,
            5f
        ) ?: 4.5f
    }

    private fun CustomAdConfig.brandLogoUrl(): String? {

        val brand =
            metadata["brand"]
                    as? Map<*, *>
                ?: return null

        return brand["logo_url"]
                as? String
    }

    private fun CustomAdConfig.adIcon(): String =
        (
                metadata["ad_icon"]
                        as? String
                )?.takeIf {
                it.isNotBlank()
            }
            ?: "AD"

    private fun parseColorSafe(
        value: String?,
        fallback: Int
    ): Int =

        runCatching {

            if (value.isNullOrBlank()) {

                fallback

            } else {

                value.toColorInt()
            }

        }.getOrDefault(
            fallback
        )
}

//package com.itwingtech.itwingsdk.ads
//
//import android.app.Activity
//import android.app.Application
//import android.content.Intent
//import android.graphics.Color
//import android.graphics.drawable.GradientDrawable
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.Button
//import android.widget.ImageView
//import android.widget.RatingBar
//import android.widget.TextView
//import androidx.annotation.LayoutRes
//import com.bumptech.glide.Glide
//import com.facebook.shimmer.ShimmerFrameLayout
//import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
//import com.google.android.libraries.ads.mobile.sdk.common.VideoController
//import com.google.android.libraries.ads.mobile.sdk.nativead.MediaContent
//import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
//import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
//import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
//import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
//import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
//import com.itwingtech.itwingsdk.R
//import com.itwingtech.itwingsdk.core.AdPlacementConfig
//import com.itwingtech.itwingsdk.core.CustomAdConfig
//import com.itwingtech.itwingsdk.core.ITWingConfig
//import com.itwingtech.itwingsdk.core.ITWingSDK
//import com.itwingtech.itwingsdk.utils.SDKMediaView
//import androidx.core.graphics.toColorInt
//import androidx.core.net.toUri
//
//class NativeLoader(
//    private val configProvider: () -> ITWingConfig
//) {
//
//    private var currentNativeAd: NativeAd? =
//        null
//
//    /*
//    |--------------------------------------------------------------------------
//    | Load
//    |--------------------------------------------------------------------------
//    */
//
//    fun load(
//        activity: Activity,
//        container: ViewGroup,
//        placementName: String,
//        nativeTypeOverride: NativeType? = null,
//        shimmerView: View? = null,
//    ) {
//
//        val config =
//            configProvider()
//
//        if (!config.ads.globalEnabled) {
//
//            destroy(container)
//
//            return
//        }
//
//        val placement =
//            config.ads.placements.firstOrNull {
//
//                it.name == placementName &&
//                        it.enabled &&
//                        it.format == "native"
//            } ?: run {
//
//                destroy(container)
//
//                return
//            }
//
//        val resolvedNativeType =
//            resolveNativeType(
//                placement,
//                nativeTypeOverride
//            )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Shimmer
//        |--------------------------------------------------------------------------
//        */
//
//        val loadingView =
//            shimmerView ?: createDefaultShimmer(
//                activity,
//                container,
//                resolvedNativeType
//            )
//
//        loadingView?.let {
//            container.removeAllViews()
//            container.addView(it)
//            it.visibility = View.VISIBLE
//            (it as? ShimmerFrameLayout)?.startShimmer()
//        }
//
//        /*
//        |--------------------------------------------------------------------------
//        | Custom Native
//        |--------------------------------------------------------------------------
//        */
//
//        val customAd = selectedCustomAd(config, placement)
//
//        if (customAd != null) {
//            preloadCustomAd(
//                activity = activity,
//                container = container,
//                ad = customAd,
//                type = resolvedNativeType,
//                loadingView = loadingView
//            )
//
//            return
//        }
//
//        /*
//        |--------------------------------------------------------------------------
//        | AdMob
//        |--------------------------------------------------------------------------
//        */
//
//        val unit =
//            placement.units.firstOrNull {
//                it.network == "admob"
//            } ?: run {
//
//                stopShimmer(
//                    loadingView
//                )
//
//                destroy(container)
//
//                return
//            }
//
//        if (
//            activity.isFinishing ||
//            activity.isDestroyed
//        ) {
//
//            stopShimmer(
//                loadingView
//            )
//
//            return
//        }
//
//        try {
//
//            val request =
//                NativeAdRequest.Builder(
//                    adUnitId = unit.adUnitId,
//                    nativeAdTypes = listOf(
//                        NativeAd.NativeAdType.NATIVE
//                    )
//                ).build()
//
//            NativeAdLoader.load(
//                request,
//                object : NativeAdLoaderCallback {
//
//                    override fun onNativeAdLoaded(
//                        nativeAd: NativeAd
//                    ) {
//
//                        activity.runOnUiThread {
//
//                            currentNativeAd?.destroy()
//
//                            currentNativeAd =
//                                nativeAd
//
//                            @LayoutRes
//                            val layoutRes =
//                                when (
//                                    resolvedNativeType
//                                ) {
//
//                                    NativeType.LARGE ->
//                                        R.layout.native_admob_large
//
//                                    NativeType.SMALL ->
//                                        R.layout.native_admob_small
//                                }
//
//                            val adView =
//                                LayoutInflater.from(activity)
//                                    .inflate(
//                                        layoutRes,
//                                        container,
//                                        false
//                                    ) as NativeAdView
//
//                            stopShimmer(
//                                loadingView
//                            )
//
//                            container.removeAllViews()
//
//                            container.addView(adView)
//
//                            container.visibility =
//                                View.VISIBLE
//
//                            populateNativeAdView(
//                                nativeAd,
//                                adView
//                            )
//
//                            adView.alpha = 0f
//
//                            adView.animate()
//                                .alpha(1f)
//                                .setDuration(250)
//                                .start()
//                        }
//                    }
//
//                    override fun onAdFailedToLoad(
//                        adError: LoadAdError
//                    ) {
//
//                        activity.runOnUiThread {
//
//                            stopShimmer(
//                                loadingView
//                            )
//
//                            container.visibility =
//                                View.GONE
//                        }
//                    }
//                }
//            )
//
//        } catch (_: Exception) {
//
//            stopShimmer(
//                loadingView
//            )
//
//            container.visibility =
//                View.GONE
//        }
//    }
//
//    /*
//    |--------------------------------------------------------------------------
//    | Destroy
//    |--------------------------------------------------------------------------
//    */
//
//    fun destroy(
//        container: ViewGroup? = null
//    ) {
//
//        try {
//
//            currentNativeAd?.destroy()
//
//        } catch (_: Exception) {
//        }
//
//        currentNativeAd = null
//
//        container?.let {
//
//            releaseMediaViews(it)
//
//            it.removeAllViews()
//        }
//    }
//
//    /*
//    |--------------------------------------------------------------------------
//    | AdMob Populate
//    |--------------------------------------------------------------------------
//    */
//
//    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
//
//        val ad_tag = adView.findViewById<TextView>(R.id.ad_ic)
//
//
//        adView.headlineView = adView.findViewById(R.id.ad_headline)
//
//        adView.bodyView =
//            adView.findViewById(
//                R.id.ad_body
//            )
//
//        adView.callToActionView =
//            adView.findViewById(
//                R.id.ad_call_to_action
//            )
//
//        adView.iconView =
//            adView.findViewById(
//                R.id.ad_app_icon
//            )
//
//
//        adView.priceView =
//            adView.findViewById(
//                R.id.ad_price
//            )
//
//        adView.starRatingView =
//            adView.findViewById(
//                R.id.ad_stars
//            )
//
//        adView.storeView =
//            adView.findViewById(
//                R.id.ad_store
//            )
//
//        adView.advertiserView =
//            adView.findViewById(
//                R.id.ad_advertiser
//            )
//
//        (adView.headlineView as? TextView)
//            ?.text = nativeAd.headline
//
//        nativeAd.body?.let { (adView.bodyView as? TextView)?.text = it
//            adView.bodyView?.visibility =
//                View.VISIBLE
//
//        } ?: run {
//
//            adView.bodyView?.visibility = View.INVISIBLE
//        }
//
//        nativeAd.callToAction?.let {
//            (adView.callToActionView as? Button)?.text = it
//            adView.callToActionView?.visibility = View.VISIBLE
//        } ?: run {
//            adView.callToActionView?.visibility = View.INVISIBLE
//        }
//
//        val ctaDrawable = adView.callToActionView?.background?.mutate() as? GradientDrawable
//        ctaDrawable?.setColor(parseColorSafe(ITWingSDK.getColor("primary"), Color.rgb(37, 99, 235)))
//
//        val adTagColor = ad_tag?.background?.mutate() as? GradientDrawable
//        adTagColor?.setColor(parseColorSafe(ITWingSDK.getColor("primary"), Color.rgb(37, 99, 235)))
//
//
//        nativeAd.icon?.drawable?.let {
//            (adView.iconView as? ImageView)?.setImageDrawable(it)
//            adView.iconView?.visibility =
//                View.VISIBLE
//
//        } ?: run { adView.iconView?.visibility = View.GONE }
//
//        nativeAd.price?.let {
//            (adView.priceView as? TextView)?.text = it
//            adView.priceView?.visibility = View.VISIBLE
//        } ?: run {
//            adView.priceView?.visibility = View.INVISIBLE
//        }
//
//        nativeAd.store?.let {
//            (adView.storeView as? TextView)?.text = it
//            adView.storeView?.visibility = View.VISIBLE
//
//        } ?: run {
//
//            adView.storeView?.visibility =
//                View.INVISIBLE
//        }
//
//        nativeAd.starRating?.let {
//            (adView.starRatingView as? RatingBar)?.rating = it.toFloat()
//            adView.starRatingView?.visibility = View.VISIBLE
//        } ?: run {
//            adView.starRatingView?.visibility = View.INVISIBLE
//        }
//
//        nativeAd.advertiser?.let {
//
//            (adView.advertiserView as? TextView)
//                ?.text = it
//
//            adView.advertiserView?.visibility =
//                View.VISIBLE
//
//        } ?: run {
//
//            adView.advertiserView?.visibility =
//                View.INVISIBLE
//        }
//
//        adView.registerNativeAd(nativeAd, adView.findViewById(R.id.ad_media))
//    }
//
//    /*
//    |--------------------------------------------------------------------------
//    | Custom Native
//    |--------------------------------------------------------------------------
//    */
//
//    private fun renderCustomNative(
//        activity: Activity,
//        container: ViewGroup,
//        ad: CustomAdConfig,
//        type: NativeType
//    ) {
//
//        destroy(container)
//
//        @LayoutRes
//        val layoutRes =
//            when (type) {
//
//                NativeType.LARGE ->
//                    R.layout.custom_native_large
//
//                NativeType.SMALL ->
//                    R.layout.custom_native_small
//            }
//
//        val root =
//            LayoutInflater.from(activity)
//                .inflate(
//                    layoutRes,
//                    container,
//                    false
//                )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Views
//        |--------------------------------------------------------------------------
//        */
//
//        val headlineView =
//            root.findViewById<TextView?>(
//                R.id.ad_headline
//            )
//
//        val bodyView =
//            root.findViewById<TextView?>(
//                R.id.ad_body
//            )
//
//        val ctaView =
//            root.findViewById<Button?>(
//                R.id.ad_call_to_action
//            )
//
//        val adIcon =
//            root.findViewById<ImageView?>(
//                R.id.ad_app_icon
//            )
//
//        val advertiserView =
//            root.findViewById<TextView?>(
//                R.id.ad_advertiser
//            )
//
//        val mediaView =
//            root.findViewById<SDKMediaView?>(
//                R.id.ad_media
//            )
//
//        val ratingView =
//            root.findViewById<RatingBar?>(
//                R.id.ad_stars
//            )
//
//        val storeView =
//            root.findViewById<TextView?>(
//                R.id.ad_store
//            )
//
//        val priceView =
//            root.findViewById<TextView?>(
//                R.id.ad_price
//            )
//
//        val adTag =
//            root.findViewById<TextView?>(
//                R.id.ad_ic
//            )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Text
//        |--------------------------------------------------------------------------
//        */
//
//        headlineView?.text =
//            ad.headline?.takeIf {
//                it.isNotBlank()
//            }
//                ?: ad.name.ifBlank {
//                    "Sponsored"
//                }
//
//        bodyView?.text =
//            ad.body?.takeIf {
//                it.isNotBlank()
//            }
//                ?: "Promoted content"
//
//        ctaView?.text =
//            ad.cta?.takeIf {
//                it.isNotBlank()
//            }
//                ?: "Install"
//
//        advertiserView?.text =
//            ad.brandName()
//                ?: "Sponsored"
//
//        storeView?.text = ""
//
//        priceView?.text = ""
//
//        ratingView?.rating =
//            ad.brandRating()
//
//        adTag?.text =
//            ad.adIcon()
//
//        /*
//        |--------------------------------------------------------------------------
//        | Colors
//        |--------------------------------------------------------------------------
//        */
//
//        val adTagDrawable =
//            adTag?.background
//                ?.mutate() as? GradientDrawable
//
//        adTagDrawable?.setColor(
//            parseColorSafe(
//                ad.primaryColor(),
//                Color.rgb(
//                    37,
//                    99,
//                    235
//                )
//            )
//        )
//
//        val ctaDrawable =
//            ctaView?.background
//                ?.mutate() as? GradientDrawable
//
//        ctaDrawable?.setColor(
//            parseColorSafe(
//                ad.primaryColor(),
//                Color.rgb(
//                    37,
//                    99,
//                    235
//                )
//            )
//        )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Icon
//        |--------------------------------------------------------------------------
//        */
//
//        loadImage(
//            ad.brandLogoUrl(),
//            adIcon,
//            activity
//        )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Visibility
//        |--------------------------------------------------------------------------
//        */
//
//        headlineView?.visibility =
//            View.VISIBLE
//
//        bodyView?.visibility =
//            View.VISIBLE
//
//        ctaView?.visibility =
//            View.VISIBLE
//
//        advertiserView?.visibility =
//            View.VISIBLE
//
//        storeView?.visibility =
//            View.VISIBLE
//
//        ratingView?.visibility =
//            View.VISIBLE
//
//        /*
//        |--------------------------------------------------------------------------
//        | Media
//        |--------------------------------------------------------------------------
//        */
//
//        mediaView?.apply {
//
//            render(
//                ad.mediaUrl(),
//                ad.isVideo()
//            )
//
//            play()
//        }
//
//        /*
//        |--------------------------------------------------------------------------
//        | Click
//        |--------------------------------------------------------------------------
//        */
//
//        val clickListener =
//            View.OnClickListener {
//
//                ITWingSDK.trackCustomAdClick(
//                    ad.id,
//                    mapOf(
//                        "placement" to "native",
//                        "native_type" to type.name.lowercase()
//                    )
//                )
//
//                ad.targetUrl
//                    ?.takeIf {
//                        it.isNotBlank()
//                    }
//                    ?.let { url ->
//
//                        runCatching {
//
//                            /*
//                            |--------------------------------------------------------------------------
//                            | Pause ONLY clicked media
//                            |--------------------------------------------------------------------------
//                            */
//
//                            mediaView?.pauseForExternalNavigation()
//
//                            /*
//                            |--------------------------------------------------------------------------
//                            | Open Browser
//                            |--------------------------------------------------------------------------
//                            */
//
//                            activity.startActivity(
//                                Intent(
//                                    Intent.ACTION_VIEW,
//                                    url.toUri()
//                                )
//                            )
//
//                            /*
//                            |--------------------------------------------------------------------------
//                            | Resume ONLY clicked media
//                            |--------------------------------------------------------------------------
//                            */
//
//                            activity.application
//                                .registerActivityLifecycleCallbacks(
//                                    object : Application.ActivityLifecycleCallbacks {
//
//                                        override fun onActivityResumed(
//                                            resumedActivity: Activity
//                                        ) {
//
//                                            if (
//                                                resumedActivity ==
//                                                activity
//                                            ) {
//
//                                                mediaView?.resumeFromExternalNavigation()
//
//                                                activity.application.unregisterActivityLifecycleCallbacks(this)
//                                            }
//                                        }
//
//                                        override fun onActivityCreated(
//                                            activity: Activity,
//                                            savedInstanceState: Bundle?
//                                        ) {
//                                        }
//
//                                        override fun onActivityStarted(
//                                            activity: Activity
//                                        ) {
//                                        }
//
//                                        override fun onActivityPaused(
//                                            activity: Activity
//                                        ) {
//                                        }
//
//                                        override fun onActivityStopped(
//                                            activity: Activity
//                                        ) {
//                                        }
//
//                                        override fun onActivitySaveInstanceState(
//                                            activity: Activity,
//                                            outState: Bundle
//                                        ) {
//                                        }
//
//                                        override fun onActivityDestroyed(
//                                            activity: Activity
//                                        ) {
//                                        }
//                                    }
//                                )
//                        }
//                    }
//            }
//
//        root.setOnClickListener(
//            clickListener
//        )
//
//        ctaView?.setOnClickListener(
//            clickListener
//        )
//
//        /*
//        |--------------------------------------------------------------------------
//        | Render
//        |--------------------------------------------------------------------------
//        */
//
//        container.removeAllViews()
//
//        container.addView(root)
//
//        container.visibility =
//            View.VISIBLE
//
//        /*
//        |--------------------------------------------------------------------------
//        | Impression
//        |--------------------------------------------------------------------------
//        */
//
//        ITWingSDK.trackCustomAdImpression(
//            ad.id,
//            mapOf(
//                "placement" to "native",
//                "native_type" to type.name.lowercase()
//            )
//        )
//    }
//
//    private fun loadImage(url: String?, imageView: ImageView?, activity: Activity) {
//        if (url.isNullOrBlank()) {
//            return
//        }
//        activity.runOnUiThread {
//            runCatching {
//                imageView?.let {
//                    Glide.with(activity)
//                        .load(url)
//                        .fitCenter()
//                        .into(it)
//                }
//
//                imageView?.visibility = View.VISIBLE
//            }
//        }
//    }
//
//    /*
//    |--------------------------------------------------------------------------
//    | Helpers
//    |--------------------------------------------------------------------------
//    */
//
//    private fun resolveNativeType(
//        placement: AdPlacementConfig,
//        override: NativeType?,
//    ): NativeType {
//        if (override != null) {
//            return override
//        }
//
//        return when ((
//                placement.metadata["native_type"]
//                    ?: placement.metadata["native_template"]
//                )
//            ?.toString()
//            ?.lowercase()
//        ) {
//
//            "small" ->
//                NativeType.SMALL
//
//            else ->
//                NativeType.LARGE
//        }
//    }
//
//    private fun createDefaultShimmer(
//        activity: Activity,
//        container: ViewGroup,
//        nativeType: NativeType
//    ): View? {
//
//        return runCatching {
//
//            val layoutRes =
//                when (nativeType) {
//
//                    NativeType.LARGE ->
//                        R.layout.large_shimmer
//
//                    NativeType.SMALL ->
//                        R.layout.small_shimmer
//                }
//
//            LayoutInflater.from(activity)
//                .inflate(
//                    layoutRes,
//                    container,
//                    false
//                )
//
//        }.getOrNull()
//    }
//
//    private fun stopShimmer(
//        loadingView: View?
//    ) {
//
//        (loadingView as? ShimmerFrameLayout)
//            ?.stopShimmer()
//
//        loadingView?.visibility =
//            View.GONE
//    }
//
//    private fun selectedCustomAd(
//        config: ITWingConfig,
//        placement: AdPlacementConfig
//    ): CustomAdConfig? {
//
//        val source =
//            placement.metadata["source"]
//                ?.toString()
//                ?.lowercase()
//
//        if (
//            source != "custom" &&
//            source != "custom_ad" &&
//            placement.customAd == null
//        ) {
//
//            return null
//        }
//
//        placement.customAd?.let {
//            return it
//        }
//
//        val requestedId =
//            placement.metadata["custom_ad_id"]
//                ?.toString()
//                ?.takeIf {
//                    it.isNotBlank()
//                }
//
//        return config.ads.customAds
//            .filter {
//                it.format == "native" ||
//                        it.format == "image" ||
//                        it.format == "html"
//            }
//            .filter {
//
//                requestedId == null ||
//                        it.id == requestedId
//            }
//            .minByOrNull {
//                it.priority
//            }
//    }
//
//    private fun preloadCustomAd(
//        activity: Activity,
//        container: ViewGroup,
//        ad: CustomAdConfig,
//        type: NativeType,
//        loadingView: View?
//    ) {
//
//        val media = ad.mediaUrl()
//
//        /*
//        |--------------------------------------------------------------------------
//        | No Media
//        |--------------------------------------------------------------------------
//        */
//
//        if (media.isNullOrBlank()) {
//            activity.runOnUiThread {
//                stopShimmer(loadingView)
//                renderCustomNative(activity = activity, container = container, ad = ad, type = type)
//            }
//
//            return
//        }
//
//        /*
//        |--------------------------------------------------------------------------
//        | Keep shimmer visible while preloading
//        |--------------------------------------------------------------------------
//        */
//
//        Glide.with(activity.applicationContext).load(media).preload()
//
//        /*
//        |--------------------------------------------------------------------------
//        | Simulate real network loading behavior
//        |--------------------------------------------------------------------------
//        */
//
//        container.postDelayed({
//
//            activity.runOnUiThread {
//
//                renderCustomNative(
//                    activity = activity,
//                    container = container,
//                    ad = ad,
//                    type = type
//                )
//
//                stopShimmer(
//                    loadingView
//                )
//
//                container.alpha = 0f
//
//                container.animate()
//                    .alpha(1f)
//                    .setDuration(250)
//                    .start()
//            }
//
//        }, 650)
//    }
//
//    private fun releaseMediaViews(
//        parent: ViewGroup
//    ) {
//
//        for (i in 0 until parent.childCount) {
//
//            val child =
//                parent.getChildAt(i)
//
//            when (child) {
//
//                is SDKMediaView -> {
//
//                    child.release()
//                }
//
//                is ViewGroup -> {
//
//                    releaseMediaViews(child)
//                }
//            }
//        }
//    }
//
//    private fun CustomAdConfig.mediaUrl(): String? =
//        mediaUrl?.takeIf {
//            it.isNotBlank()
//        }
//            ?: videoUrl?.takeIf {
//                it.isNotBlank()
//            }
//            ?: imageUrl?.takeIf {
//                it.isNotBlank()
//            }
//
//    private fun CustomAdConfig.isVideo(): Boolean = mediaType.equals(
//        "video",
//        ignoreCase = true
//    ) || (!videoUrl.isNullOrBlank() && mediaUrl == videoUrl)
//
//    private fun CustomAdConfig.primaryColor(): String? = metadata["ad_primary_color"] as? String
//        ?: (metadata["brand"] as? Map<*, *>)?.get("primary_color") as? String
//
//    private fun CustomAdConfig.brandName(): String? =
//        (
//                metadata["brand"]
//                        as? Map<*, *>
//                )?.get("name")
//                as? String
//            ?: campaignGroup
//
//    private fun CustomAdConfig.brandRating(): Float {
//        val value = metadata["brand_rating"] ?: (metadata["brand"] as? Map<*, *>)?.get("rating")
//        return when (value) {
//            is Number ->
//                value.toFloat()
//
//            is String ->
//                value.toFloatOrNull()
//
//            else ->
//                null
//        }?.coerceIn(0f, 5f)
//            ?: 4.5f
//    }
//
//    private fun CustomAdConfig.brandLogoUrl(): String? {
//        val brand = metadata["brand"] as? Map<*, *> ?: return null
//        return brand["logo_url"] as? String
//    }
//
//    private fun CustomAdConfig.adIcon(): String =
//        (metadata["ad_icon"] as? String)?.takeIf { it.isNotBlank() } ?: "AD"
//
//
//    private fun parseColorSafe(value: String?, fallback: Int): Int =
//        runCatching {
//            if (value.isNullOrBlank()) {
//                fallback
//            } else {
//                value.toColorInt()
//            }
//
//        }.getOrDefault(fallback)
//}