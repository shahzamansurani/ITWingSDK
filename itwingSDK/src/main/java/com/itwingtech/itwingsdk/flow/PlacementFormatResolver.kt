package com.itwingtech.itwingsdk.flow

/**
 * Converts a remotely supplied placement format into one of the formats the
 * flow renderer actually supports.  This is deliberately tolerant because
 * placement values can cross a JSON/Bundle boundary before reaching the SDK.
 */
internal fun normalizePlacementFormat(value: Any?): String =
    value
        ?.toString()
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == "banner" || it == "native" }
        ?: "native"
