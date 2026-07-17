package com.itwingtech.itwingsdk.ads

import android.os.SystemClock
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import java.util.concurrent.ConcurrentHashMap

internal object AdLoadBackoff {
    private val failures = ConcurrentHashMap<String, Failure>()

    fun canRequest(placement: AdPlacementConfig): Boolean {
        val failure = failures[placement.name] ?: return true
        if (SystemClock.elapsedRealtime() >= failure.untilElapsedMs) {
            failures.remove(placement.name)
            return true
        }
        AdEventTracker.log(
            "ad_load_throttled",
            placement,
            mapOf("cooldown_ms" to (failure.untilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)),
        )
        return false
    }

    fun recordFailure(placement: AdPlacementConfig, message: String?) {
        val seconds = when {
            message?.contains("no fill", ignoreCase = true) == true -> 120
            message?.contains("network", ignoreCase = true) == true -> 60
            else -> 45
        }
        failures[placement.name] = Failure(SystemClock.elapsedRealtime() + seconds * 1000L)
    }

    fun recordSuccess(placement: AdPlacementConfig) {
        failures.remove(placement.name)
    }

    private data class Failure(val untilElapsedMs: Long)
}
