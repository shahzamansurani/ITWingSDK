package com.itwingtech.itwingsdk.analytics

import com.itwingtech.itwingsdk.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant


class AnalyticsClient(private val repository: ConfigRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var flushing = false

    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val event = JSONObject()
            .put("name", name)
            .put("event_at", Instant.now().toString())
            .put("properties", JSONObject(properties))

        repository.enqueueAnalyticsEvent(event)
        scheduleFlush()
    }

    fun flush() {
        scheduleFlush(immediate = true)
    }

    private fun scheduleFlush(immediate: Boolean = false) {
        if (flushing) return
        flushing = true
        scope.launch {
            if (!immediate) delay(1_000)
            runCatching { repository.flushAnalytics() }
            flushing = false
        }
    }
}
