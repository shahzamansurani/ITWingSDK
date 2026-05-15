package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.utils.safeCallback

internal class AppRuntimeManager(
    private val configProvider: () -> ITWingConfig,
    private val adManagerProvider: () -> AdManager?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showSplash(activity: Activity, onComplete: () -> Unit) {
        val config = configProvider()
        val status = config.app["status"] as? String ?: "active"
        val maintenance = config.app["maintenance"] as? Boolean ?: false

        if (maintenance || status != "active") {
            showStateDialog(activity, status, onComplete)
            return
        }

        val delayMs = splashDelayMs(config)
        mainHandler.postDelayed({
            showSplashAd(activity, config, onComplete)
        }, delayMs)
    }

    private fun showSplashAd(activity: Activity, config: ITWingConfig, onComplete: () -> Unit) {
        val ads = adManagerProvider()
        if (ads == null || !config.ads.globalEnabled) {
            safeCallback(onComplete)
            return
        }

        val format = splashFormat(config)
        val placement = config.ads.placements.firstOrNull {
            it.enabled && it.format == format && (it.metadata["splash"].isTruthy() || it.metadata["usage"] == "splash")
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
            "rewarded" -> ads.showRewarded(activity, placement.name, onComplete = onComplete)
            "rewarded_interstitial" -> ads.showRewardedInterstitial(activity, placement.name, onComplete = onComplete)
            else -> safeCallback(onComplete)
        }
    }

    private fun showStateDialog(activity: Activity, status: String, onComplete: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            safeCallback(onComplete)
            return
        }

        val title = configProvider().app["title"] as? String ?: configProvider().app["name"] as? String ?: "App"
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
        val splash = config.app["splash"] as? Map<*, *>
        val seconds = splash?.get("seconds") as? Number
        return ((seconds?.toLong() ?: 2L).coerceIn(0L, 10L)) * 1000L
    }

    private fun splashFormat(config: ITWingConfig): String {
        val splash = config.app["splash"] as? Map<*, *>
        return splash?.get("ad_format") as? String ?: "app_open"
    }

    private fun Any?.isTruthy(): Boolean {
        return when (this) {
            null -> false
            is Boolean -> this
            is String -> equals("true", ignoreCase = true) || this == "1"
            is Number -> toInt() != 0
            else -> false
        }
    }
}
