package com.itwingtech.itwingsdk.media

import org.json.JSONArray
import org.json.JSONObject

data class ITWingMediaCategory(
    val id: String,
    val name: String,
    val slug: String?,
    val description: String?,
    val imageUrl: String?,
    val sortOrder: Int,
)

data class ITWingMediaStats(
    val clicks: Long = 0,
    val views: Long = 0,
    val plays: Long = 0,
    val downloads: Long = 0,
    val sets: Long = 0,
)

data class ITWingMediaItem(
    val id: String,
    val categoryId: String?,
    val categorySlug: String?,
    val title: String,
    val slug: String?,
    val mediaUrl: String,
    val thumbnailUrl: String?,
    val mimeType: String?,
    val durationMs: Int?,
    val tags: List<String>,
    val isFeatured: Boolean,
    val isPremium: Boolean,
    val sortOrder: Int,
    val stats: ITWingMediaStats,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ITWingMediaResponse(
    val enabled: Boolean,
    val topLimit: Int,
    val defaultSort: String,
    val categories: List<ITWingMediaCategory>,
    val items: List<ITWingMediaItem>,
    val trending: List<ITWingMediaItem>,
)

abstract class ITWingMediaCallback {
    open fun onLoaded(response: ITWingMediaResponse) = Unit
    open fun onError(error: String) = Unit
}

internal fun JSONObject.toMediaResponse(): ITWingMediaResponse {
    val data = optJSONObject("data") ?: this
    val settings = data.optJSONObject("settings")
    return ITWingMediaResponse(
        enabled = settings?.optBoolean("enabled", false) ?: false,
        topLimit = settings?.optInt("top_limit", 10) ?: 10,
        defaultSort = settings?.optString("default_sort", "trending") ?: "trending",
        categories = data.optJSONArray("categories").toCategoryList(),
        items = data.optJSONArray("items").toItemList(),
        trending = data.optJSONArray("trending").toItemList(),
    )
}

private fun JSONArray?.toCategoryList(): List<ITWingMediaCategory> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        ITWingMediaCategory(
            id = item.optString("id"),
            name = item.optString("name"),
            slug = item.optString("slug").takeIf(String::isNotBlank),
            description = item.optString("description").takeIf(String::isNotBlank),
            imageUrl = item.optString("image_url").takeIf(String::isNotBlank),
            sortOrder = item.optInt("sort_order", 100),
        )
    }
}

private fun JSONArray?.toItemList(): List<ITWingMediaItem> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        val stats = item.optJSONObject("stats")
        ITWingMediaItem(
            id = item.optString("id"),
            categoryId = item.optString("category_id").takeIf(String::isNotBlank),
            categorySlug = item.optString("category_slug").takeIf(String::isNotBlank),
            title = item.optString("title"),
            slug = item.optString("slug").takeIf(String::isNotBlank),
            mediaUrl = item.optString("media_url"),
            thumbnailUrl = item.optString("thumbnail_url").takeIf(String::isNotBlank),
            mimeType = item.optString("mime_type").takeIf(String::isNotBlank),
            durationMs = if (item.has("duration_ms") && !item.isNull("duration_ms")) item.optInt("duration_ms") else null,
            tags = item.optJSONArray("tags")?.let { tags -> (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) } } ?: emptyList(),
            isFeatured = item.optBoolean("is_featured", false),
            isPremium = item.optBoolean("is_premium", false),
            sortOrder = item.optInt("sort_order", 100),
            stats = ITWingMediaStats(
                clicks = stats?.optLong("clicks", 0) ?: 0,
                views = stats?.optLong("views", 0) ?: 0,
                plays = stats?.optLong("plays", 0) ?: 0,
                downloads = stats?.optLong("downloads", 0) ?: 0,
                sets = stats?.optLong("sets", 0) ?: 0,
            ),
            metadata = item.optJSONObject("metadata")?.toMap() ?: emptyMap(),
        )
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    keys().forEach { key ->
        result[key] = when (val value = opt(key)) {
            is JSONObject -> value.toMap()
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            JSONObject.NULL -> null
            else -> value
        }
    }
    return result
}
