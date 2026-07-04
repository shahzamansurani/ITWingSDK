package com.itwingtech.itwingsdk.utils

internal object SensitiveDataSanitizer {
    private val urlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val sdkKeyPattern = Regex("""\bitw_(?:live|test)_[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)
    private val headerKeyPattern = Regex("""(?i)(X-ITW-Key|api[_-]?key|sdk[_-]?key)\s*[:=]\s*['"]?[^,'"\s}]+""")
    private val endpointPattern = Regex("""(?i)(endpoint|base[_-]?url)\s*[:=]\s*['"]?https?://[^,'"\s}]+""")

    fun sanitize(message: String?): String {
        if (message.isNullOrBlank()) return message.orEmpty()
        return message
            .replace(sdkKeyPattern, "[sdk-key hidden]")
            .replace(headerKeyPattern) { "${it.groupValues[1]}=[hidden]" }
            .replace(endpointPattern) { "${it.groupValues[1]}=[hidden]" }
            .replace(urlPattern, "[endpoint hidden]")
    }

    fun sanitizeMap(values: Map<String, Any?>): Map<String, Any?> {
        return values.mapValues { (_, value) ->
            when (value) {
                is String -> sanitize(value)
                is Map<*, *> -> value.entries.associate { entry ->
                    entry.key.toString() to sanitizeAny(entry.value)
                }
                is Iterable<*> -> value.map(::sanitizeAny)
                else -> value
            }
        }
    }

    private fun sanitizeAny(value: Any?): Any? = when (value) {
        is String -> sanitize(value)
        is Map<*, *> -> value.entries.associate { entry -> entry.key.toString() to sanitizeAny(entry.value) }
        is Iterable<*> -> value.map(::sanitizeAny)
        else -> value
    }
}
