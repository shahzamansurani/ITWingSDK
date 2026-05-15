package com.itwingtech.itwingsdk.ads


import android.app.Activity
import android.view.ViewGroup
import com.itwingtech.itwingsdk.core.ITWingConfig


class AdManager(private val configProvider: () -> ITWingConfig, private val suppressAdsProvider: () -> Boolean = { false } ) {
    private val frequencyController = FrequencyController()
    private val bannerLoader by lazy { BannerLoader(configProvider) }
    private val interstitialManager by lazy { InterstitialManager(configProvider = configProvider, frequency = frequencyController) }
    private val rewardedManager by lazy { RewardedManager(configProvider, frequencyController) }
    private val rewardedInterstitialManager by lazy { RewardedInterstitialManager(configProvider, frequencyController) }
    private val appOpenManager by lazy { AppOpenManager(configProvider, frequencyController) }
    private val nativeLoader by lazy { NativeLoader(configProvider) }
    /**
     * Interstitial
     */
    fun showInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        interstitialManager.show(activity = activity, placementName = placement, onComplete = onComplete,)
    }

    fun preloadInterstitial(activity: Activity, placement: String) {
        if (adsSuppressed()) return
        interstitialManager.preload(activity = activity, placementName = placement)
    }

    fun isInterstitialLoaded(placement: String): Boolean {
        return interstitialManager.isLoaded(placement)
    }

    /**
     * Rewarded
     */
    fun showRewarded(activity: Activity, placement: String, onReward: () -> Unit, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        rewardedManager.show(activity, placement, onReward, onComplete)
    }

    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        rewardedManager.show(activity, placement, onComplete)
    }

    /**
     * Rewarded Interstitial
     */
    fun showRewardedInterstitial(activity: Activity, placement: String, onReward: () -> Unit={}, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        rewardedInterstitialManager.show(activity, placement, onReward, onComplete)
    }

    fun showRewardedInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        rewardedInterstitialManager.show(activity, placement, onComplete)
    }

    fun preloadRewarded(activity: Activity, placement: String) {
        if (adsSuppressed()) return
        rewardedManager.preload(activity, placement)
    }

    fun preloadRewardedInterstitial(activity: Activity, placement: String) {
        if (adsSuppressed()) return
        rewardedInterstitialManager.preload(activity, placement)
    }

    fun showAppOpen(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            clearCache()
            onComplete()
            return
        }
        appOpenManager.show(activity, placement, onComplete)
    }

    fun preloadAppOpen(activity: Activity, placement: String) {
        if (adsSuppressed()) return
        appOpenManager.preload(activity, placement)
    }

    fun startAutomaticAppOpen(activity: Activity) {
        if (adsSuppressed()) {
            clearCache()
            return
        }
        appOpenManager.startAutomatic(activity)
    }

    /**
     * Banner
     */
    fun loadBanner(activity: Activity, container: ViewGroup, placement: String, bannerType: BannerType? = null) {
        if (adsSuppressed()) {
            destroyBanner(container)
            return
        }
        bannerLoader.load(activity = activity, container = container, placementName = placement, bannerType = bannerType)
    }

    fun destroyBanner(container: ViewGroup) {
        bannerLoader.destroy(container)
    }

    /**
     * Native
     */
    fun loadNative(activity: Activity, container: ViewGroup, placement: String, nativeType: NativeType? = null) {
        if (adsSuppressed()) {
            destroyNative(container)
            return
        }
        nativeLoader.load(activity = activity, container = container, placementName = placement, nativeTypeOverride = nativeType)
    }

    fun destroyNative(container: ViewGroup) {
        nativeLoader.destroy(container)
    }

    /**
     * SDK Cleanup
     */
    fun clearCache() {
        interstitialManager.clearAll()
        rewardedManager.clearAll()
        rewardedInterstitialManager.clearAll()
        appOpenManager.clear()
    }

    fun preloadAll(activity: Activity){
        preloadInterstitials(activity)
        preloadRewardedAds(activity)
        preloadRewardedInterstitials(activity)
        preloadAppOpen(activity)
    }

    fun preloadInterstitials(activity: Activity) {
        if (adsSuppressed()) return
        interstitialManager.preloadAll(activity)
    }

    fun preloadRewardedAds(activity: Activity) {
        if (adsSuppressed()) return
        rewardedManager.preloadAll(activity)
    }

    fun preloadRewardedInterstitials(activity: Activity) {
        if (adsSuppressed()) return
        rewardedInterstitialManager.preloadAll(activity)
    }

    fun preloadAppOpen(activity: Activity) {
        if (adsSuppressed()) return
        appOpenManager.preloadAll(activity)
    }

    private fun adsSuppressed(): Boolean {
        val ads = configProvider().ads
        return suppressAdsProvider() || !ads.globalEnabled || ads.blockedReason == "active_subscription"
    }
}
