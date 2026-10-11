package com.itwingtech.itwingsdk.ads

enum class BannerType {
    ADAPTIVE,
    COLLAPSIBLE_TOP,
    COLLAPSIBLE_BOTTOM,

    ;

    companion object {
        internal fun fromMetadata(metadata: Map<String, Any?>): BannerType {
            val bannerType = metadata["banner_type"]
                ?.toString()
                ?.trim()
                ?.lowercase()
            val position = metadata["collapsible_position"]
                ?.toString()
                ?.trim()
                ?.lowercase()
            val value = when {
                bannerType.isNullOrEmpty() || bannerType == "collapsible" -> position
                else -> bannerType
            }

            return when (value) {
                "top", "collapsible_top" -> COLLAPSIBLE_TOP
                "bottom", "collapsible_bottom" -> COLLAPSIBLE_BOTTOM
                else -> ADAPTIVE
            }
        }
    }
}
