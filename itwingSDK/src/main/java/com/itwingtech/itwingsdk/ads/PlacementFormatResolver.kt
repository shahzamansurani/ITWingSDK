package com.itwingtech.itwingsdk.ads

/**
 * Resolves the only two inline formats that can be rendered by the universal
 * XML ad slot. The wire/config model still keeps all other formats (for
 * example interstitial) intact; this resolver is used only at an inline
 * presentation boundary.
 */
internal object PlacementFormatResolver {
    fun inlineFormat(value: Any?): String {
        val normalized = (value as? String)?.trim()?.lowercase()
        return if (normalized == "banner" || normalized == "native") normalized else "native"
    }

    fun safeTruthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.trim().lowercase() in setOf("1", "true", "yes", "on", "enabled")
        else -> false
    }
}
