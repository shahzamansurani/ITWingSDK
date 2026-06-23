package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.utils.runOnMain
import com.itwingtech.itwingsdk.utils.safeCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class AppOpenManager(
    private val configProvider: () -> ITWingConfig,
    private val frequency: FrequencyController
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)
    private val automaticStarted = AtomicBoolean(false)
    private var loadedPlacement: String? = null
    private var appOpenAd: AppOpenAd? = null
    private var foregroundActivity: WeakReference<Activity>? = null
    private val customRenderer = CustomFullscreenAdRenderer()

    fun startAutomatic(activity: Activity) {
        updateForegroundActivity(activity)
        safeCallback {
            if (!automaticStarted.compareAndSet(false, true)) {
                preloadAll(activity)
                return@safeCallback
            }

            ProcessLifecycleOwner.get().lifecycle
                .addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            runCatching {
                                val currentActivity = foregroundActivity?.get()?.takeUnless {
                                    it.isFinishing || it.isDestroyed
                                } ?: return
                                if (FullscreenAdState.isActive()) {
                                    SDKTelemetry.track(
                                        "ad_suppressed",
                                        mapOf(
                                            "format" to "app_open",
                                            "placement" to "automatic",
                                            "reason" to "fullscreen_ad_active",
                                            "active_owner" to (FullscreenAdState.activeOwner() ?: "unknown"),
                                        ),
                                    )
                                    return
                                }
                                val placement = automaticPlacementName() ?: return
                                show(currentActivity, placement, waitForLoad = false)
                            }
                        }
                    },
                )

            preloadAll(activity)
        }
    }

    fun updateForegroundActivity(activity: Activity) {
        foregroundActivity = WeakReference(activity)
    }

    fun preloadAll(activity: Activity) {
        val placement = automaticPlacement() ?: return
        preload(activity, placement.name)
    }


    fun preload(activity: Activity, placementName: String) {
        val config = configProvider()
        if (!config.ads.globalEnabled || loading.get() || appOpenAd != null) {
            return
        }

        val placement = config.ads.placements.firstOrNull {
            it.name == placementName &&
                    it.enabled &&
                    it.format == "app_open"

        } ?: return

        if (customRenderer.canRender(placement)) {
            customRenderer.preload(activity, placement)
            loadedPlacement = placementName
            return
        }

        val unit = placement.units.firstOrNull {
            it.network == "admob"
        } ?: return
        loading.set(true)
        AppOpenAd.load(
            AdRequest.Builder(unit.adUnitId).build(),
            object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) {
                    loading.set(false)
                    appOpenAd = ad
                    loadedPlacement = placementName
                }

                override fun onAdFailedToLoad(
                    adError: LoadAdError,
                ) {

                    loading.set(false)
                }
            },
        )
    }

    fun show(
        activity: Activity,
        placementName: String = loadedPlacement ?: "app_open",
        onComplete: () -> Unit = {},
        waitForLoad: Boolean = true,
    ) {
        updateForegroundActivity(activity)
        val config = configProvider()
        val placement =
            config.ads.placements.firstOrNull {
                config.ads.globalEnabled &&
                        it.name == placementName &&
                        it.enabled &&
                        it.format == "app_open"
            }

        if (placement == null || !frequency.canShow(placement)) {
            placement?.let { AdEventTracker.log("ad_frequency_capped", it) }
            safeCallback(onComplete)
            return
        }

        if (FullscreenAdState.isActive()) {
            AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
            safeCallback(onComplete)
            return
        }

        AdEventTracker.log("ad_requested", placement)
        if (customRenderer.canRender(placement)) {
            val shown = customRenderer.show(activity, placement, onComplete = {
                AdEventTracker.log("ad_dismissed", placement)
                InlineAdSafetyGate.arm("app_open", placement.name)
                preload(activity, placementName)
                safeCallback(onComplete)
            })
            if (shown) {
                frequency.markShown(placement)
                AdEventTracker.log("ad_impression", placement)
            } else {
                AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active_or_show_failed"))
                safeCallback(onComplete)
            }
            return
        }

        val ad = appOpenAd
        if (ad == null) {
            preload(activity, placementName)
            if (waitForLoad) {
                waitForAdAndShow(activity, placementName, onComplete)
            } else {
                safeCallback(onComplete)
            }
            return
        }

        presentAd(activity, placementName, placement, ad, onComplete)
    }

    fun clear() {
        appOpenAd = null
        loadedPlacement = null
        loading.set(false)
    }

    private fun automaticPlacementName(): String? {
        return automaticPlacement()?.name
    }

    private fun automaticPlacement(): com.itwingtech.itwingsdk.core.AdPlacementConfig? {
        val config = runCatching { configProvider() }.getOrNull() ?: return null
        if (!config.ads.globalEnabled) return null

        return config.ads.placements.firstOrNull { placement ->
            runCatching {
                placement.enabled &&
                    placement.format == "app_open" &&
                    placement.metadata["splash"].isDisabledByDefault() &&
                    !placement.metadata["usage"].safeString().equals("splash", ignoreCase = true) &&
                    placement.metadata["show_automatically"].isEnabledByDefault()
            }.getOrDefault(false)
        }
    }

    private fun presentAd(
        activity: Activity,
        placementName: String,
        placement: com.itwingtech.itwingsdk.core.AdPlacementConfig,
        ad: AppOpenAd,
        onComplete: () -> Unit,
    ) {
        val completion = FullscreenCompletion(onComplete)
        val fullscreenOwner = FullscreenAdState.tryBegin("app_open", placement.name)
        if (fullscreenOwner == null) {
            AdEventTracker.log("ad_suppressed", placement, mapOf("reason" to "fullscreen_ad_active"))
            completion.complete()
            return
        }
        appOpenAd = null
        loadedPlacement = null
        ad.adEventCallback =
            object : AppOpenAdEventCallback {
                override fun onAdShowedFullScreenContent() {
                    frequency.markShown(placement)
                    AdEventTracker.log("ad_impression", placement)
                }

                override fun onAdDismissedFullScreenContent() {
                    AdEventTracker.log("ad_dismissed", placement)
                    InlineAdSafetyGate.arm("app_open", placement.name)
                    preload(activity, placementName)
                    FullscreenAdState.end(fullscreenOwner)
                    completion.complete()
                }

                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                    AdEventTracker.log("ad_show_failed", placement, mapOf("message" to fullScreenContentError.message))
                    preload(activity, placementName)
                    FullscreenAdState.end(fullscreenOwner)
                    completion.complete()
                }
            }

        runOnMain {
            runCatching {
                ad.show(activity)
            }.onFailure {
                AdEventTracker.log("ad_show_failed", placement, mapOf("message" to (it.message ?: "show_exception")))
                preload(activity, placementName)
                FullscreenAdState.end(fullscreenOwner)
                completion.complete()
            }
        }
    }

    private fun waitForAdAndShow(activity: Activity, placementName: String, onComplete: () -> Unit) {
        val loadingDialog = AdLoadingDialog(activity)
        val app = configProvider().app
        val timeoutMs = (app["loading_ad_timeout_ms"] as? Number)?.toLong() ?: 7000L
        val lottieUrl = app["loading_lottie_url"] as? String
        val startedAt = System.currentTimeMillis()
        loadingDialog.show(lottieUrl)

        fun poll() {
            val ad = appOpenAd
            if (ad != null) {
                loadingDialog.dismiss()
                val placement = configProvider().ads.placements.firstOrNull {
                    it.name == placementName && it.enabled && it.format == "app_open"
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

    private fun Any?.isEnabledByDefault(): Boolean {
        return when (val value = normalizedValue()) {
            null -> true
            is Boolean -> value
            is String -> !value.equals("false", ignoreCase = true) &&
                value != "0" &&
                !value.equals("no", ignoreCase = true) &&
                !value.equals("off", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> true
        }
    }

    private fun Any?.isDisabledByDefault(): Boolean {
        return when (val value = normalizedValue()) {
            null -> true
            is Boolean -> !value
            is String -> value.equals("false", ignoreCase = true) ||
                value == "0" ||
                value.equals("no", ignoreCase = true) ||
                value.equals("off", ignoreCase = true)
            is Number -> value.toInt() == 0
            else -> true
        }
    }

    private fun Any?.safeString(): String? = normalizedValue()?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Any?.normalizedValue(): Any? {
        return runCatching {
            when (this) {
                null -> null
                is Boolean, is Number -> this
                is String -> trim().takeUnless {
                    it.isBlank() ||
                        it.equals("null", ignoreCase = true) ||
                        it.equals("undefined", ignoreCase = true)
                }
                else -> toString().trim().takeUnless {
                    it.isBlank() ||
                        it.equals("null", ignoreCase = true) ||
                        it.equals("undefined", ignoreCase = true)
                }
            }
        }.getOrNull()
    }
}
