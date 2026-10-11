package com.itwingtech.itwingsdk.ads

enum class BannerType {
    ADAPTIVE,
    COLLAPSIBLE_TOP,
    COLLAPSIBLE_BOTTOM,

    ;

    /** The value supported by Google's collapsible-banner request extra. */
    val collapsibleDirection: String?
        get() = when (this) {
            COLLAPSIBLE_TOP -> "top"
            COLLAPSIBLE_BOTTOM -> "bottom"
            ADAPTIVE -> null
        }

    companion object {
        /**
         * Converts administrator metadata without trusting arbitrary object
         * stringification. Unknown or malformed values use a safe adaptive
         * banner, while a separate collapsible_position can still provide the
         * direction for a generic "collapsible" banner type.
         */
        fun fromMetadata(metadata: Map<String, Any?>): BannerType {
            val bannerType = normalized(metadata["banner_type"])
            val position = normalized(metadata["collapsible_position"])

            return when (bannerType) {
                "top", "collapsible_top" -> COLLAPSIBLE_TOP
                "bottom", "collapsible_bottom" -> COLLAPSIBLE_BOTTOM
                "collapsible" -> fromDirection(position)
                "adaptive", "anchored_adaptive", "normal", "" -> ADAPTIVE
                null -> fromDirection(position)
                else -> ADAPTIVE
            }
        }

        private fun fromDirection(value: String?): BannerType = when (value) {
            "top", "collapsible_top" -> COLLAPSIBLE_TOP
            "bottom", "collapsible_bottom" -> COLLAPSIBLE_BOTTOM
            else -> ADAPTIVE
        }

        private fun normalized(value: Any?): String? = when (value) {
            is String -> value.trim().lowercase().takeIf { it.isNotEmpty() }
            is CharSequence -> value.toString().trim().lowercase().takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}
