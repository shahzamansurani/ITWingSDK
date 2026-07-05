package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.utils.runOnMain
import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RewardedManager(
    private val configProvider: () -> ITWingConfig,
    private val frequency: FrequencyController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preloaderKeys = ConcurrentHashMap<String, String>()
    private val lastLoadAttemptAt = ConcurrentHashMap<String, Long>()
    private val customRenderer = CustomFullscreenAdRenderer()
    private val minLoadIntervalMs = 20_000L

    fun preloadAll(activity: Activity) {
        val config = configProvider()
        config.ads.placements
            .filter {
                config.ads.globalEnabled &&
                    it.enabled &&
                    it.format == "rewarded" &&
                    it.metadata["preload_on_start"].isTruthy()
            }
            .forEach { preload(activity, it.name) }
    }

    fun preload(activity: Activity, placementName: String) {
        load(activity, placementName, forceRequest = false)
    }

    fun load(activity: Activity, placementName: String, forceRequest: Boolean = false) {
        val config = configProvider()
        if (!config.ads.globalEnabled) return

        if (!NetworkState.isOnline(activity)) return

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "rewarded"
        } ?: return

        if (customRenderer.canRender(placement)) {
            customRenderer.preload(activity, placement)
            return
        }

        val unit = placement.units.firstOrNull { it.network == "admob" } ?: return
        if (!canStartLoad(placementName, placement)) return
        val request = AdRequest.Builder(unit.adUnitId).build()
        AdEventTracker.log("ad_load_requested", placement)
        startPreloader(placementName, unit.adUnitId, request)
    }

    fun show(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit = {},
        onUnavailableOrSkipped: () -> Unit = {},
    ) {
        if (!activity.isUsable()) {
            safeCallback(onUnavailableOrSkipped)
            return
        }
        val config = configProvider()
        if (!NetworkState.isOnline(activity)) {
            AdFailureDialog.show(activity, config.adPrimaryColor(), NetworkState.offlineMessage())
            safeCallback(onUnavailableOrSkipped)
            return
        }

        if (!config.ads.globalEnabled) {
            AdFailureDialog.show(activity, config.adPrimaryColor(), "Rewarded ads are disabled for this app.")
            safeCallback(onUnavailableOrSkipped)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName && it.enabled && it.format == "rewarded"
        }

        if (placement == null) {
            AdFailureDialog.show(
                activity,
                config.adPrimaryColor(),
                "The rewarded placement '$placementName' is missing or disabled.",
            )
            safeCallback(onUnavailableOrSkipped)
            return
        }

        if (!frequency.canShow(placement)) {
            AdEventTracker.log("ad_frequency_capped", placement)
            AdFailureDialog.show(activity, config.adPrimaryColor(), "This ad reached its display limit. Please try again later.")
            safeCallback(onUnavailableOrSkipped)
            return
        }

        if (!customRenderer.canRender(placement) && placement.units.none { it.network.equals("admob", true) && it.adUnitId.isNotBlank() }) {
            AdFailureDialog.show(activity, config.adPrimaryColor(), "No valid AdMob unit is configured for this rewarded placement.")
            safeCallback(onUnavailableOrSkipped)
            return
        }

        RewardedIntroDialog.show(activity, placement, config.adPrimaryColor(), onSkip = {
            AdEventTracker.log("ad_opt_out", placement)
            safeCallback(onUnavailableOrSkipped)
        }) {
            AdEventTracker.log("ad_show_requested", placement)
            if (customRenderer.canRender(placement)) {
                val customRewardEarned = AtomicBoolean(false)
                val shown = customRenderer.show(activity, placement, reward = {
                    AdEventTracker.log("ad_reward_earned", placement)
                    customRewardEarned.set(true)
                }, onComplete = {
                    AdEventTracker.log("ad_dismissed", placement)
                    InlineAdSafetyGate.arm("rewarded", placement.name)
                    preloadAfterShowIfEnabled(activity, placementName, placement)
                    if (customRewardEarned.get()) {
                        safeCallback(onReward)
                        safeCallback(onComplete)
                    } else {
                        safeCallback(onUnavailableOrSkipped)
                    }
                })
                if (!shown) {
                    AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
                    showFailure(activity, placementName, placement, "Another full-screen ad is already showing.", onReward, onComplete, onUnavailableOrSkipped)
                } else {
                    AdEventTracker.log("ad_requested", placement)
                    frequency.markShown(placement)
                    AdEventTracker.log("ad_impression", placement)
                }
                return@show
            }

            val ad = pollPreloadedAd(placementName)
            if (ad == null) {
                load(activity, placementName, forceRequest = true)
                waitForAdAndShow(activity, placementName, onReward, onComplete, onUnavailableOrSkipped)
                return@show
            }

            presentAd(activity, placementName, placement, ad, onReward, onComplete, onUnavailableOrSkipped)
        }
    }

    fun clearAll() {
        preloaderKeys.values.forEach { RewardedAdPreloader.destroy(it) }
        preloaderKeys.clear()
    }

    private fun presentAd(
        activity: Activity,
        placementName: String,
        placement: AdPlacementConfig,
        ad: RewardedAd,
        onReward: () -> Unit,
        onComplete: () -> Unit,
        onUnavailableOrSkipped: () -> Unit,
    ) {
        val completion = FullscreenCompletion(onComplete)
        val rewardEarned = AtomicBoolean(false)
        val fullscreenOwner = FullscreenAdState.tryBegin("rewarded", placement.name)
        if (fullscreenOwner == null) {
            AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
            showFailure(activity, placementName, placement, "Another full-screen ad is already showing.", onReward, onComplete, onUnavailableOrSkipped)
            return
        }
        AdEventTracker.log("ad_requested", placement)
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                frequency.markShown(placement)
                AdEventTracker.log("ad_impression", placement)
            }

            override fun onAdDismissedFullScreenContent() {
                AdEventTracker.log("ad_dismissed", placement)
                InlineAdSafetyGate.arm("rewarded", placement.name)
                preloadAfterShowIfEnabled(activity, placementName, placement)
                FullscreenAdState.end(fullscreenOwner)
                if (rewardEarned.get()) {
                    safeCallback(onReward)
                    completion.complete()
                } else {
                    safeCallback(onUnavailableOrSkipped)
                }
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError,
            ) {
                AdEventTracker.log("ad_show_failed", placement, mapOf("message" to fullScreenContentError.message))
                FullscreenAdState.end(fullscreenOwner)
                showFailure(activity, placementName, placement, fullScreenContentError.message, onReward, onComplete, onUnavailableOrSkipped)
            }

            override fun onAdPaid(adValue: AdValue) {
                AdEventTracker.log(
                    "ad_paid",
                    placement,
                    mapOf(
                        "revenue_micros" to adValue.valueMicros,
                        "currency" to adValue.currencyCode,
                        "precision" to adValue.precisionType,
                        "ad_unit_id" to (placement.units.firstOrNull { it.network == "admob" }?.adUnitId ?: ""),
                    ),
                )
            }
        }

        runOnMain {
            if (!activity.isUsable()) {
                FullscreenAdState.end(fullscreenOwner)
                safeCallback(onUnavailableOrSkipped)
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
                showFailure(activity, placementName, placement, it.message ?: "The rewarded ad could not be opened.", onReward, onComplete, onUnavailableOrSkipped)
            }
        }
    }

    private fun startPreloader(placementName: String, adUnitId: String, request: AdRequest) {
        if (preloaderKeys[placementName] == adUnitId) return

        preloaderKeys[placementName]?.let { RewardedAdPreloader.destroy(it) }
        preloaderKeys.remove(placementName)
        val started = runCatching {
            RewardedAdPreloader.start(
                adUnitId,
                PreloadConfiguration(request, 1),
            )
            true
        }.getOrDefault(false)
        if (started) preloaderKeys[placementName] = adUnitId
    }

    private fun pollPreloadedAd(placementName: String): RewardedAd? {
        val key = preloaderKeys[placementName] ?: return null
        val ad = runCatching { RewardedAdPreloader.pollAd(key) }.getOrNull()
        if (ad != null) {
            preloaderKeys.remove(placementName)
            runCatching { RewardedAdPreloader.destroy(key) }
        }
        return ad
    }

    private fun waitForAdAndShow(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit,
        onUnavailableOrSkipped: () -> Unit,
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
                safeCallback(onUnavailableOrSkipped)
                return
            }
            if (!NetworkState.isOnline(activity)) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull { it.name == placementName }
                if (placement != null) {
                    showFailure(activity, placementName, placement, NetworkState.offlineMessage(), onReward, onComplete, onUnavailableOrSkipped)
                } else {
                    safeCallback(onUnavailableOrSkipped)
                }
                return
            }
            val ad = pollPreloadedAd(placementName)
            if (ad != null) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull {
                    it.name == placementName && it.enabled && it.format == "rewarded"
                }
                if (placement == null) {
                    AdFailureDialog.show(activity, configProvider().adPrimaryColor(), "The rewarded placement is no longer available.")
                    safeCallback(onUnavailableOrSkipped)
                    return
                } else {
                    presentAd(activity, placementName, placement, ad, onReward, onComplete, onUnavailableOrSkipped)
                }
                return
            }

            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull { it.name == placementName }
                if (placement != null) {
                    showFailure(activity, placementName, placement, "The ad did not load within ${timeoutMs / 1000} seconds. Check your connection and try again.", onReward, onComplete, onUnavailableOrSkipped)
                } else {
                    safeCallback(onUnavailableOrSkipped)
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
        onUnavailableOrSkipped: () -> Unit,
    ) {
        AdFailureDialog.show(activity, configProvider().adPrimaryColor(), reason) {
            restartPreloader(activity, placementName)
            waitForAdAndShow(activity, placementName, onReward, onComplete, onUnavailableOrSkipped)
        }
        safeCallback(onUnavailableOrSkipped)
        AdEventTracker.log("ad_retry_offered", placement, mapOf("reason" to reason))
    }

    private fun restartPreloader(activity: Activity, placementName: String) {
        preloaderKeys.remove(placementName)?.let { RewardedAdPreloader.destroy(it) }
        lastLoadAttemptAt.remove(placementName)
        load(activity, placementName, forceRequest = true)
    }

    private fun preloadAfterShowIfEnabled(
        activity: Activity,
        placementName: String,
        placement: AdPlacementConfig,
    ) {
        if (placement.metadata["preload_after_show"].isTruthy()) {
            preload(activity, placementName)
        }
    }

    private fun canStartLoad(placementName: String, placement: AdPlacementConfig): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = lastLoadAttemptAt[placementName] ?: 0L
        if (now - previous < minLoadIntervalMs) {
            AdEventTracker.log("ad_load_throttled", placement, mapOf("cooldown_ms" to minLoadIntervalMs))
            return false
        }
        lastLoadAttemptAt[placementName] = now
        return true
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun Any?.isTruthy(): Boolean {
        return when (this) {
            is Boolean -> this
            is String -> equals("true", ignoreCase = true) || this == "1"
            is Number -> toInt() != 0
            else -> false
        }
    }
}
