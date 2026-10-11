package com.itwingtech.itwingsdk.realtime

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.itwingtech.itwingsdk.data.ConfigRepository
import com.itwingtech.itwingsdk.data.RealtimeRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Firebase-style app-scoped JSON tree backed by IT Wing's signed realtime API. */
class ITWingRealtime internal constructor(
    private val repository: () -> ConfigRepository?,
    private val context: () -> Context?,
) {
    private val gson = Gson()

    /** Start a typed, fluent reference to one app-scoped database (legacy namespace). */
    fun database(name: String): ITWingRealtimeReference {
        validate(name, "")
        return ITWingRealtimeReference(this, name.lowercase(), "")
    }

    suspend fun get(namespace: String, path: String = ""): RealtimeSnapshot {
        val data = request(namespace, path, "GET") ?: JSONObject()
        return data.toSnapshot(namespace, path)
    }

    suspend fun <T> get(namespace: String, path: String = "", type: Class<T>): T? {
        val value = get(namespace, path).value
        return if (value == null || value is JsonNull) null else gson.fromJson(value, type)
    }

    internal suspend fun getCached(namespace: String, path: String, fallbackToCacheOnError: Boolean): RealtimeSnapshot {
        validate(namespace, path)
        val key = cacheKey(namespace, path)
        try {
            val data = request(namespace, path, "GET") ?: JSONObject()
            runCatching {
                withContext(Dispatchers.IO) {
                    contextOrThrow().getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putString(key, data.toString()).apply()
                }
            }
            return data.toSnapshot(namespace, path)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!fallbackToCacheOnError) throw error
            val cached = withContext(Dispatchers.IO) {
                contextOrThrow().getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
                    .getString(key, null)
            } ?: throw error
            return runCatching { JSONObject(cached).toSnapshot(namespace, path).copy(isFromCache = true) }
                .getOrElse { throw error }
        }
    }

    suspend fun set(namespace: String, path: String, value: JsonElement, expectedVersion: Long? = null): RealtimeSnapshot {
        val data = request(namespace, path, "PUT", value, expectedVersion) ?: JSONObject()
        return data.toSnapshot(namespace, path)
    }

    suspend fun update(namespace: String, path: String, changes: JsonObject, expectedVersion: Long? = null): RealtimeSnapshot {
        val data = request(namespace, path, "PATCH", changes, expectedVersion) ?: JSONObject()
        return data.toSnapshot(namespace, path)
    }

    suspend fun delete(namespace: String, path: String, expectedVersion: Long? = null) {
        request(namespace, path, "DELETE", null, expectedVersion)
    }

    suspend fun push(namespace: String, path: String, value: JsonElement): RealtimeSnapshot {
        val data = request(namespace, path, "POST", value) ?: JSONObject()
        return data.toSnapshot(namespace, path)
    }

    internal fun encode(value: Any?): JsonElement = gson.toJsonTree(value)

    internal fun <T> decode(value: JsonElement?, type: Class<T>): T? =
        if (value == null || value is JsonNull) null else gson.fromJson(value, type)

    internal fun validateChild(segment: String) {
        require(segment.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) && segment != "..") {
            "Invalid realtime child name. Use one non-empty path segment."
        }
    }

    /** Conditional version sync while the host app is foregrounded; cancellation stops observation. */
    fun observe(namespace: String, path: String = "", pollIntervalMs: Long = 30_000): Flow<JsonElement?> = flow {
        validate(namespace, path)
        var version: Long? = null
        val interval = pollIntervalMs.coerceIn(15_000, 300_000)
        while (currentCoroutineContext().isActive) {
            if (!isForeground()) {
                delay(1_000)
                continue
            }
            val subscription = JSONObject()
                .put("namespace", namespace.lowercase())
                .put("path", path)
                .apply { version?.let { put("version", it) } }
            val data = repositoryOrThrow().realtimeRequest(
                "/realtime/_sync", "POST", JSONObject().put("subscriptions", JSONArray().put(subscription)).toString(),
            )
            val result = data?.optJSONArray("subscriptions")?.optJSONObject(0)
                ?: throw IllegalStateException("Realtime sync response was incomplete.")
            when (result.optString("status")) {
                "changed" -> {
                    version = result.optLong("version")
                    emit(result.opt("value").asJsonElement())
                }
                "unchanged" -> version = result.optLong("version")
                "forbidden" -> throw RealtimeRequestException(403, "This realtime path is not available to the SDK.")
                "missing" -> if (version != 0L) {
                    version = 0L
                    emit(null)
                }
                else -> throw IllegalStateException("Realtime sync returned ${result.optString("status") ?: "an unknown state"}.")
            }
            delay(interval)
        }
    }

    private suspend fun request(
        namespace: String,
        path: String,
        method: String,
        payload: JsonElement? = null,
        expectedVersion: Long? = null,
    ): JSONObject? {
        validate(namespace, path)
        val safeNamespace = namespace.lowercase()
        val safePath = path.trim('/')
        val suffix = if (safePath.isEmpty()) "" else "/" + safePath.split('/').joinToString("/") { it }
        val headers = expectedVersion?.let { mapOf("If-Match" to it.toString()) } ?: emptyMap()
        return repositoryOrThrow().realtimeRequest("/realtime/$safeNamespace$suffix", method, payload?.toString(), headers)
    }

    private fun repositoryOrThrow(): ConfigRepository = repository()
        ?: throw IllegalStateException("ITWingSDK.initialize must be called before using realtime data.")

    private fun contextOrThrow(): Context = context()?.applicationContext
        ?: throw IllegalStateException("ITWingSDK.initialize must be called before using realtime snapshot caching.")

    private fun cacheKey(namespace: String, path: String): String {
        val source = "${namespace.lowercase()}/${path.trim('/')}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(source)
        return CACHE_KEY_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun isForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(true)

    private fun validate(namespace: String, path: String) {
        require(namespace.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "Invalid realtime namespace." }
        require(path.isEmpty() || path.trim('/').matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,254}"))) { "Invalid realtime path." }
        require(!path.contains("..") && path.trim('/').split('/').size <= 12) { "Invalid realtime path." }
    }

    private fun JSONObject.toSnapshot(namespace: String, path: String) = RealtimeSnapshot(
        namespace = optString("namespace", namespace),
        path = optString("path", path),
        value = opt("value").asJsonElement(),
        version = optLong("version"),
        updatedAt = optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
        children = optJSONArray("children")?.let { nodes ->
            (0 until nodes.length()).mapNotNull { index ->
                nodes.optJSONObject(index)?.let { node ->
                    RealtimeChildSnapshot(
                        name = node.optString("name"), path = node.optString("path"), type = node.optString("type"),
                        value = node.opt("value").asJsonElement(), hasChildren = node.optBoolean("has_children"),
                        version = node.optLong("version"),
                    )
                }
            }
        } ?: emptyList(),
    )

    private fun Any?.asJsonElement(): JsonElement? = when {
        this == null || this === JSONObject.NULL -> null
        this is JSONObject || this is JSONArray -> JsonParser.parseString(toString())
        else -> gson.toJsonTree(this)
    }

    private companion object {
        const val CACHE_PREFERENCES = "itwing_realtime_snapshot_cache_v1"
        const val CACHE_KEY_PREFIX = "snapshot_"
    }
}

/** A reusable database/node reference. Host apps never handle URLs, signing, or JSON transport. */
class ITWingRealtimeReference internal constructor(
    private val realtime: ITWingRealtime,
    private val namespace: String,
    private val path: String,
) {
    fun child(name: String): ITWingRealtimeReference {
        realtime.validateChild(name)
        return ITWingRealtimeReference(realtime, namespace, listOf(path, name).filter { it.isNotEmpty() }.joinToString("/"))
    }

    suspend fun getSnapshot(): RealtimeSnapshot = realtime.get(namespace, path)

    /** Fetches the current server snapshot, falling back to the last successful SDK cache if offline. */
    suspend fun getSnapshotCached(fallbackToCacheOnError: Boolean = true): RealtimeSnapshot =
        realtime.getCached(namespace, path, fallbackToCacheOnError)

    suspend fun <T> get(type: Class<T>): T? = realtime.get(namespace, path, type)

    suspend inline fun <reified T> get(): T? = get(T::class.java)

    suspend fun set(value: Any?, expectedVersion: Long? = null): RealtimeSnapshot =
        realtime.set(namespace, path, realtime.encode(value), expectedVersion)

    suspend fun update(changes: Map<String, Any?>, expectedVersion: Long? = null): RealtimeSnapshot =
        realtime.update(namespace, path, realtime.encode(changes).asJsonObject, expectedVersion)

    suspend fun remove(expectedVersion: Long? = null) = realtime.delete(namespace, path, expectedVersion)

    suspend fun delete(expectedVersion: Long? = null) = remove(expectedVersion)

    suspend fun push(value: Any?): RealtimeSnapshot = realtime.push(namespace, path, realtime.encode(value))

    fun observe(pollIntervalMs: Long = 30_000): Flow<JsonElement?> = realtime.observe(namespace, path, pollIntervalMs)

    fun <T> observe(type: Class<T>, pollIntervalMs: Long = 30_000): Flow<T?> =
        realtime.observe(namespace, path, pollIntervalMs).map { realtime.decode(it, type) }

    @JvmName("observeTyped")
    inline fun <reified T> observe(pollIntervalMs: Long = 30_000): Flow<T?> = observe(T::class.java, pollIntervalMs)
}

data class RealtimeSnapshot(
    val namespace: String,
    val path: String,
    val value: JsonElement?,
    val version: Long,
    val updatedAt: String?,
    /** Direct descendants, independent of the parent's own typed value. */
    val children: List<RealtimeChildSnapshot> = emptyList(),
    /** True only when this result came from the SDK's local fallback cache. */
    val isFromCache: Boolean = false,
)

data class RealtimeChildSnapshot(
    val name: String,
    val path: String,
    val type: String,
    val value: JsonElement?,
    val hasChildren: Boolean,
    val version: Long,
)
