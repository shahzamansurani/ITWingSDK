package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.itwingtech.itwingsdk.core.ITWingConfig
import java.util.concurrent.ConcurrentHashMap
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.itwingtech.itwingsdk.utils.runOnMain
import com.itwingtech.itwingsdk.utils.safeCallback

class InterstitialManager(private val configProvider: () -> ITWingConfig, private val frequency: FrequencyController) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadedAds = ConcurrentHashMap<String, InterstitialAd>()
    private val preloaderKeys = ConcurrentHashMap<String, String>()
    private val customRenderer = CustomFullscreenAdRenderer()

    fun preloadAll(activity: Activity) {
        val config = configProvider()
        config.ads.placements.filter {
            config.ads.globalEnabled &&
                    it.enabled &&
                    it.format == "interstitial" &&
                    it.metadata["preload_on_start"].isTruthy()
        }
            .forEach {
                preload(activity, it.name)
            }
    }

    fun preload(activity: Activity, placementName: String) {
        load(activity, placementName)
    }

    fun load(activity: Activity, placementName: String) {
        val config = configProvider()
        if (!config.ads.globalEnabled) return
        val placement = config.ads.placements.firstOrNull {
            it.name == placementName &&
                    it.enabled &&
                    it.format == "interstitial"
        } ?: return

        if (customRenderer.canRender(placement)) {
            customRenderer.preload(activity, placement)
            return
        }

        val unit = placement.units.firstOrNull {
            it.network == "admob"
        } ?: return

        if (loadedAds.containsKey(placementName)) {
            return
        }

        val request = AdRequest.Builder(unit.adUnitId).build()
        startPreloader(placementName, unit.adUnitId, request)
    }

    fun show(activity: Activity, placementName: String, onComplete: () -> Unit = {}, ) {
        val config = configProvider()
        if (!config.ads.globalEnabled) {
            safeCallback(onComplete)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName &&
                    it.enabled &&
                    it.format == "interstitial"
        }

        if (placement == null) {
            safeCallback(onComplete)
            return
        }

        if (!frequency.canShow(placement, countTrigger = true)) {
            safeCallback(onComplete)
            return
        }

        if (customRenderer.canRender(placement)) {
            frequency.markShown(placement)
            customRenderer.show(activity, placement, onComplete = {
                preload(activity, placementName)
                safeCallback(onComplete)
            })
            return
        }

        val ad = loadedAds.remove(placementName) ?: pollPreloadedAd(placementName)

        if (ad == null) {
            load(activity, placementName)
            waitForAdAndShow(activity, placementName, onComplete)
            return
        }

        presentAd(activity, placementName, placement, ad, onComplete)
    }

    private fun presentAd(
        activity: Activity,
        placementName: String,
        placement: com.itwingtech.itwingsdk.core.AdPlacementConfig,
        ad: InterstitialAd,
        onComplete: () -> Unit,
    ) {
        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                frequency.markShown(placement)
            }

            override fun onAdDismissedFullScreenContent() {
                loadedAds.remove(placementName)
                preload(activity, placementName)
                   safeCallback(onComplete)
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError,
            ) {
                loadedAds.remove(placementName)
                preload(activity, placementName)
                safeCallback(onComplete)
            }

            override fun onAdClicked() {}

            override fun onAdImpression() {}
        }

        runOnMain {
            runCatching {
                ad.show(activity)
                preload(activity, placementName)
            }.onFailure {
                preload(activity, placementName)
                safeCallback(onComplete)
            }
        }
    }

    fun isLoaded(placementName: String, ): Boolean {
        return loadedAds.containsKey(placementName)
    }

    fun clear(placementName: String, ) {
        loadedAds.remove(placementName)
        preloaderKeys[placementName]?.let { InterstitialAdPreloader.destroy(it) }
        preloaderKeys.remove(placementName)
    }

    fun clearAll() {
        loadedAds.clear()
        preloaderKeys.values.forEach { InterstitialAdPreloader.destroy(it) }
        preloaderKeys.clear()
    }

    private fun startPreloader(placementName: String, adUnitId: String, request: AdRequest) {
        if (preloaderKeys[placementName] == adUnitId) {
            return
        }
        preloaderKeys[placementName]?.let { InterstitialAdPreloader.destroy(it) }
        preloaderKeys[placementName] = adUnitId
        runCatching {
            InterstitialAdPreloader.start(
                adUnitId,
                PreloadConfiguration(request, 2),
            )
        }
    }

    private fun pollPreloadedAd(placementName: String): InterstitialAd? {
        val key = preloaderKeys[placementName] ?: return null
        return runCatching { InterstitialAdPreloader.pollAd(key) }.getOrNull()
    }

    private fun waitForAdAndShow(activity: Activity, placementName: String, onComplete: () -> Unit) {
        val loadingDialog = AdLoadingDialog(activity)
        val app = configProvider().app
        val timeoutMs = (app["loading_ad_timeout_ms"] as? Number)?.toLong() ?: 2500L
        val lottieUrl = app["loading_lottie_url"] as? String
        val startedAt = System.currentTimeMillis()
        loadingDialog.show(lottieUrl)

        fun poll() {
            val ad = loadedAds.remove(placementName) ?: pollPreloadedAd(placementName)
            if (ad != null) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull {
                    it.name == placementName && it.enabled && it.format == "interstitial"
                }
                if (placement == null) {
                    safeCallback(onComplete)
                } else {
                    presentAd(activity, placementName, placement, ad, onComplete)
                }
                return
            }
            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                loadingDialog.dismiss()
                safeCallback(onComplete)
                return
            }
            mainHandler.postDelayed({ poll() }, 150L)
        }

        mainHandler.postDelayed({ poll() }, 150L)
    }

    private fun Any?.isTruthy(): Boolean {
        return when (this) {
            is Boolean -> this
            is String -> equals("true", ignoreCase = true) || this == "1"
            is Number -> toInt() != 0
            else -> false
        }
    }

}
