package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.AdPlacementConfig

internal object AdEventTracker {
    fun log(event: String, placement: AdPlacementConfig, extra: Map<String, Any?> = emptyMap()) {
        val network = when {
            placement.customAd != null -> "custom"
            placement.units.any { it.network == "admob" } -> "admob"
            else -> "unknown"
        }

        SDKTelemetry.track(
            event,
            mapOf(
                "placement" to placement.name,
                "format" to placement.format,
                "network" to network,
                "custom_ad_id" to placement.customAd?.id,
                "campaign" to placement.customAd?.campaignGroup,
            ) + extra,
        )
    }
}
