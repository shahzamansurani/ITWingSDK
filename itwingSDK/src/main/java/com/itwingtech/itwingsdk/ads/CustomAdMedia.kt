package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig

private val videoExtensions = setOf(".mp4", ".m4v", ".webm", ".m3u8", ".mpd", ".3gp", ".mov", ".mkv")

internal fun CustomAdConfig.resolvedMediaUrl(): String? =
    sequenceOf(mediaUrl, videoUrl, imageUrl).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

internal fun CustomAdConfig.isVideoMedia(): Boolean {
    if (mediaType?.trim()?.equals("video", ignoreCase = true) == true) return true
    if (!videoUrl.isNullOrBlank() && mediaUrl?.trim() == videoUrl.trim()) return true
    return resolvedMediaUrl()?.substringBefore('?')?.lowercase()?.let { url -> videoExtensions.any(url::endsWith) } == true
}
