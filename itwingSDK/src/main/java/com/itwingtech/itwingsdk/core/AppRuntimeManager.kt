package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.ads.FullscreenAdState
import com.itwingtech.itwingsdk.utils.safeCallback

internal class AppRuntimeManager(
    private val configProvider: () -> ITWingConfig,
    private val adManagerProvider: () -> AdManager?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showSplash(activity: Activity, onComplete: () -> Unit) {
        val config = runCatching { configProvider() }.getOrNull() ?: run {
            safeCallback(onComplete)
            return
        }
        val status = config.app["status"].safeString() ?: "active"
        val maintenance = config.app["maintenance"].safeBoolean(false)

        if (maintenance || status != "active") {
            showStateDialog(activity, status, onComplete)
            return
        }

        val delayMs = splashDelayMs(config)
        val splashDelayOwner = FullscreenAdState.tryBegin("sdk_splash", "delay")
        mainHandler.postDelayed({
            runCatching { showSplashAd(activity, config, splashDelayOwner, onComplete) }
                .onFailure {
                    FullscreenAdState.end(splashDelayOwner)
                    safeCallback(onComplete)
                }
        }, delayMs)
    }

    private fun showSplashAd(activity: Activity, config: ITWingConfig, splashDelayOwner: String?, onComplete: () -> Unit) {
        FullscreenAdState.end(splashDelayOwner)
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
                    (it.metadata["splash"].safeBoolean(false) ||
                        it.metadata["usage"].safeString().equals("splash", ignoreCase = true))
            }.getOrDefault(false)
        } ?: config.ads.placements.firstOrNull {
            it.enabled && it.format == format && it.name.contains("splash", ignoreCase = true)
        } ?: config.ads.placements.firstOrNull {
            it.enabled && it.format == format
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
        val splash = config.app["splash"].safeMap()
        val seconds = splash["seconds"].safeLong(2L)
        return seconds.coerceIn(0L, 10L) * 1000L
    }

    private fun splashFormat(config: ITWingConfig): String {
        val splash = config.app["splash"].safeMap()
        return splash["ad_format"].safeString() ?: "app_open"
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
        return when (val value = normalizedValue()) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun Any?.safeString(): String? = normalizedValue()?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Any?.safeMap(): Map<*, *> {
        return when (this) {
            is Map<*, *> -> this
            else -> emptyMap<Any?, Any?>()
        }
    }

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
