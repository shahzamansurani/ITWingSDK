package com.itwingtech.itwingsdk.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class MediaDiskCache(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "itwing_sdk_media_cache")
    private val responses = File(root, "responses")
    private val files = File(root, "files")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    init {
        responses.mkdirs()
        files.mkdirs()
    }

    fun saveResponse(key: String, response: JSONObject) {
        runCatching {
            responseFile(key).writeText(response.toString())
        }
    }

    fun loadResponse(key: String): JSONObject? {
        return runCatching {
            val file = responseFile(key)
            if (!file.exists()) return null
            rewriteCachedUrls(JSONObject(file.readText()))
        }.getOrNull()
    }

    fun prefetchResponseMedia(response: JSONObject) {
        val urls = response.collectMediaUrls()
        if (urls.isEmpty()) return
        scope.launch {
            urls.distinct().take(MAX_PREFETCH_PER_RESPONSE).forEach { url ->
                prefetch(url)
            }
        }
    }

    fun cachedUri(url: String?): String? {
        if (url.isNullOrBlank() || !url.isHttpUrl()) return null
        val file = mediaFile(url)
        return if (file.exists() && file.length() > 0L) file.toURI().toString() else null
    }

    private fun prefetch(url: String) {
        if (!url.isHttpUrl()) return
        val target = mediaFile(url)
        if (target.exists() && target.length() > 0L) return

        val temp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                temp.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                if (temp.length() > 0L) {
                    temp.renameTo(target)
                } else {
                    temp.delete()
                }
            }
        }.onFailure {
            temp.delete()
        }
    }

    private fun rewriteCachedUrls(root: JSONObject): JSONObject {
        root.walkObjects { item ->
            MEDIA_URL_KEYS.forEach { key ->
                val current = item.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
                val cached = cachedUri(current)
                if (!cached.isNullOrBlank()) item.put(key, cached)
            }
        }
        return root
    }

    private fun JSONObject.collectMediaUrls(): List<String> {
        val urls = mutableListOf<String>()
        walkObjects { item ->
            MEDIA_URL_KEYS.forEach { key ->
                val value = item.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
                if (value != null && value.isHttpUrl()) urls.add(value)
            }
        }
        return urls
    }

    private fun JSONObject.walkObjects(block: (JSONObject) -> Unit) {
        block(this)
        keys().forEach { key ->
            when (val value = opt(key)) {
                is JSONObject -> value.walkObjects(block)
                is JSONArray -> value.walkObjects(block)
            }
        }
    }

    private fun JSONArray.walkObjects(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is JSONObject -> value.walkObjects(block)
                is JSONArray -> value.walkObjects(block)
            }
        }
    }

    private fun responseFile(key: String): File = File(responses, "${key.sha256()}.json")

    private fun mediaFile(url: String): File {
        val extension = url.substringBefore("?")
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf { it.length in 2..6 && it.all { char -> char.isLetterOrDigit() } }
            ?: "bin"
        return File(files, "${url.sha256()}.$extension")
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val MAX_PREFETCH_PER_RESPONSE = 80
        private val MEDIA_URL_KEYS = setOf(
            "image_url",
            "image",
            "media_url",
            "url",
            "thumbnail_url",
            "thumb_url",
            "thumbnail",
            "poster_url",
            "cover_url",
        )
    }
}
