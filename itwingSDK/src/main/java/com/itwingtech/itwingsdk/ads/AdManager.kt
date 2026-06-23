package com.itwingtech.itwingsdk.ads


import android.app.Activity
import android.view.ViewGroup
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.ITWingConfig
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList


class AdManager(private val configProvider: () -> ITWingConfig, private val suppressAdsProvider: () -> Boolean = { false } ) {
    private val frequencyController = FrequencyController()
    private val bannerLoader by lazy { BannerLoader(configProvider) }
    private val interstitialManager by lazy { InterstitialManager(configProvider = configProvider, frequency = frequencyController) }
    private val rewardedManager by lazy { RewardedManager(configProvider, frequencyController) }
    private val rewardedInterstitialManager by lazy { RewardedInterstitialManager(configProvider, frequencyController) }
    private val appOpenManager by lazy { AppOpenManager(configProvider, frequencyController) }
    private val nativeLoader by lazy { NativeLoader(configProvider) }
    private val bannerContainers = CopyOnWriteArrayList<WeakReference<ViewGroup>>()
    private val nativeContainers = CopyOnWriteArrayList<WeakReference<ViewGroup>>()
    /**
     * Interstitial
     */
    fun showInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            trackSuppressed("interstitial", placement)
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
            trackSuppressed("rewarded", placement)
            clearCache()
            AdFailureDialog.show(activity, configProvider().adPrimaryColor(), rewardedSuppressionReason())
            return
        }
        rewardedManager.show(activity, placement, onReward, onComplete)
    }

    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            trackSuppressed("rewarded", placement)
            clearCache()
            AdFailureDialog.show(activity, configProvider().adPrimaryColor(), rewardedSuppressionReason())
            return
        }
        rewardedManager.show(activity, placement, onReward = {}, onComplete = onComplete)
    }

    /**
     * Rewarded Interstitial
     */
    fun showRewardedInterstitial(activity: Activity, placement: String, onReward: () -> Unit={}, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            trackSuppressed("rewarded_interstitial", placement)
            clearCache()
            AdFailureDialog.show(activity, configProvider().adPrimaryColor(), rewardedSuppressionReason())
            return
        }
        rewardedInterstitialManager.show(activity, placement, onReward, onComplete)
    }

    fun showRewardedInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) {
        if (adsSuppressed()) {
            trackSuppressed("rewarded_interstitial", placement)
            clearCache()
            AdFailureDialog.show(activity, configProvider().adPrimaryColor(), rewardedSuppressionReason())
            return
        }
        rewardedInterstitialManager.show(activity, placement, onReward = {}, onComplete = onComplete)
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
            trackSuppressed("app_open", placement)
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
            trackSuppressed("app_open_automatic", "automatic")
            clearCache()
            return
        }
        appOpenManager.startAutomatic(activity)
    }

    fun updateForegroundActivity(activity: Activity) {
        appOpenManager.updateForegroundActivity(activity)
    }

    /**
     * Banner
     */
    fun loadBanner(activity: Activity, container: ViewGroup, placement: String, bannerType: BannerType? = null) {
        rememberContainer(bannerContainers, container)
        if (adsSuppressed()) {
            trackSuppressed("banner", placement)
            destroyBanner(container)
            return
        }
        val activityRef = WeakReference(activity)
        val containerRef = WeakReference(container)
        if (InlineAdSafetyGate.suppressInlineAd(
                activity = activity,
                inlineFormat = "banner",
                placement = placement,
                reloadAfterInteraction = {
                    val resumedActivity = activityRef.get()
                    val resumedContainer = containerRef.get()
                    if (
                        resumedActivity != null &&
                        resumedContainer != null &&
                        !resumedActivity.isFinishing &&
                        !resumedActivity.isDestroyed &&
                        resumedContainer.isAttachedToWindow
                    ) {
                        loadBanner(resumedActivity, resumedContainer, placement, bannerType)
                    }
                },
            )
        ) {
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
        rememberContainer(nativeContainers, container)
        if (adsSuppressed()) {
            trackSuppressed("native", placement)
            destroyNative(container)
            return
        }
        val activityRef = WeakReference(activity)
        val containerRef = WeakReference(container)
        if (InlineAdSafetyGate.suppressInlineAd(
                activity = activity,
                inlineFormat = "native",
                placement = placement,
                reloadAfterInteraction = {
                    val resumedActivity = activityRef.get()
                    val resumedContainer = containerRef.get()
                    if (
                        resumedActivity != null &&
                        resumedContainer != null &&
                        !resumedActivity.isFinishing &&
                        !resumedActivity.isDestroyed &&
                        resumedContainer.isAttachedToWindow
                    ) {
                        loadNative(resumedActivity, resumedContainer, placement, nativeType)
                    }
                },
            )
        ) {
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

    internal fun onEntitlementActivated() {
        clearCache()
        bannerContainers.forEach { reference -> reference.get()?.let(::destroyBanner) }
        nativeContainers.forEach { reference -> reference.get()?.let(::destroyNative) }
        bannerContainers.removeAll { it.get() == null }
        nativeContainers.removeAll { it.get() == null }
    }

    private fun rememberContainer(
        containers: CopyOnWriteArrayList<WeakReference<ViewGroup>>,
        container: ViewGroup,
    ) {
        containers.removeAll { it.get() == null }
        if (containers.none { it.get() === container }) {
            containers.add(WeakReference(container))
        }
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

    private fun rewardedSuppressionReason(): String {
        val ads = configProvider().ads
        return when (ads.blockedReason) {
            "active_subscription" -> "Ads are disabled because this user has an active ad-free subscription."
            else -> "Rewarded ads are currently unavailable for this app."
        }
    }
    private fun trackSuppressed(format: String, placement: String) {
        val ads = configProvider().ads
        SDKTelemetry.track(
            "ad_suppressed",
            mapOf(
                "format" to format,
                "placement" to placement,
                "global_enabled" to ads.globalEnabled,
                "blocked_reason" to (ads.blockedReason ?: if (suppressAdsProvider()) "subscription" else "unknown"),
            ),
        )
    }
}
