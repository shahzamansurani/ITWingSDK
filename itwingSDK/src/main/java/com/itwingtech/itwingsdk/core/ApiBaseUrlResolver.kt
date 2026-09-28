package com.itwingtech.itwingsdk.core

/** Pure, context-free URL selection used by startup/DI callers. */
internal object ApiBaseUrlResolver {
    fun resolve(configured: String?, defaultValue: String): String {
        return normalize(configured)
            ?: normalize(defaultValue)
            ?: defaultValue
    }

    private fun normalize(value: String?): String? {
        val clean = value?.trim()?.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("undefined", ignoreCase = true)
        } ?: return null
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) return null
        return if (clean.endsWith('/')) clean else "$clean/"
    }
}
