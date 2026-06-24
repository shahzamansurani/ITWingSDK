package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.utils.runOnMain
import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RewardedInterstitialManager(
    private val configProvider: () -> ITWingConfig,
    private val frequency: FrequencyController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preloaderKeys = ConcurrentHashMap<String, String>()
    private val customRenderer = CustomFullscreenAdRenderer()

    fun preloadAll(activity: Activity) {
        val config = configProvider()
        config.ads.placements
            .filter { config.ads.globalEnabled && it.enabled && it.format == "rewarded_interstitial" }
            .forEach { preload(activity, it.name) }
    }

    fun preload(activity: Activity, placementName: String) {
        load(activity, placementName)
    }

    fun load(activity: Activity, placementName: String) {
        val config = configProvider()
        if (!config.ads.globalEnabled) return

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
        } ?: return

        if (customRenderer.canRender(placement)) {
            customRenderer.preload(activity, placement)
            return
        }

        val unit = placement.units.firstOrNull { it.network == "admob" } ?: return
        val request = AdRequest.Builder(unit.adUnitId).build()
        startPreloader(placementName, unit.adUnitId, request)
    }

    fun show(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit = {},
    ) {
        if (!activity.isUsable()) {
            return
        }
        val config = configProvider()
        if (!config.ads.globalEnabled) {
            AdFailureDialog.show(activity, config.adPrimaryColor(), "Rewarded interstitial ads are disabled for this app.")
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
        }

        if (placement == null) {
            AdFailureDialog.show(
                activity,
                config.adPrimaryColor(),
                "The rewarded interstitial placement '$placementName' is missing or disabled.",
            )
            return
        }

        if (!frequency.canShow(placement, countTrigger = true)) {
            AdEventTracker.log("ad_frequency_capped", placement)
            AdFailureDialog.show(activity, config.adPrimaryColor(), "This ad reached its display interval or frequency limit. Please try again later.")
            return
        }

        if (!customRenderer.canRender(placement) && placement.units.none { it.network.equals("admob", true) && it.adUnitId.isNotBlank() }) {
            AdFailureDialog.show(activity, config.adPrimaryColor(), "No valid AdMob unit is configured for this rewarded interstitial placement.")
            return
        }

        AdEventTracker.log("ad_requested", placement)
        if (customRenderer.canRender(placement)) {
            RewardedIntroDialog.show(activity, placement, config.adPrimaryColor(), onSkip = {
                AdEventTracker.log("ad_opt_out", placement)
            }) {
                val customRewardEarned = AtomicBoolean(false)
                val shown = customRenderer.show(activity, placement, reward = {
                    AdEventTracker.log("ad_reward_earned", placement)
                    customRewardEarned.set(true)
                }, onComplete = {
                    AdEventTracker.log("ad_dismissed", placement)
                    InlineAdSafetyGate.arm("rewarded_interstitial", placement.name)
                    preload(activity, placementName)
                    if (customRewardEarned.get()) {
                        safeCallback(onReward)
                        safeCallback(onComplete)
                    }
                })
                if (!shown) {
                    AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
                    showFailure(activity, placementName, placement, "Another full-screen ad is already showing.", onReward, onComplete)
                } else {
                    frequency.markShown(placement)
                    AdEventTracker.log("ad_impression", placement)
                }
            }
            return
        }

        RewardedIntroDialog.show(activity, placement, config.adPrimaryColor(), onSkip = {
            AdEventTracker.log("ad_opt_out", placement)
        }) {
            val ad = pollPreloadedAd(placementName)
            if (ad == null) {
                load(activity, placementName)
                waitForAdAndShow(activity, placementName, onReward, onComplete)
                return@show
            }

            presentAd(activity, placementName, placement, ad, onReward, onComplete)
        }
    }

    fun clearAll() {
        preloaderKeys.values.forEach { RewardedInterstitialAdPreloader.destroy(it) }
        preloaderKeys.clear()
    }

    private fun presentAd(
        activity: Activity,
        placementName: String,
        placement: AdPlacementConfig,
        ad: RewardedInterstitialAd,
        onReward: () -> Unit,
        onComplete: () -> Unit,
    ) {
        val completion = FullscreenCompletion(onComplete)
        val rewardEarned = AtomicBoolean(false)
        val fullscreenOwner = FullscreenAdState.tryBegin("rewarded_interstitial", placement.name)
        if (fullscreenOwner == null) {
            AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
            showFailure(activity, placementName, placement, "Another full-screen ad is already showing.", onReward, onComplete)
            return
        }
        ad.adEventCallback = object : RewardedInterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                frequency.markShown(placement)
                AdEventTracker.log("ad_impression", placement)
            }

            override fun onAdDismissedFullScreenContent() {
                AdEventTracker.log("ad_dismissed", placement)
                InlineAdSafetyGate.arm("rewarded_interstitial", placement.name)
                preload(activity, placementName)
                FullscreenAdState.end(fullscreenOwner)
                if (rewardEarned.get()) {
                    safeCallback(onReward)
                    completion.complete()
                }
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError,
            ) {
                AdEventTracker.log("ad_show_failed", placement, mapOf("message" to fullScreenContentError.message))
                FullscreenAdState.end(fullscreenOwner)
                showFailure(activity, placementName, placement, fullScreenContentError.message, onReward, onComplete)
            }
        }

        runOnMain {
            if (!activity.isUsable()) {
                FullscreenAdState.end(fullscreenOwner)
                return@runOnMain
            }
            runCatching {
                ad.show(activity) {
                    AdEventTracker.log("ad_reward_earned", placement)
                    rewardEarned.set(true)
                }
            }.onFailure {
                AdEventTracker.log("ad_show_failed", placement, mapOf("message" to (it.message ?: "show_exception")))
                FullscreenAdState.end(fullscreenOwner)
                showFailure(activity, placementName, placement, it.message ?: "The rewarded interstitial could not be opened.", onReward, onComplete)
            }
        }
    }

    private fun startPreloader(placementName: String, adUnitId: String, request: AdRequest) {
        if (preloaderKeys[placementName] == adUnitId) return

        preloaderKeys[placementName]?.let { RewardedInterstitialAdPreloader.destroy(it) }
        preloaderKeys.remove(placementName)
        val started = runCatching {
            RewardedInterstitialAdPreloader.start(
                adUnitId,
                PreloadConfiguration(request, 1),
            )
            true
        }.getOrDefault(false)
        if (started) preloaderKeys[placementName] = adUnitId
    }

    private fun pollPreloadedAd(placementName: String): RewardedInterstitialAd? {
        val key = preloaderKeys[placementName] ?: return null
        return runCatching { RewardedInterstitialAdPreloader.pollAd(key) }.getOrNull()
    }

    private fun waitForAdAndShow(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit,
    ) {
        val loadingDialog = AdLoadingDialog(activity)
        val app = configProvider().app
        val timeoutMs = (app["loading_ad_timeout_ms"] as? Number)?.toLong() ?: 7000L
        val lottieUrl = app["loading_lottie_url"] as? String
        val startedAt = System.currentTimeMillis()
        loadingDialog.show(lottieUrl)

        fun poll() {
            if (!activity.isUsable()) {
                loadingDialog.dismiss()
                return
            }
            val ad = pollPreloadedAd(placementName)
            if (ad != null) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull {
                    it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
                }
                if (placement == null) {
                    AdFailureDialog.show(activity, configProvider().adPrimaryColor(), "The rewarded interstitial placement is no longer available.")
                    return
                } else {
                    presentAd(activity, placementName, placement, ad, onReward, onComplete)
                }
                return
            }

            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull { it.name == placementName }
                if (placement != null) {
                    showFailure(activity, placementName, placement, "The ad did not load within ${timeoutMs / 1000} seconds. Check your connection and try again.", onReward, onComplete)
                }
                return
            }

            mainHandler.postDelayed({ poll() }, 150L)
        }

        mainHandler.postDelayed({ poll() }, 150L)
    }

    private fun showFailure(
        activity: Activity,
        placementName: String,
        placement: AdPlacementConfig,
        reason: String,
        onReward: () -> Unit,
        onComplete: () -> Unit,
    ) {
        AdFailureDialog.show(activity, configProvider().adPrimaryColor(), reason) {
            restartPreloader(activity, placementName)
            waitForAdAndShow(activity, placementName, onReward, onComplete)
        }
        AdEventTracker.log("ad_retry_offered", placement, mapOf("reason" to reason))
    }

    private fun restartPreloader(activity: Activity, placementName: String) {
        preloaderKeys.remove(placementName)?.let { RewardedInterstitialAdPreloader.destroy(it) }
        load(activity, placementName)
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed
}
