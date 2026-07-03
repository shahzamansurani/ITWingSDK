package com.itwingtech.itwingsdk.wallpapers

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ITWingWallpaperCategory(
    val id: String,
    val name: String,
    val slug: String,
    val description: String?,
    val imageUrl: String?,
    val sortOrder: Int,
)

data class ITWingWallpaperStats(
    val clicks: Long = 0,
    val views: Long = 0,
    val downloads: Long = 0,
    val sets: Long = 0,
)

data class ITWingWallpaperItem(
    val id: String,
    val categoryId: String?,
    val categorySlug: String?,
    val title: String,
    val slug: String,
    val imageUrl: String,
    val thumbnailUrl: String?,
    val tags: List<String>,
    val featured: Boolean,
    val premium: Boolean,
    val sortOrder: Int,
    val stats: ITWingWallpaperStats,
)

data class ITWingWallpaperResponse(
    val enabled: Boolean,
    val topLimit: Int,
    val defaultSort: String,
    val categories: List<ITWingWallpaperCategory>,
    val wallpapers: List<ITWingWallpaperItem>,
    val trending: List<ITWingWallpaperItem>,
)

abstract class ITWingWallpapersCallback {
    open fun onLoaded(response: ITWingWallpaperResponse) = Unit
    open fun onError(error: String) = Unit
}

internal fun JSONObject.toWallpaperResponse(): ITWingWallpaperResponse {
    val data = optJSONObject("data") ?: this
    val settings = data.optJSONObject("settings")
    return ITWingWallpaperResponse(
        enabled = settings?.optBoolean("enabled", false) ?: false,
        topLimit = settings?.optInt("top_limit", 10) ?: 10,
        defaultSort = settings?.optString("default_sort", "trending") ?: "trending",
        categories = data.optJSONArray("categories").toCategoryList(),
        wallpapers = data.optJSONArray("wallpapers").toWallpaperList(),
        trending = data.optJSONArray("trending").toWallpaperList(),
    )
}

private fun JSONArray?.toCategoryList(): List<ITWingWallpaperCategory> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        ITWingWallpaperCategory(
            id = item.optString("id"),
            name = item.optString("name"),
            slug = item.optString("slug"),
            description = item.optString("description").takeIf { it.isNotBlank() },
            imageUrl = item.optCleanUrl("image_url")
                ?: item.optCleanUrl("image")
                ?: item.optCleanUrl("url"),
            sortOrder = item.optInt("sort_order", 100),
        )
    }
}

private fun JSONArray?.toWallpaperList(): List<ITWingWallpaperItem> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        val imageUrl = item.optCleanUrl("image_url")
            ?: item.optCleanUrl("image")
            ?: item.optCleanUrl("media_url")
            ?: item.optCleanUrl("url")
            ?: return@mapNotNull null
        val stats = item.optJSONObject("stats")
        ITWingWallpaperItem(
            id = item.optString("id"),
            categoryId = item.optString("category_id").takeIf { it.isNotBlank() },
            categorySlug = item.optString("category_slug").takeIf { it.isNotBlank() },
            title = item.optString("title"),
            slug = item.optString("slug"),
            imageUrl = imageUrl,
            thumbnailUrl = item.optCleanUrl("thumbnail_url")
                ?: item.optCleanUrl("thumb_url")
                ?: item.optCleanUrl("thumbnail"),
            tags = item.optJSONArray("tags").toStringList(),
            featured = item.optBoolean("is_featured", false),
            premium = item.optBoolean("is_premium", false),
            sortOrder = item.optInt("sort_order", 100),
            stats = ITWingWallpaperStats(
                clicks = stats?.optLong("clicks", 0) ?: 0,
                views = stats?.optLong("views", 0) ?: 0,
                downloads = stats?.optLong("downloads", 0) ?: 0,
                sets = stats?.optLong("sets", 0) ?: 0,
            ),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
}

private fun JSONObject.optCleanUrl(key: String): String? {
    val raw = optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        ?: return null
    return raw.toUsableMediaUrl()
}

internal fun String.toUsableMediaUrl(): String {
    val trimmed = trim().replace("\\", "/")
    val absolute = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/uploads/") -> "https://sdk.itwingtech.com$trimmed"
        trimmed.startsWith("uploads/") -> "https://sdk.itwingtech.com/$trimmed"
        trimmed.startsWith("/storage/") -> "https://sdk.itwingtech.com$trimmed"
        trimmed.startsWith("storage/") -> "https://sdk.itwingtech.com/$trimmed"
        else -> trimmed
    }
    return absolute.encodeUrlSpacesOnly()
}

private fun String.encodeUrlSpacesOnly(): String {
    if (!contains(' ')) return this
    val queryIndex = indexOf('?')
    val path = if (queryIndex >= 0) substring(0, queryIndex) else this
    val query = if (queryIndex >= 0) substring(queryIndex) else ""
    return path.split('/').joinToString("/") { segment ->
        if (segment.contains(' ')) URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20") else segment
    } + query
}
