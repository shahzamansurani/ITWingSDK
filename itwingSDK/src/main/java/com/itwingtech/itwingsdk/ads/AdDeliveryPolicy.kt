package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig

internal fun AdPlacementConfig.adMobUnitOrNull(): AdUnitConfig? =
    units.firstOrNull {
        it.network.trim().equals("admob", ignoreCase = true) && it.adUnitId.isNotBlank()
    }?.let { it.copy(adUnitId = it.adUnitId.trim()) }

internal fun AdPlacementConfig.shouldRenderCustomBeforeAdMob(): Boolean = adMobUnitOrNull() == null
