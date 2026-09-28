package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig

/** A configured AdMob unit always gets first opportunity; custom is used only without one or after failure. */
internal fun AdPlacementConfig.adMobUnitOrNull(): AdUnitConfig? =
    units.firstOrNull {
        it.network.trim().equals("admob", ignoreCase = true) && it.adUnitId.isNotBlank()
    }?.let { unit -> unit.copy(adUnitId = unit.adUnitId.trim()) }

internal fun AdPlacementConfig.shouldRenderCustomBeforeAdMob(): Boolean = adMobUnitOrNull() == null

internal fun AdPlacementConfig.deliveryMode(hasCustomFallback: Boolean): String = when {
    adMobUnitOrNull() != null && hasCustomFallback -> "REAL_WITH_CUSTOM_FALLBACK"
    adMobUnitOrNull() != null -> "REAL_ONLY"
    hasCustomFallback -> "CUSTOM_ONLY"
    else -> "DISABLED"
}
