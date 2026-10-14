package com.itwingtech.itwingsdk.ads

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure request-shaping helpers kept separate so width and direction regressions are testable. */
internal object BannerRequestSizing {
    fun widthDp(innerWidthPx: Int, density: Float): Int? {
        if (innerWidthPx <= 0 || density <= 0f) return null
        return max(1, (innerWidthPx / density).roundToInt())
    }

    fun collapsibleDirection(type: BannerType): String? = when (type) {
        BannerType.COLLAPSIBLE_TOP -> "top"
        BannerType.COLLAPSIBLE_BOTTOM -> "bottom"
        BannerType.ADAPTIVE -> null
    }
}
