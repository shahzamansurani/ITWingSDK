package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import java.util.concurrent.ConcurrentHashMap

class FrequencyController {
    private val shownInSession = ConcurrentHashMap<String, Int>()
    private val lastShownAt = ConcurrentHashMap<String, Long>()
    private val triggerCounts = ConcurrentHashMap<String, Int>()

    fun canShow(placement: AdPlacementConfig, countTrigger: Boolean = false, ): Boolean {
        if (!placement.enabled) {
            return false
        }

        if (countTrigger) {
            val interval = placement.triggerInterval?.coerceAtLeast(1) ?: 1
            val key = frequencyKey(placement)
            val currentCount = triggerCounts[key] ?: 0
            /*
             * First click always shows
             */
            val shouldShow = currentCount == 0 || currentCount % interval == 0

            /*
             * Increment AFTER evaluation
             */
            triggerCounts[key] = currentCount + 1
            if (!shouldShow) {
                return false
            }
        }

        val now = System.currentTimeMillis()

        val cooldown = placement.cooldownSeconds?.times(1000L) ?: 0L

        val key = frequencyKey(placement)
        val last = lastShownAt[key] ?: 0L
        if (cooldown > 0 && now - last < cooldown) {
            return false
        }

        val cap = placement.sessionCap

        if (cap != null && (shownInSession[key] ?: 0) >= cap) {
            return false
        }
        return true
    }

    fun markShown(placement: AdPlacementConfig) {
        val key = frequencyKey(placement)
        shownInSession[key] = (shownInSession[key] ?: 0) + 1
        lastShownAt[key] = System.currentTimeMillis()
    }

    private fun frequencyKey(placement: AdPlacementConfig): String {
        return when (placement.format.lowercase()) {
            "interstitial" -> "format:interstitial"
            else -> "placement:${placement.name}"
        }
    }
}
