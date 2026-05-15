package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
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
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

class BannerLoader(private val configProvider: () -> ITWingConfig, ) {
    private var currentBannerAd: BannerAd? = null
    private var currentAdView: AdView? = null
    private val imageClient = OkHttpClient.Builder().build()
    @MainThread
    fun load(activity: Activity, container: ViewGroup, placementName: String, bannerType: BannerType? = null, shimmerView: View? = null, ) {
        val config = configProvider()
        if (!config.ads.globalEnabled) {
            destroy(container)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "banner" } ?: run {
            destroy(container)
            return
        }

        val customAd = selectedCustomAd(config, placement)
        if (customAd != null) {
            renderCustomBanner(activity, container, customAd)
            return
        }

        val unit = placement.units.firstOrNull {
            it.network == "admob"
        } ?: run {
            destroy(container)
            return
        }
        val resolvedBannerType = resolveBannerType(placement, bannerType)

        try {
            destroy(container)
            val loadingView = shimmerView ?: createDefaultShimmer(activity, container)
            loadingView?.let {
                container.removeAllViews()
                container.addView(it)
                it.visibility = View.VISIBLE
                (it as? ShimmerFrameLayout)?.startShimmer()
            }

            val adView = AdView(activity)
            currentAdView = adView
            val extras = Bundle()
            when (resolvedBannerType) {
                BannerType.COLLAPSIBLE_TOP -> {
                    extras.putString(
                        "collapsible",
                        "top",
                    )
                }

                BannerType.COLLAPSIBLE_BOTTOM -> {
                    extras.putString(
                        "collapsible",
                        "bottom",
                    )
                }

                BannerType.ADAPTIVE -> {
                }
            }

            val request = BannerAdRequest.Builder(adUnitId = unit.adUnitId, adSize = getAdaptiveAdSize(activity, container)).setGoogleExtrasBundle(extras).build()
            adView.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd, ) {
                    activity.runOnUiThread {
                        currentBannerAd = ad
                        adView.registerBannerAd(ad, activity,)
                        container.removeAllViews()
                        container.addView(adView)
                        container.requestLayout()
                        container.invalidate()
                        (loadingView as? ShimmerFrameLayout)?.stopShimmer()
                        loadingView?.visibility = View.GONE
                        container.visibility = View.VISIBLE
                        ad.bannerAdRefreshCallback =
                            object : BannerAdRefreshCallback {
                                override fun onAdRefreshed() {}
                                override fun onAdFailedToRefresh(
                                    adError: LoadAdError,
                                ) {
                                }
                            }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError, ) {
                    activity.runOnUiThread {
                        (loadingView as? ShimmerFrameLayout)?.stopShimmer()
                        loadingView?.visibility = View.GONE
                        container.visibility = View.GONE
                    }
                }
            }
            )

        } catch (_: Exception) {
            container.visibility = View.GONE
        }
    }

    private fun selectedCustomAd(config: ITWingConfig, placement: AdPlacementConfig): CustomAdConfig? {
        val source = placement.metadata["source"]?.toString()?.lowercase()
        if (source != "custom" && source != "custom_ad" && placement.customAd == null) {
            return null
        }
        placement.customAd?.let { return it }
        val requestedId = placement.metadata["custom_ad_id"]?.toString()?.takeIf { it.isNotBlank() }
        return config.ads.customAds
            .filter { it.format == "banner" || it.format == "image" || it.format == "html" }
            .filter { requestedId == null || it.id == requestedId }
            .minByOrNull { it.priority }
    }

    private fun renderCustomBanner(activity: Activity, container: ViewGroup, ad: CustomAdConfig) {
        destroy(container)
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), android.graphics.Color.rgb(226, 232, 240))
            }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams((68 * density).toInt(), (52 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
            visibility = View.GONE
        }
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        copy.addView(TextView(activity).apply {
            text = ad.headline ?: ad.name.ifBlank { "Sponsored" }
            setTextColor(android.graphics.Color.rgb(15, 23, 42))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            maxLines = 1
        })
        copy.addView(TextView(activity).apply {
            text = ad.body ?: "Promoted content"
            setTextColor(android.graphics.Color.rgb(100, 116, 139))
            textSize = 12f
            maxLines = 2
        })
        val cta = Button(activity).apply {
            text = ad.cta ?: "Open"
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt())
        }
        root.addView(image)
        root.addView(copy)
        root.addView(cta)
        val click = View.OnClickListener {
            ITWingSDK.trackCustomAdClick(ad.id, mapOf("placement" to "banner"))
            ad.targetUrl?.takeIf { it.isNotBlank() }?.let {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            }
        }
        root.setOnClickListener(click)
        cta.setOnClickListener(click)
        container.removeAllViews()
        container.addView(root)
        container.visibility = View.VISIBLE
        ITWingSDK.trackCustomAdImpression(ad.id, mapOf("placement" to "banner"))
        loadImage(ad.imageUrl, image, activity)
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

    fun destroy(
        container: ViewGroup? = null,
    ) {

        try {
            currentBannerAd?.destroy()
            currentAdView?.destroy()
        } catch (_: Exception) {
        }
        currentBannerAd = null
        currentAdView = null
        container?.removeAllViews()
    }

    private fun getAdaptiveAdSize(activity: Activity, container: ViewGroup, ): AdSize {
        val displayMetrics: DisplayMetrics = activity.resources.displayMetrics
        val density = displayMetrics.density
        var adWidthPixels = container.width.toFloat()
        if (adWidthPixels <= 0f) { adWidthPixels = displayMetrics.widthPixels.toFloat() }
        val adWidth = (adWidthPixels / density).roundToInt()
        return AdSize.getLandscapeAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    private fun resolveBannerType(
        placement: AdPlacementConfig,
        override: BannerType?,
    ): BannerType {

        if (override != null) {
            return override
        }

        val value = (placement.metadata["banner_type"] ?: placement.metadata["collapsible_position"])
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

    private fun createDefaultShimmer(activity: Activity, container: ViewGroup): View? {
        return runCatching {
            LayoutInflater.from(activity).inflate(R.layout.banner_shimmer, container, false) }.getOrNull()
    }
}
