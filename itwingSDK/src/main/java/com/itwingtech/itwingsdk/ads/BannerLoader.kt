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
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.SDKMediaView
import kotlin.math.roundToInt

class BannerLoader(private val configProvider: () -> ITWingConfig) {
    private var currentBannerAd: BannerAd? = null
    private var currentAdView: AdView? = null

    @MainThread
    fun load(activity: Activity, container: ViewGroup, placementName: String, bannerType: BannerType? = null, shimmerView: View? = null) {
        val config = configProvider()
        if (!config.ads.globalEnabled) {
            destroy(container)
            return
        }
        val placement = config.ads.placements.firstOrNull {
                it.name == placementName && it.enabled && it.format == "banner"
            } ?: run {
                destroy(container)
                return
            }

        val loadingView = shimmerView ?: createDefaultShimmer(activity, container)

        loadingView?.let {
            container.removeAllViews()
            container.addView(it)
            it.visibility = View.VISIBLE

            (it as? ShimmerFrameLayout)
                ?.startShimmer()
        }

        /*
        |--------------------------------------------------------------------------
        | Custom Banner
        |--------------------------------------------------------------------------
        */

        val customAd =
            selectedCustomAd(
                config,
                placement
            )

        if (customAd != null) {

            preloadCustomBanner(
                activity = activity,
                container = container,
                ad = customAd,
                loadingView = loadingView
            )

            return
        }

        /*
        |--------------------------------------------------------------------------
        | AdMob
        |--------------------------------------------------------------------------
        */

        val unit = placement.units.firstOrNull { it.network == "admob"
            } ?: run { stopShimmer(loadingView)
                destroy(container)
                return
            }

        val resolvedBannerType = resolveBannerType(placement, bannerType)

        try {
            try {
                currentBannerAd?.destroy()
                currentAdView?.destroy()
            } catch (_: Exception) {
            }

            currentBannerAd = null
            currentAdView = null

            /*
            |--------------------------------------------------------------------------
            | Keep shimmer visible
            |--------------------------------------------------------------------------
            */

            val adView = AdView(activity)

            currentAdView =
                adView

            val extras = Bundle()

            when (resolvedBannerType) {

                BannerType.COLLAPSIBLE_TOP -> {

                    extras.putString(
                        "collapsible",
                        "top"
                    )
                }

                BannerType.COLLAPSIBLE_BOTTOM -> {

                    extras.putString(
                        "collapsible",
                        "bottom"
                    )
                }

                BannerType.ADAPTIVE -> {
                }
            }

            val request =
                BannerAdRequest.Builder(
                    adUnitId = unit.adUnitId,
                    adSize = getAdaptiveAdSize(
                        activity,
                        container
                    )
                )
                    .setGoogleExtrasBundle(extras)
                    .build()

            adView.loadAd(
                request,
                object : AdLoadCallback<BannerAd> {

                    override fun onAdLoaded(
                        ad: BannerAd
                    ) {

                        activity.runOnUiThread {

                            currentBannerAd = ad

                            adView.registerBannerAd(
                                ad,
                                activity
                            )

                            /*
                            |--------------------------------------------------------------------------
                            | Remove shimmer ONLY after loaded
                            |--------------------------------------------------------------------------
                            */

                            container.removeAllViews()

                            stopShimmer(
                                loadingView
                            )

                            container.addView(adView)

                            container.visibility =
                                View.VISIBLE

                            adView.alpha = 0f

                            adView.animate()
                                .alpha(1f)
                                .setDuration(250)
                                .start()

                            ad.bannerAdRefreshCallback =
                                object :
                                    BannerAdRefreshCallback {

                                    override fun onAdRefreshed() {
                                    }

                                    override fun onAdFailedToRefresh(
                                        adError: LoadAdError
                                    ) {
                                    }
                                }
                        }
                    }

                    override fun onAdFailedToLoad(
                        adError: LoadAdError
                    ) {

                        activity.runOnUiThread {

                            stopShimmer(
                                loadingView
                            )

                            container.visibility =
                                View.GONE
                        }
                    }
                }
            )

        } catch (_: Exception) {

            stopShimmer(
                loadingView
            )

            container.visibility =
                View.GONE
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Custom Banner
    |--------------------------------------------------------------------------
    */
    private fun renderCustomBanner(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig
    ) {

        destroy(container)

        val root =
            LayoutInflater.from(activity)
                .inflate(
                    R.layout.custom_banner,
                    container,
                    false
                )

        /*
        |--------------------------------------------------------------------------
        | Views
        |--------------------------------------------------------------------------
        */

        val headlineView =
            root.findViewById<TextView>(
                R.id.ad_headline
            )

        val bodyView =
            root.findViewById<TextView>(
                R.id.ad_body
            )

        val advertiserView =
            root.findViewById<TextView>(
                R.id.ad_advertiser
            )

        val ctaView =
            root.findViewById<Button>(
                R.id.ad_call_to_action
            )

        val mediaView =
            root.findViewById<SDKMediaView>(
                R.id.ad_media
            )

        val iconView =
            root.findViewById<ImageView>(
                R.id.ad_app_icon
            )

        val ratingView =
            root.findViewById<RatingBar>(
                R.id.ad_stars
            )

        val adTag =
            root.findViewById<TextView>(
                R.id.ad_ic
            )

        /*
        |--------------------------------------------------------------------------
        | Text
        |--------------------------------------------------------------------------
        */

        headlineView.text =
            ad.headline?.takeIf {
                it.isNotBlank()
            }
                ?: ad.name.ifBlank {
                    "Sponsored"
                }

        bodyView.text =
            ad.body?.takeIf {
                it.isNotBlank()
            }
                ?: "Promoted content"

        advertiserView.text =
            ad.brandName()
                ?: "Sponsored"

        ctaView.text =
            ad.cta?.takeIf {
                it.isNotBlank()
            }
                ?: "Install"

        ratingView.rating =
            ad.brandRating()

        adTag.text =
            ad.adIcon()

        /*
        |--------------------------------------------------------------------------
        | CTA Color
        |--------------------------------------------------------------------------
        */

        val ctaDrawable = ctaView.background?.mutate() as? GradientDrawable

        ctaDrawable?.setColor(parseColorSafe(ad.primaryColor(), Color.rgb(37, 99, 235))
        )

        /*
        |--------------------------------------------------------------------------
        | Ad Badge Color
        |--------------------------------------------------------------------------
        */

        val adTagDrawable =
            adTag.background
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

        /*
        |--------------------------------------------------------------------------
        | Media
        |--------------------------------------------------------------------------
        */

        mediaView.apply {
            render(
                ad.mediaUrl(),
                ad.isVideo()
            )

            play()
        }

        /*
        |--------------------------------------------------------------------------
        | App Icon
        |--------------------------------------------------------------------------
        */

        loadImage(
            ad.brandLogoUrl()
                ?: ad.imageUrl
                ?: ad.mediaUrl(),
            iconView,
            activity
        )

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
                        "placement" to "banner"
                    )
                )

                ad.targetUrl
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

                            mediaView.pauseForExternalNavigation()

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

                                                mediaView.resumeFromExternalNavigation()

                                                activity.application
                                                    .unregisterActivityLifecycleCallbacks(
                                                        this
                                                    )
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

        ctaView.setOnClickListener(
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

        root.alpha = 0f

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
                "placement" to "banner"
            )
        )
    }

    private fun preloadCustomBanner(
        activity: Activity,
        container: ViewGroup,
        ad: CustomAdConfig,
        loadingView: View?
    ) {

        val media =
            ad.mediaUrl()

        if (media.isNullOrBlank()) {

            activity.runOnUiThread {

                stopShimmer(
                    loadingView
                )

                renderCustomBanner(
                    activity,
                    container,
                    ad
                )
            }

            return
        }

        Glide.with(activity.applicationContext)
            .load(media)
            .preload()

        container.postDelayed({

            activity.runOnUiThread {

                renderCustomBanner(
                    activity,
                    container,
                    ad
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

    /*
    |--------------------------------------------------------------------------
    | Helpers
    |--------------------------------------------------------------------------
    */

    private fun stopShimmer(
        loadingView: View?
    ) {

        (loadingView as? ShimmerFrameLayout)
            ?.stopShimmer()

        loadingView?.apply {

            visibility = View.GONE

            (parent as? ViewGroup)
                ?.removeView(this)
        }
    }

    private fun loadImage(
        url: String?,
        imageView: ImageView,
        activity: Activity
    ) {

        if (url.isNullOrBlank()) {

            imageView.visibility =
                View.GONE

            return
        }

        activity.runOnUiThread {

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

                it.format == "banner" ||
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

    fun destroy(
        container: ViewGroup? = null
    ) {

        try {

            currentBannerAd?.destroy()

            currentAdView?.destroy()

        } catch (_: Exception) {
        }

        currentBannerAd = null

        currentAdView = null

        container?.let {

            releaseMediaViews(it)

            it.removeAllViews()
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

    private fun getAdaptiveAdSize(
        activity: Activity,
        container: ViewGroup
    ): AdSize {

        val displayMetrics: DisplayMetrics =
            activity.resources.displayMetrics

        val density =
            displayMetrics.density

        var adWidthPixels =
            container.width.toFloat()

        if (adWidthPixels <= 0f) {

            adWidthPixels =
                displayMetrics.widthPixels.toFloat()
        }

        val adWidth =
            (adWidthPixels / density)
                .roundToInt()

        return AdSize
            .getLandscapeAnchoredAdaptiveBannerAdSize(
                activity,
                adWidth
            )
    }

    private fun resolveBannerType(
        placement: AdPlacementConfig,
        override: BannerType?,
    ): BannerType {

        if (override != null) {
            return override
        }

        val value =
            (
                    placement.metadata["banner_type"]
                        ?: placement.metadata["collapsible_position"]
                    )
                ?.toString()
                ?.lowercase()

        return when (value) {

            "top" ->
                BannerType.COLLAPSIBLE_TOP

            "bottom" ->
                BannerType.COLLAPSIBLE_BOTTOM

            "collapsible_top" ->
                BannerType.COLLAPSIBLE_TOP

            "collapsible_bottom" ->
                BannerType.COLLAPSIBLE_BOTTOM

            else ->
                BannerType.ADAPTIVE
        }
    }

    private fun createDefaultShimmer(
        activity: Activity,
        container: ViewGroup
    ): View? {

        return runCatching {

            LayoutInflater.from(activity)
                .inflate(
                    R.layout.banner_shimmer,
                    container,
                    false
                )

        }.getOrNull()
    }

    /*
    |--------------------------------------------------------------------------
    | CustomAdConfig Helpers
    |--------------------------------------------------------------------------
    */

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

    private fun CustomAdConfig.isVideo(): Boolean =
        mediaType.equals(
            "video",
            ignoreCase = true
        ) || (
                !videoUrl.isNullOrBlank() &&
                        mediaUrl == videoUrl
                )

    private fun CustomAdConfig.primaryColor(): String? =
        metadata["ad_primary_color"] as? String ?: (metadata["brand"] as? Map<*, *>)?.get("primary_color") as? String

    private fun CustomAdConfig.brandName(): String? = (metadata["brand"] as? Map<*, *>)?.get("name") as? String ?: campaignGroup

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
        }?.coerceIn(0f, 5f)
            ?: 4.5f
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

        }.getOrDefault(fallback)
}
