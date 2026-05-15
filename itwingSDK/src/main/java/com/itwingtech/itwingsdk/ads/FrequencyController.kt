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
            val currentCount = triggerCounts[placement.name] ?: 0
            /*
             * First click always shows
             */
            val shouldShow = currentCount == 0 || currentCount % interval == 0

            /*
             * Increment AFTER evaluation
             */
            triggerCounts[placement.name] = currentCount + 1
            if (!shouldShow) {
                return false
            }
        }

        val now = System.currentTimeMillis()

        val cooldown = placement.cooldownSeconds?.times(1000L) ?: 0L

        val last = lastShownAt[placement.name] ?: 0L
        if (cooldown > 0 && now - last < cooldown) {
            return false
        }

        val cap = placement.sessionCap

        if (cap != null && (shownInSession[placement.name] ?: 0) >= cap) {
            return false
        }
        return true
    }

    fun markShown(placement: AdPlacementConfig) {
        shownInSession[placement.name] = (shownInSession[placement.name] ?: 0) + 1
        lastShownAt[placement.name] = System.currentTimeMillis()
    }
}
