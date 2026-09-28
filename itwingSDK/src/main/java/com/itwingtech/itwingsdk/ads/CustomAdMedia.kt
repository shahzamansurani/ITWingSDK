package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig

/** Resolves custom media consistently when backend payloads use either legacy or explicit video fields. */
internal fun CustomAdConfig.isVideoMedia(): Boolean {
    val declaredType = sequenceOf(
        mediaType,
        metadata["media_type"] as? String,
        metadata["mediaType"] as? String,
        metadata["mime_type"] as? String,
        metadata["mimeType"] as? String,
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotEmpty)

    if (declaredType?.startsWith("video", ignoreCase = true) == true) return true
    if (explicitVideoUrl() != null) return true

    val candidate = mediaUrl ?: imageUrl
    return candidate.isVideoFileUrl()
}

/** Selects the actual video source when one is explicitly supplied; otherwise preserves image precedence. */
internal fun CustomAdConfig.resolvedMediaUrl(): String? {
    val explicitVideo = isVideoMedia()
    if (explicitVideo) {
        // Preserve playable media_url values used by existing Small campaigns,
        // while still preferring video_url when media_url is a poster image.
        val mediaCandidates = sequenceOf(mediaUrl, imageUrl)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        val videoCandidates = sequenceOf(explicitVideoUrl(), videoUrl)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        val typedAsVideo = sequenceOf(
            mediaType,
            metadata["media_type"] as? String,
            metadata["mediaType"] as? String,
            metadata["mime_type"] as? String,
            metadata["mimeType"] as? String,
        ).filterNotNull().any { it.trim().startsWith("video", ignoreCase = true) }
        val typedMediaUrl = mediaUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.isImageFileUrl() }

        return when {
            mediaUrl.isVideoFileUrl() -> mediaUrl
            videoCandidates.firstOrNull(String::isVideoFileUrl) != null -> videoCandidates.first(String::isVideoFileUrl)
            typedAsVideo -> typedMediaUrl ?: videoCandidates.firstOrNull() ?: mediaCandidates.firstOrNull()
            else -> videoCandidates.firstOrNull() ?: mediaCandidates.firstOrNull()
        }
    }

    return sequenceOf(mediaUrl, imageUrl, videoUrl)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
}

private fun CustomAdConfig.explicitVideoUrl(): String? = sequenceOf(
    videoUrl,
    metadata["video_url"] as? String,
    metadata["videoUrl"] as? String,
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .firstOrNull()

private fun String?.isVideoFileUrl(): Boolean {
    if (isNullOrBlank()) return false
    val path = substringBefore('#').substringBefore('?').lowercase()
    return VIDEO_EXTENSIONS.any(path::endsWith)
}

private fun String?.isImageFileUrl(): Boolean {
    if (isNullOrBlank()) return false
    val path = substringBefore('#').substringBefore('?').lowercase()
    return IMAGE_EXTENSIONS.any(path::endsWith)
}

private val VIDEO_EXTENSIONS = setOf(
    ".mp4", ".m4v", ".webm", ".m3u8", ".mpd", ".3gp", ".mov", ".mkv"
)

private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
