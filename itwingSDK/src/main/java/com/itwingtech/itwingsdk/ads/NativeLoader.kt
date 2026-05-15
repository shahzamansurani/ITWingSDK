package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoController
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaContent
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.ITWingConfig
import okhttp3.OkHttpClient
import okhttp3.Request


class NativeLoader(private val configProvider: () -> ITWingConfig) {
    private var currentNativeAd: NativeAd? = null
    private val imageClient = OkHttpClient.Builder().build()
    fun load(
        activity: Activity,
        container: ViewGroup,
        placementName: String,
        nativeTypeOverride: NativeType? = null,
        shimmerView: View? = null,
    ) {

        val config = configProvider()

        if (!config.ads.globalEnabled) {
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

        val resolvedNativeType =
            resolveNativeType(placement, nativeTypeOverride)

        /*
        |--------------------------------------------------------------------------
        | Start Shimmer ONCE
        |--------------------------------------------------------------------------
        */

        val loadingView = shimmerView
            ?: createDefaultShimmer(
                activity,
                container,
                resolvedNativeType
            )

        loadingView?.let {

            container.removeAllViews()

            container.addView(it)

            it.visibility = View.VISIBLE

            (it as? ShimmerFrameLayout)
                ?.startShimmer()
        }

        /*
        |--------------------------------------------------------------------------
        | Custom Native Ads
        |--------------------------------------------------------------------------
        */

        val customAd = selectedCustomAd(config, placement)

        if (customAd != null) {

            preloadCustomAd(
                activity = activity,
                container = container,
                ad = customAd,
                type = resolvedNativeType,
                loadingView = loadingView
            )

            return
        }

        /*
        |--------------------------------------------------------------------------
        | AdMob Native Ads
        |--------------------------------------------------------------------------
        */

        val unit = placement.units.firstOrNull {
            it.network == "admob"
        } ?: run {

            (loadingView as? ShimmerFrameLayout)
                ?.stopShimmer()

            loadingView?.visibility = View.GONE

            destroy(container)

            return
        }

        if (activity.isFinishing || activity.isDestroyed) {

            (loadingView as? ShimmerFrameLayout)
                ?.stopShimmer()

            loadingView?.visibility = View.GONE

            return
        }

        try {

            val request = NativeAdRequest.Builder(
                adUnitId = unit.adUnitId,
                nativeAdTypes = listOf(
                    NativeAd.NativeAdType.NATIVE
                )
            ).build()

            NativeAdLoader.load(
                request,
                object : NativeAdLoaderCallback {

                    override fun onNativeAdLoaded(
                        nativeAd: NativeAd
                    ) {

                        activity.runOnUiThread {

                            currentNativeAd?.destroy()

                            currentNativeAd = nativeAd

                            @LayoutRes
                            val layoutRes = when (
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

                            /*
                            |--------------------------------------------------------------------------
                            | Stop Shimmer
                            |--------------------------------------------------------------------------
                            */

                            (loadingView as? ShimmerFrameLayout)
                                ?.stopShimmer()

                            loadingView?.visibility =
                                View.GONE

                            /*
                            |--------------------------------------------------------------------------
                            | Render Ad
                            |--------------------------------------------------------------------------
                            */

                            container.removeAllViews()

                            container.addView(adView)

                            container.visibility =
                                View.VISIBLE

                            /*
                            |--------------------------------------------------------------------------
                            | Populate
                            |--------------------------------------------------------------------------
                            */

                            populateNativeAdView(
                                nativeAd,
                                adView
                            )

                            /*
                            |--------------------------------------------------------------------------
                            | Smooth Fade
                            |--------------------------------------------------------------------------
                            */

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

                            (loadingView as? ShimmerFrameLayout)
                                ?.stopShimmer()

                            loadingView?.visibility =
                                View.GONE

                            container.visibility =
                                View.GONE
                        }
                    }
                }
            )

        } catch (_: Exception) {

            (loadingView as? ShimmerFrameLayout)
                ?.stopShimmer()

            loadingView?.visibility = View.GONE

            container.visibility = View.GONE
        }
    }
//    fun load(activity: Activity, container: ViewGroup, placementName: String, nativeTypeOverride: NativeType? = null, shimmerView: View? = null, ) {
//        val config = configProvider()
//        if (!config.ads.globalEnabled) {
//            destroy(container)
//            return
//        }
//
//        val placement = config.ads.placements.firstOrNull {
//            it.name == placementName && it.enabled && it.format == "native" } ?: run {
//            destroy(container)
//            return
//        }
//
//        val resolvedNativeType = resolveNativeType(placement, nativeTypeOverride)
////        val customAd = selectedCustomAd(config, placement)
////        if (customAd != null) {
////            renderCustomNative(activity, container, customAd, resolvedNativeType)
////            return
////        }
//        val loadingView = shimmerView ?: createDefaultShimmer(activity, container, resolvedNativeType)
//        loadingView?.let { container.removeAllViews()
//            container.addView(it)
//            it.visibility = View.VISIBLE
//            (it as? ShimmerFrameLayout)?.startShimmer()
//        }
//
//        val customAd = selectedCustomAd(config, placement)
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
//        val unit = placement.units.firstOrNull { it.network == "admob" } ?: run {
//            destroy(container)
//            return
//        }
//
//        if (activity.isFinishing) {
//            return
//        }
//
//        try {
//            val loadingView = shimmerView ?: createDefaultShimmer(activity, container, resolvedNativeType)
//            loadingView?.let {
//            container.removeAllViews()
//            container.addView(it)
//            it.visibility = View.VISIBLE
//            (it as? ShimmerFrameLayout)?.startShimmer()
//        }
//
//            val request = NativeAdRequest.Builder(adUnitId = unit.adUnitId, nativeAdTypes = listOf(NativeAd.NativeAdType.NATIVE)).build()
//            NativeAdLoader.load(request, object : NativeAdLoaderCallback {
//                override fun onNativeAdLoaded(nativeAd: NativeAd) {
//                    activity.runOnUiThread {
//                        currentNativeAd?.destroy()
//                        currentNativeAd = nativeAd
//                        @LayoutRes
//                        val layoutRes = when (resolvedNativeType) {
//                            NativeType.LARGE -> R.layout.native_admob_large
//                            NativeType.SMALL -> R.layout.native_admob_small
//                        }
//
//                        val adView = LayoutInflater.from(activity)
//                            .inflate(layoutRes, container, false) as NativeAdView
//                        (loadingView as? ShimmerFrameLayout)?.stopShimmer()
//                        loadingView?.visibility = View.GONE
//                        container.removeAllViews()
//                        container.addView(adView)
//                        container.requestLayout()
//                        container.invalidate()
//                        container.visibility = View.VISIBLE
//                        populateNativeAdView(nativeAd, adView)
//                    }
//                }
//
//                override fun onAdFailedToLoad(adError: LoadAdError) {
//                    activity.runOnUiThread {
//                        (loadingView as? ShimmerFrameLayout)?.stopShimmer()
//                        loadingView?.visibility = View.GONE
//                        container.visibility = View.GONE
//                    }
//                }
//            }
//            )
//
//        } catch (_: Exception) {
//            container.visibility = View.GONE
//        }
//    }

    fun destroy(container: ViewGroup? = null) {
        try {
            currentNativeAd?.destroy()
        } catch (_: Exception) {
        }
        currentNativeAd = null
        container?.removeAllViews()
    }

    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        adView.headlineView = adView.findViewById<View?>(R.id.ad_headline)
        adView.bodyView = adView.findViewById<View?>(R.id.ad_body)
        adView.callToActionView = adView.findViewById<View?>(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById<View?>(R.id.ad_app_icon)
        adView.priceView = adView.findViewById<View?>(R.id.ad_price)
        adView.starRatingView = adView.findViewById<View?>(R.id.ad_stars)
        adView.storeView = adView.findViewById<View?>(R.id.ad_store)
        adView.advertiserView = adView.findViewById<View?>(R.id.ad_advertiser)
        (adView.headlineView as? TextView)?.text = nativeAd.headline
        nativeAd.body?.let {
            (adView.bodyView as? TextView)?.text = it
            adView.bodyView?.visibility = View.VISIBLE
        } ?: run {
            adView.bodyView?.visibility = View.INVISIBLE
        }

        nativeAd.callToAction?.let { (adView.callToActionView as? Button)?.text = it
            adView.callToActionView?.visibility = View.VISIBLE
        } ?: run {
            adView.callToActionView?.visibility = View.INVISIBLE
        }

        nativeAd.icon?.drawable?.let {
            (adView.iconView as? ImageView)?.setImageDrawable(it)
            adView.iconView?.visibility = View.VISIBLE
        } ?: run { adView.iconView?.visibility = View.GONE }
        nativeAd.price?.let {
            (adView.priceView as? TextView)?.text = it
            adView.priceView?.visibility = View.VISIBLE
        } ?: run {
            adView.priceView?.visibility = View.INVISIBLE
        }
        nativeAd.store?.let {
            (adView.storeView as? TextView)?.text = it
            adView.storeView?.visibility =
                View.VISIBLE

        } ?: run {

            adView.storeView?.visibility =
                View.INVISIBLE
        }

        nativeAd.starRating?.let {

            (adView.starRatingView as? RatingBar)
                ?.rating = it.toFloat()

            adView.starRatingView?.visibility =
                View.VISIBLE

        } ?: run {

            adView.starRatingView?.visibility =
                View.INVISIBLE
        }

        nativeAd.advertiser?.let {

            (adView.advertiserView as? TextView)?.text = it

            adView.advertiserView?.visibility =
                View.VISIBLE

        } ?: run {

            adView.advertiserView?.visibility =
                View.INVISIBLE
        }

        adView.registerNativeAd(nativeAd, adView.findViewById(R.id.ad_media))
        val mediaContent: MediaContent = nativeAd.mediaContent
        val videoController: VideoController? = mediaContent.videoController
        if (videoController != null && mediaContent.hasVideoContent) {
            videoController.videoLifecycleCallbacks =
                object : VideoController.VideoLifecycleCallbacks {

                }
        }
    }

    private fun resolveNativeType(placement: AdPlacementConfig, override: NativeType?, ): NativeType {
        if (override != null) { return override }
        return when ((placement.metadata["native_type"] ?: placement.metadata["native_template"])?.toString()?.lowercase()) {
            "large" -> NativeType.LARGE
            "small" -> NativeType.SMALL
            else -> NativeType.LARGE
        }
    }

    private fun createDefaultShimmer(activity: Activity, container: ViewGroup, nativeType: NativeType): View? {
        return runCatching {
            val layoutRes = when (nativeType) {
                NativeType.LARGE -> R.layout.large_shimmer
                NativeType.SMALL -> R.layout.small_shimmer
            }
            LayoutInflater.from(activity).inflate(layoutRes, container, false)
        }.getOrNull()
    }

    private fun selectedCustomAd(config: ITWingConfig, placement: AdPlacementConfig): CustomAdConfig? {
        val source = placement.metadata["source"]?.toString()?.lowercase()
        if (source != "custom" && source != "custom_ad" && placement.customAd == null) {
            return null
        }
        placement.customAd?.let { return it }
        val requestedId = placement.metadata["custom_ad_id"]?.toString()?.takeIf { it.isNotBlank() }
        return config.ads.customAds
            .filter { it.format == "native" || it.format == "image" || it.format == "html" }
            .filter { requestedId == null || it.id == requestedId }
            .minByOrNull { it.priority }
    }

    private fun renderCustomNative(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        type: NativeType
    ) {

        destroy(container)

        @LayoutRes
        val layoutRes = when (type) {
            NativeType.LARGE -> R.layout.custom_native_large
            NativeType.SMALL -> R.layout.custom_native_small
        }

        val root = LayoutInflater.from(activity)
            .inflate(layoutRes, container, false)

        /*
        |--------------------------------------------------------------------------
        | Find Views Safely
        |--------------------------------------------------------------------------
        */

        val headlineView =
            root.findViewById<TextView?>(R.id.ad_headline)

        val bodyView =
            root.findViewById<TextView?>(R.id.ad_body)

        val ctaView =
            root.findViewById<Button?>(R.id.ad_call_to_action)

        val iconView =
            root.findViewById<ImageView?>(R.id.ad_app_icon)

        val advertiserView =
            root.findViewById<TextView?>(R.id.ad_advertiser)

        val mediaImage =
            root.findViewById<ImageView?>(R.id.ad_media)

        /*
        |--------------------------------------------------------------------------
        | Populate Text
        |--------------------------------------------------------------------------
        */

        headlineView?.text =
            ad.headline?.takeIf { it.isNotBlank() }
                ?: ad.name.ifBlank { "Sponsored" }

        bodyView?.text =
            ad.body?.takeIf { it.isNotBlank() }
                ?: "Promoted content"

        ctaView?.text =
            ad.cta?.takeIf { it.isNotBlank() }
                ?: "Install"

        advertiserView?.text = "Sponsored"

        /*
        |--------------------------------------------------------------------------
        | Visibility
        |--------------------------------------------------------------------------
        */

        headlineView?.visibility = View.VISIBLE
        bodyView?.visibility = View.VISIBLE
        ctaView?.visibility = View.VISIBLE
        advertiserView?.visibility = View.VISIBLE

        /*
        |--------------------------------------------------------------------------
        | Load Images
        |--------------------------------------------------------------------------
        */

        loadImage(ad.imageUrl, mediaImage, activity)

        loadImage(ad.imageUrl, iconView, activity)

        /*
        |--------------------------------------------------------------------------
        | Click Handling
        |--------------------------------------------------------------------------
        */

        val clickListener = View.OnClickListener {

            ITWingSDK.trackCustomAdClick(
                ad.id,
                mapOf(
                    "placement" to "native",
                    "native_type" to type.name.lowercase()
                )
            )

            ad.targetUrl
                ?.takeIf { it.isNotBlank() }
                ?.let {

                    runCatching {

                        activity.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(it)
                            )
                        )

                    }
                }
        }

        root.setOnClickListener(clickListener)

        ctaView?.setOnClickListener(clickListener)

        /*
        |--------------------------------------------------------------------------
        | Render
        |--------------------------------------------------------------------------
        */

        container.removeAllViews()

        container.addView(root)

        container.visibility = View.VISIBLE

        /*
        |--------------------------------------------------------------------------
        | Track Impression
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

    private fun loadImage(url: String?, imageView: ImageView, activity: Activity) {
        if (url.isNullOrBlank()) return
        Thread {
            runCatching {
                val request = Request.Builder().url(url).build()
                imageClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        null
                    }
                }
            }.getOrNull()?.let { bitmap ->
                activity.runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                }
            }
        }.start()
    }


    private fun preloadCustomAd(activity: Activity, container: ViewGroup, ad: CustomAdConfig, type: NativeType, loadingView: View?) {
        Thread {
            var imageLoaded = false

            runCatching {
                ad.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
                    val request = Request.Builder()
                        .url(imageUrl)
                        .build()

                    imageClient.newCall(request)
                        .execute()
                        .use { response ->

                            if (response.isSuccessful) {

                                response.body.bytes()

                                imageLoaded = true
                            }
                        }
                }

            }

            activity.runOnUiThread {

                (loadingView as? ShimmerFrameLayout)
                    ?.stopShimmer()

                loadingView?.visibility = View.GONE

                renderCustomNative(activity = activity, container = container, ad = ad, type = type)

                /*
                |--------------------------------------------------------------------------
                | Fallback visibility
                |--------------------------------------------------------------------------
                */

                if (!imageLoaded) {

                    container.alpha = 0f

                    container.animate()
                        .alpha(1f)
                        .setDuration(250)
                        .start()
                }
            }

        }.start()
    }
}
