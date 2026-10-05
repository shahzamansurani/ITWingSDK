package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig

internal fun AdPlacementConfig.adMobUnitOrNull(): AdUnitConfig? =
    units.firstOrNull {
        it.network.trim().equals("admob", ignoreCase = true) && it.adUnitId.isNotBlank()
    }?.let { it.copy(adUnitId = it.adUnitId.trim()) }

internal fun AdPlacementConfig.deliveryMode(): String {
    val raw = (sourceMode
        ?: metadata["source_mode"]?.toString()
        ?: metadata["delivery_mode"]?.toString()
        ?: metadata["source"]?.toString())
        ?.trim()
        ?.lowercase()
        .orEmpty()

    return when (raw) {
        "custom", "custom_ad", "custom_only" -> "custom_only"
        "admob", "admob_only", "google", "google_only" -> "admob_only"
        "disabled", "off", "none" -> "disabled"
        else -> "admob_first_custom_fallback"
    }
}

internal fun AdPlacementConfig.allowsCustomFallback(): Boolean =
    deliveryMode() != "admob_only" && deliveryMode() != "disabled"

internal fun AdPlacementConfig.shouldRenderCustomBeforeAdMob(): Boolean = when (deliveryMode()) {
    "custom_only" -> true
    "admob_only", "disabled" -> false
    else -> adMobUnitOrNull() == null
}
