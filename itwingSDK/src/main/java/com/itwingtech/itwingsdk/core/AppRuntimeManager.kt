package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.ads.FullscreenAdState
import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.atomic.AtomicBoolean

internal class AppRuntimeManager(
    private val configProvider: () -> ITWingConfig,
    private val adManagerProvider: () -> AdManager?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showSplash(activity: Activity, onComplete: () -> Unit) {
        val completed = AtomicBoolean(false)
        val adFlowStarted = AtomicBoolean(false)
        fun completeOnce() {
            if (!completed.compareAndSet(false, true)) return
            safeCallback(onComplete)
        }
        fun scheduleRuntimeTimeout(delayMs: Long = 12_000L) {
            mainHandler.postDelayed({
                if (completed.get()) return@postDelayed
                if (FullscreenAdState.isActive() || adFlowStarted.get()) {
                    scheduleRuntimeTimeout(1_000L)
                } else {
                    completeOnce()
                }
            }, delayMs)
        }
        scheduleRuntimeTimeout()

        if (activity.isFinishing || activity.isDestroyed) {
            completeOnce()
            return
        }

        val config = runCatching { configProvider() }.getOrNull() ?: run {
            completeOnce()
            return
        }
        val status = config.app["status"].safeString() ?: "active"
        val maintenance = config.app["maintenance"].safeBoolean(false)

        if (maintenance || status != "active") {
            showStateDialog(activity, status, ::completeOnce)
            return
        }

        val delayMs = splashDelayMs(config)
        val splashDelayOwner = FullscreenAdState.tryBegin("sdk_splash", "delay")
        mainHandler.postDelayed({
            adFlowStarted.set(true)
            runCatching { showSplashAd(activity, config, splashDelayOwner, ::completeOnce) }
                .onFailure {
                    FullscreenAdState.end(splashDelayOwner)
                    completeOnce()
                }
        }, delayMs)
    }

    private fun showSplashAd(activity: Activity, config: ITWingConfig, splashDelayOwner: String?, onComplete: () -> Unit) {
        FullscreenAdState.end(splashDelayOwner)
        if (activity.isFinishing || activity.isDestroyed) {
            safeCallback(onComplete)
            return
        }
        val ads = adManagerProvider()
        if (ads == null || !config.ads.globalEnabled) {
            safeCallback(onComplete)
            return
        }

        val format = splashFormat(config)
        if (format == "none" || format == "no_ad" || format == "disabled") {
            safeCallback(onComplete)
            return
        }

        val placement = config.ads.placements.firstOrNull {
            runCatching {
                it.enabled &&
                    it.format == format &&
                    (it.metadata.safeValue("splash").safeBoolean(false) ||
                        it.metadata.safeValue("usage").safeString().equals("splash", ignoreCase = true))
            }.getOrDefault(false)
        } ?: config.ads.placements.firstOrNull {
            runCatching { it.enabled && it.format == format && it.name.contains("splash", ignoreCase = true) }
                .getOrDefault(false)
        } ?: config.ads.placements.firstOrNull {
            runCatching { it.enabled && it.format == format }.getOrDefault(false)
        }

        if (placement == null) {
            safeCallback(onComplete)
            return
        }

        when (format) {
            "app_open" -> ads.showAppOpen(activity, placement.name, onComplete)
            "interstitial" -> ads.showInterstitial(activity, placement.name, onComplete)
            "rewarded" -> safeCallback(onComplete)
            "rewarded_interstitial" -> ads.showRewardedInterstitial(activity, placement.name, onComplete = onComplete)
            else -> safeCallback(onComplete)
        }
    }

    private fun showStateDialog(activity: Activity, status: String, onComplete: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            safeCallback(onComplete)
            return
        }

        val appConfig = runCatching { configProvider().app }.getOrNull().orEmpty()
        val title = appConfig["title"].safeString() ?: appConfig["name"].safeString() ?: "App"
        val message = when (status) {
            "maintenance" -> "This app is currently under maintenance. Please try again later."
            "disabled" -> "This app is currently disabled by the administrator."
            "archived" -> "This app is no longer available."
            else -> "This app is currently unavailable."
        }

        mainHandler.post {
            runCatching {
                if (activity.isFinishing || activity.isDestroyed) {
                    safeCallback(onComplete)
                    return@post
                }
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        dialog.dismiss()
                        safeCallback(onComplete)
                    }
                    .show()
            }.onFailure {
                safeCallback(onComplete)
            }
        }
    }

    private fun splashDelayMs(config: ITWingConfig): Long {
        val splash = config.app.safeValue("splash").safeMap()
        val seconds = listOf(
            splash.safeValue("seconds"),
            config.app.safeValue("splash_seconds"),
            config.app.safeValue("splashSeconds"),
        ).firstNotNullOfOrNull { it.safeLongOrNull() } ?: 7L
        return seconds.coerceIn(0L, 15L) * 1000L
    }

    private fun splashFormat(config: ITWingConfig): String {
        val splash = config.app.safeValue("splash").safeMap()
        return listOf(
            splash.safeValue("ad_format"),
            splash.safeValue("adFormat"),
            config.app.safeValue("splash_ad_format"),
            config.app.safeValue("splashAdFormat"),
        ).firstNotNullOfOrNull { it.safeString() }?.lowercase() ?: "none"
    }

    private fun Any?.safeBoolean(defaultValue: Boolean): Boolean {
        return when (val value = normalizedValue()) {
            null -> defaultValue
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) ||
                value == "1" ||
                value.equals("yes", ignoreCase = true) ||
                value.equals("on", ignoreCase = true)
            else -> defaultValue
        }
    }

    private fun Any?.safeLong(defaultValue: Long): Long {
        return safeLongOrNull() ?: defaultValue
    }

    private fun Any?.safeLongOrNull(): Long? {
        return when (val value = normalizedValue()) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun Any?.safeString(): String? = normalizedValue()?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Any?.safeMap(): Map<*, *> {
        return when (this) {
            is Map<*, *> -> this
            else -> emptyMap<Any?, Any?>()
        }
    }

    private fun Map<*, *>?.safeValue(key: String): Any? =
        runCatching { this?.get(key) }.getOrNull()

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
