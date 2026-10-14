package com.itwingtech.itwingsdk.ads

import android.content.Context
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class FrequencyController(
    private val contextProvider: () -> Context? = { null },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val shownInSession = ConcurrentHashMap<String, Int>()
    private val lastShownAt = ConcurrentHashMap<String, Long>()
    private val triggerCounts = ConcurrentHashMap<String, Int>()
    private val dailyState = ConcurrentHashMap<String, DailyState>()
    private val dailyStateLock = Any()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    init {
        persistenceScope.launch {
            val prefs = contextProvider()?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            preferences = prefs
            if (prefs != null) {
                val today = currentDay()
                prefs.all.forEach { (key, value) ->
                    if (!key.startsWith(KEY_PREFIX)) return@forEach
                    val placement = key.removePrefix(KEY_PREFIX)
                    val state = when (value) {
                        is String -> value.split('|', limit = 2).takeIf { it.size == 2 }
                            ?.let { DailyState(it[0], it[1].toIntOrNull()?.coerceAtLeast(0) ?: 0) }
                        is Int -> DailyState(today, value.coerceAtLeast(0))
                        else -> null
                    }
                    state?.let { dailyState[placement] = it }
                }
            }
        }
    }

    fun canShow(placement: AdPlacementConfig, countTrigger: Boolean = false): Boolean {
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

        val dailyCap = placement.dailyCap?.takeIf { it > 0 }
        val dailyKey = dailyFrequencyKey(placement)
        if (dailyCap != null && dailyCount(dailyKey) >= dailyCap) {
            return false
        }
        return true
    }

    fun markShown(placement: AdPlacementConfig) {
        val key = frequencyKey(placement)
        shownInSession[key] = (shownInSession[key] ?: 0) + 1
        lastShownAt[key] = System.currentTimeMillis()
        val dailyCap = placement.dailyCap?.takeIf { it > 0 } ?: return
        val dailyKey = dailyFrequencyKey(placement)
        val next = synchronized(dailyStateLock) {
            val today = currentDay()
            val previous = dailyState[dailyKey]?.takeIf { it.day == today }?.count ?: 0
            DailyState(today, (previous + 1).coerceAtMost(dailyCap))
                .also { dailyState[dailyKey] = it }
        }
        persistDailyCount(dailyKey, next)
    }

    fun refundTrigger(placement: AdPlacementConfig) {
        val key = frequencyKey(placement)
        val current = triggerCounts[key] ?: return
        triggerCounts[key] = (current - 1).coerceAtLeast(0)
    }

    private fun frequencyKey(placement: AdPlacementConfig): String {
        return when (placement.format.lowercase()) {
            "interstitial" -> "format:interstitial"
            else -> "placement:${placement.name}"
        }
    }

    private fun dailyFrequencyKey(placement: AdPlacementConfig): String =
        "placement:${placement.name}"

    private fun dailyCount(key: String): Int {
        val today = currentDay()
        synchronized(dailyStateLock) {
            val state = dailyState[key] ?: return 0
            if (state.day != today) {
                dailyState.remove(key)
                preferences?.edit()?.remove(KEY_PREFIX + key)?.apply()
                return 0
            }
            return state.count
        }
    }

    private fun persistDailyCount(key: String, state: DailyState) {
        persistenceScope.launch {
            preferences?.edit()?.putString(KEY_PREFIX + key, "${state.day}|${state.count}")?.apply()
        }
    }

    private fun currentDay(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowProvider()))

    private data class DailyState(val day: String, val count: Int)

    private companion object {
        const val PREFS_NAME = "itwing_ad_frequency"
        const val KEY_PREFIX = "daily_count:"
    }
}
