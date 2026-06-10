package com.itwingtech.itwingsdk.analytics

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.itwingtech.itwingsdk.core.FirebaseRuntimeManager
import com.itwingtech.itwingsdk.data.ConfigRepository
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal object SDKTelemetry {
    private const val MAIN_THREAD_STALL_MS = 5_000L
    private const val MAIN_THREAD_STALL_RATE_LIMIT_MS = 60_000L

    private val installed = AtomicBoolean(false)
    private val watchdogStarted = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var analyticsProvider: (() -> AnalyticsClient?)? = null
    @Volatile
    private var repositoryProvider: (() -> ConfigRepository?)? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var lastMainResponseAt = System.currentTimeMillis()
    @Volatile
    private var lastStallReportedAt = 0L

    fun configure(
        context: Context,
        analyticsProvider: () -> AnalyticsClient?,
        repositoryProvider: () -> ConfigRepository?,
    ) {
        appContext = context.applicationContext
        this.analyticsProvider = analyticsProvider
        this.repositoryProvider = repositoryProvider
        installCrashHandler()
        startMainThreadWatchdog()
    }

    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val enriched = defaultProperties() + properties
        val analytics = analyticsProvider?.invoke()
        if (analytics != null) {
            runCatching { analytics.track(name, enriched) }
            return
        }

        runCatching { FirebaseRuntimeManager.logEvent(name, enriched) }
        enqueueDirect(name, enriched)
    }

    fun recordNonFatal(throwable: Throwable, properties: Map<String, Any?> = emptyMap()) {
        val enriched = defaultProperties() + properties + throwableProperties(throwable)
        track("sdk_non_fatal_error", enriched)
        FirebaseRuntimeManager.recordNonFatal(throwable, enriched)
    }

    private fun installCrashHandler() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val properties = defaultProperties() + throwableProperties(throwable) + mapOf(
                "thread" to thread.name,
                "fatal" to true,
                "sdk_in_stack" to throwable.stackTraceToString().contains("com.itwingtech.itwingsdk"),
            )
            enqueueDirect("app_crash", properties)
            runCatching { FirebaseRuntimeManager.recordNonFatal(throwable, properties) }
            previous?.uncaughtException(thread, throwable)
                ?: run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

    private fun startMainThreadWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        fun pingMainThread() {
            lastMainResponseAt = System.currentTimeMillis()
            mainHandler.postDelayed({ pingMainThread() }, 1_000L)
        }
        mainHandler.post { pingMainThread() }

        Thread({
            while (true) {
                runCatching { Thread.sleep(2_000L) }
                val now = System.currentTimeMillis()
                val stalledFor = now - lastMainResponseAt
                if (
                    stalledFor >= MAIN_THREAD_STALL_MS &&
                    now - lastStallReportedAt >= MAIN_THREAD_STALL_RATE_LIMIT_MS
                ) {
                    lastStallReportedAt = now
                    val properties = mapOf("stalled_ms" to stalledFor)
                    track("main_thread_stall", properties)
                    FirebaseRuntimeManager.recordNonFatal(
                        IllegalStateException("Main thread did not respond for ${stalledFor}ms"),
                        defaultProperties() + properties,
                    )
                }
            }
        }, "ITWingSDK-MainThreadWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun enqueueDirect(name: String, properties: Map<String, Any?>) {
        val repo = repositoryProvider?.invoke() ?: return
        runCatching {
            repo.enqueueAnalyticsEvent(
                JSONObject()
                    .put("name", name)
                    .put("event_at", Instant.now().toString())
                    .put("properties", JSONObject(properties.mapValues { safeJsonValue(it.value) })),
            )
        }
    }

    private fun defaultProperties(): Map<String, Any?> {
        val context = appContext ?: return emptyMap()
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return mapOf(
            "package_name" to context.packageName,
            "app_version" to versionName,
            "sdk_version" to "1.0.0",
            "platform" to "android",
        )
    }

    private fun throwableProperties(throwable: Throwable): Map<String, Any?> {
        return mapOf(
            "exception" to throwable.javaClass.name,
            "message" to (throwable.message ?: throwable.cause?.message ?: "unknown").take(200),
        )
    }

    private fun safeJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Boolean, is Number, is String -> value
            is Iterable<*> -> value.joinToString(",") { it?.toString().orEmpty() }.take(500)
            is Array<*> -> value.joinToString(",") { it?.toString().orEmpty() }.take(500)
            is Map<*, *> -> JSONObject(value.mapKeys { it.key?.toString().orEmpty() }.mapValues { safeJsonValue(it.value) })
            else -> value.toString().take(500)
        }
    }
}
