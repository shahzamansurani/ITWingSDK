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
import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.ConcurrentHashMap

class RewardedInterstitialManager(
    private val configProvider: () -> ITWingConfig,
    private val frequency: FrequencyController
) {
    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val preloaderKeys =
        ConcurrentHashMap<String, String>()

    private val customRenderer =
        CustomFullscreenAdRenderer()

    /*
    |--------------------------------------------------------------------------
    | Preload All
    |--------------------------------------------------------------------------
    */

    fun preloadAll(
        activity: Activity
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                return@runOnMain
            }

            val config =
                safeConfig()
                    ?: return@runOnMain

            if (!config.ads.globalEnabled) {
                return@runOnMain
            }

            config.ads.placements
                .filter {
                    it.enabled &&
                            it.format == "rewarded_interstitial"
                }
                .forEach {
                    preload(
                        activity,
                        it.name
                    )
                }
        }
    }

    fun preload(
        activity: Activity,
        placementName: String
    ) {
        load(
            activity,
            placementName
        )
    }

    /*
    |--------------------------------------------------------------------------
    | Load
    |--------------------------------------------------------------------------
    */

    fun load(
        activity: Activity,
        placementName: String
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                return@runOnMain
            }

            val config =
                safeConfig()
                    ?: return@runOnMain

            if (!config.ads.globalEnabled) {
                return@runOnMain
            }

            val placement =
                config.ads.placements.firstOrNull {
                    it.name == placementName &&
                            it.enabled &&
                            it.format == "rewarded_interstitial"
                } ?: return@runOnMain

            if (
                customRenderer.canRender(
                    placement
                )
            ) {

                customRenderer.preload(
                    activity,
                    placement
                )

                return@runOnMain
            }

            val unit =
                placement.units.firstOrNull {
                    it.network == "admob"
                } ?: return@runOnMain

            val request =
                runCatching {

                    AdRequest.Builder(
                        unit.adUnitId
                    ).build()

                }.getOrNull()
                    ?: return@runOnMain

            startPreloader(
                placementName,
                unit.adUnitId,
                request
            )
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Show
    |--------------------------------------------------------------------------
    */

    fun show(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit = {}
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                safeCallback(onComplete)
                return@runOnMain
            }

            val config =
                safeConfig()

            if (
                config == null ||
                !config.ads.globalEnabled
            ) {
                safeCallback(onComplete)
                return@runOnMain
            }

            val placement =
                config.ads.placements.firstOrNull {
                    it.name == placementName &&
                            it.enabled &&
                            it.format == "rewarded_interstitial"
                }

            if (
                placement == null ||
                !frequency.canShow(
                    placement,
                    countTrigger = true
                )
            ) {
                safeCallback(onComplete)
                return@runOnMain
            }

            /*
            |--------------------------------------------------------------------------
            | Custom Rewarded Interstitial
            |--------------------------------------------------------------------------
            */

            if (
                customRenderer.canRender(
                    placement
                )
            ) {

                showRewardedIntro(
                    activity = activity,
                    placement = placement,
                    onSkip = onComplete
                ) {

                    frequency.markShown(
                        placement
                    )

                    customRenderer.show(
                        activity,
                        placement,
                        reward = onReward,
                        onComplete = {

                            preload(
                                activity,
                                placementName
                            )

                            safeCallback(
                                onComplete
                            )
                        }
                    )
                }

                return@runOnMain
            }

            /*
            |--------------------------------------------------------------------------
            | AdMob Rewarded Interstitial
            |--------------------------------------------------------------------------
            */

            showRewardedIntro(
                activity = activity,
                placement = placement,
                onSkip = onComplete
            ) {

                val ad =
                    pollPreloadedAd(
                        placementName
                    )

                if (ad == null) {

                    load(
                        activity,
                        placementName
                    )

                    waitForAdAndShow(
                        activity,
                        placementName,
                        onReward,
                        onComplete
                    )

                    return@showRewardedIntro
                }

                presentAd(
                    activity,
                    placementName,
                    placement,
                    ad,
                    onReward,
                    onComplete
                )
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Clear
    |--------------------------------------------------------------------------
    */

    fun clearAll() {
        runOnMain {

            preloaderKeys.values.forEach { key ->

                runCatching {

                    RewardedInterstitialAdPreloader.destroy(
                        key
                    )
                }
            }

            preloaderKeys.clear()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Present Ad
    |--------------------------------------------------------------------------
    */

    private fun presentAd(
        activity: Activity,
        placementName: String,
        placement: AdPlacementConfig,
        ad: RewardedInterstitialAd,
        onReward: () -> Unit,
        onComplete: () -> Unit
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                safeCallback(onComplete)
                return@runOnMain
            }

            ad.adEventCallback =
                object : RewardedInterstitialAdEventCallback {

                    override fun onAdShowedFullScreenContent() {
                        runOnMain {

                            runCatching {

                                frequency.markShown(
                                    placement
                                )
                            }
                        }
                    }

                    override fun onAdDismissedFullScreenContent() {
                        runOnMain {

                            preload(
                                activity,
                                placementName
                            )

                            safeCallback(
                                onComplete
                            )
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        runOnMain {

                            preload(
                                activity,
                                placementName
                            )

                            safeCallback(
                                onComplete
                            )
                        }
                    }
                }

            runCatching {

                ad.show(
                    activity
                ) {

                    safeCallback(
                        onReward
                    )
                }

                preload(
                    activity,
                    placementName
                )

            }.onFailure {

                preload(
                    activity,
                    placementName
                )

                safeCallback(
                    onComplete
                )
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Preloader
    |--------------------------------------------------------------------------
    */

    private fun startPreloader(
        placementName: String,
        adUnitId: String,
        request: AdRequest
    ) {
        runOnMain {

            if (
                preloaderKeys[placementName] ==
                adUnitId
            ) {
                return@runOnMain
            }

            preloaderKeys[placementName]?.let { oldKey ->

                runCatching {

                    RewardedInterstitialAdPreloader.destroy(
                        oldKey
                    )
                }
            }

            preloaderKeys[placementName] =
                adUnitId

            runCatching {

                RewardedInterstitialAdPreloader.start(
                    adUnitId,
                    PreloadConfiguration(
                        request,
                        1
                    )
                )
            }
        }
    }

    private fun pollPreloadedAd(
        placementName: String
    ): RewardedInterstitialAd? {

        val key =
            preloaderKeys[placementName]
                ?: return null

        return runCatching {

            RewardedInterstitialAdPreloader.pollAd(
                key
            )

        }.getOrNull()
    }

    /*
    |--------------------------------------------------------------------------
    | Wait For Ad
    |--------------------------------------------------------------------------
    */

    private fun waitForAdAndShow(
        activity: Activity,
        placementName: String,
        onReward: () -> Unit,
        onComplete: () -> Unit
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                safeCallback(onComplete)
                return@runOnMain
            }

            val loadingDialog =
                AdLoadingDialog(
                    activity
                )

            val app =
                safeConfig()
                    ?.app
                    ?: emptyMap<String, Any?>()

            val timeoutMs =
                (
                        app["loading_ad_timeout_ms"]
                                as? Number
                        )?.toLong()
                    ?: 2500L

            val lottieUrl =
                app["loading_lottie_url"]
                        as? String

            val startedAt =
                System.currentTimeMillis()

            loadingDialog.show(
                lottieUrl
            )

            fun poll() {

                if (!isActivityUsable(activity)) {

                    loadingDialog.dismiss()

                    safeCallback(
                        onComplete
                    )

                    return
                }

                val ad =
                    pollPreloadedAd(
                        placementName
                    )

                if (ad != null) {

                    loadingDialog.dismiss()

                    val placement =
                        safeConfig()
                            ?.ads
                            ?.placements
                            ?.firstOrNull {
                                it.name == placementName &&
                                        it.enabled &&
                                        it.format == "rewarded_interstitial"
                            }

                    if (placement == null) {

                        safeCallback(
                            onComplete
                        )

                    } else {

                        presentAd(
                            activity,
                            placementName,
                            placement,
                            ad,
                            onReward,
                            onComplete
                        )
                    }

                    return
                }

                if (
                    System.currentTimeMillis() -
                    startedAt >= timeoutMs
                ) {

                    loadingDialog.dismiss()

                    safeCallback(
                        onComplete
                    )

                    return
                }

                mainHandler.postDelayed(
                    {
                        poll()
                    },
                    150L
                )
            }

            mainHandler.postDelayed(
                {
                    poll()
                },
                150L
            )
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Rewarded Intro Dialog
    |--------------------------------------------------------------------------
    */

    private fun showRewardedIntro(
        activity: Activity,
        placement: AdPlacementConfig,
        onSkip: () -> Unit,
        onContinue: () -> Unit
    ) {
        runOnMain {
            if (!isActivityUsable(activity)) {
                safeCallback(onSkip)
                return@runOnMain
            }

            runCatching {
                RewardedIntroDialog.show(
                    activity,
                    placement,
                    onSkip = { safeCallback(onSkip) },
                    onWatch = {
                        runOnMain {
                            if (!isActivityUsable(activity)) {
                                safeCallback(onSkip)
                            } else {
                                onContinue()
                            }
                        }
                    }
                )

            }.onFailure {

                safeCallback(
                    onSkip
                )
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Helpers
    |--------------------------------------------------------------------------
    */

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
}

//package com.itwingtech.itwingsdk.ads
//
//import android.app.Activity
//import android.os.Handler
//import android.os.Looper
//import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
//import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
//import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
//import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
//import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
//import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader
//import com.itwingtech.itwingsdk.core.AdPlacementConfig
//import com.itwingtech.itwingsdk.core.ITWingConfig
//import com.itwingtech.itwingsdk.utils.runOnMain
//import com.itwingtech.itwingsdk.utils.safeCallback
//import java.util.concurrent.ConcurrentHashMap
//
//class RewardedInterstitialManager(
//    private val configProvider: () -> ITWingConfig,
//    private val frequency: FrequencyController,
//) {
//    private val mainHandler = Handler(Looper.getMainLooper())
//    private val preloaderKeys = ConcurrentHashMap<String, String>()
//    private val customRenderer = CustomFullscreenAdRenderer()
//
//    fun preloadAll(activity: Activity) {
//        val config = configProvider()
//        config.ads.placements
//            .filter { config.ads.globalEnabled && it.enabled && it.format == "rewarded_interstitial" }
//            .forEach { preload(activity, it.name) }
//    }
//
//    fun preload(activity: Activity, placementName: String) {
//        load(activity, placementName)
//    }
//
//    fun load(activity: Activity, placementName: String) {
//        val config = configProvider()
//        if (!config.ads.globalEnabled) return
//
//        val placement = config.ads.placements.firstOrNull {
//            it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
//        } ?: return
//
//        if (customRenderer.canRender(placement)) {
//            customRenderer.preload(activity, placement)
//            return
//        }
//
//        val unit = placement.units.firstOrNull { it.network == "admob" } ?: return
//        val request = AdRequest.Builder(unit.adUnitId).build()
//        startPreloader(placementName, unit.adUnitId, request)
//    }
//
//    fun show(
//        activity: Activity,
//        placementName: String,
//        onReward: () -> Unit,
//        onComplete: () -> Unit = {},
//    ) {
//        val config = configProvider()
//        if (!config.ads.globalEnabled) {
//            safeCallback(onComplete)
//            return
//        }
//
//        val placement = config.ads.placements.firstOrNull {
//            it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
//        }
//
//        if (placement == null || !frequency.canShow(placement, countTrigger = true)) {
//            safeCallback(onComplete)
//            return
//        }
//
//        if (customRenderer.canRender(placement)) {
//            RewardedIntroDialog.show(activity, placement, onSkip = onComplete) {
//                frequency.markShown(placement)
//                customRenderer.show(activity, placement, reward = onReward, onComplete = {
//                    preload(activity, placementName)
//                    safeCallback(onComplete)
//                })
//            }
//            return
//        }
//
//        RewardedIntroDialog.show(activity, placement, onSkip = onComplete) {
//            val ad = pollPreloadedAd(placementName)
//            if (ad == null) {
//                load(activity, placementName)
//                waitForAdAndShow(activity, placementName, onReward, onComplete)
//                return@show
//            }
//
//            presentAd(activity, placementName, placement, ad, onReward, onComplete)
//        }
//    }
//
//    fun clearAll() {
//        preloaderKeys.values.forEach { RewardedInterstitialAdPreloader.destroy(it) }
//        preloaderKeys.clear()
//    }
//
//    private fun presentAd(
//        activity: Activity,
//        placementName: String,
//        placement: AdPlacementConfig,
//        ad: RewardedInterstitialAd,
//        onReward: () -> Unit,
//        onComplete: () -> Unit,
//    ) {
//        ad.adEventCallback = object : RewardedInterstitialAdEventCallback {
//            override fun onAdShowedFullScreenContent() {
//                frequency.markShown(placement)
//            }
//
//            override fun onAdDismissedFullScreenContent() {
//                preload(activity, placementName)
//                safeCallback(onComplete)
//            }
//
//            override fun onAdFailedToShowFullScreenContent(
//                fullScreenContentError: FullScreenContentError,
//            ) {
//                preload(activity, placementName)
//                safeCallback(onComplete)
//            }
//        }
//
//        runOnMain {
//            runCatching {
//                ad.show(activity) {
//                    safeCallback(onReward)
//                }
//                preload(activity, placementName)
//            }.onFailure {
//                preload(activity, placementName)
//                safeCallback(onComplete)
//            }
//        }
//    }
//
//    private fun startPreloader(placementName: String, adUnitId: String, request: AdRequest) {
//        if (preloaderKeys[placementName] == adUnitId) return
//
//        preloaderKeys[placementName]?.let { RewardedInterstitialAdPreloader.destroy(it) }
//        preloaderKeys[placementName] = adUnitId
//        runCatching {
//            RewardedInterstitialAdPreloader.start(
//                adUnitId,
//                PreloadConfiguration(request, 1),
//            )
//        }
//    }
//
//    private fun pollPreloadedAd(placementName: String): RewardedInterstitialAd? {
//        val key = preloaderKeys[placementName] ?: return null
//        return runCatching { RewardedInterstitialAdPreloader.pollAd(key) }.getOrNull()
//    }
//
//    private fun waitForAdAndShow(
//        activity: Activity,
//        placementName: String,
//        onReward: () -> Unit,
//        onComplete: () -> Unit,
//    ) {
//        val loadingDialog = AdLoadingDialog(activity)
//        val app = configProvider().app
//        val timeoutMs = (app["loading_ad_timeout_ms"] as? Number)?.toLong() ?: 2500L
//        val lottieUrl = app["loading_lottie_url"] as? String
//        val startedAt = System.currentTimeMillis()
//        loadingDialog.show(lottieUrl)
//
//        fun poll() {
//            val ad = pollPreloadedAd(placementName)
//            if (ad != null) {
//                loadingDialog.dismiss()
//                val placement = configProvider().ads.placements.firstOrNull {
//                    it.name == placementName && it.enabled && it.format == "rewarded_interstitial"
//                }
//                if (placement == null) {
//                    safeCallback(onComplete)
//                } else {
//                    presentAd(activity, placementName, placement, ad, onReward, onComplete)
//                }
//                return
//            }
//
//            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
//                loadingDialog.dismiss()
//                safeCallback(onComplete)
//                return
//            }
//
//            mainHandler.postDelayed({ poll() }, 150L)
//        }
//
//        mainHandler.postDelayed({ poll() }, 150L)
//    }
//}
