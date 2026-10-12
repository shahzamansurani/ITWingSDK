package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

internal object AdEventTracker {
    private val providerRequestSequence = AtomicLong(0L)

    fun log(event: String, placement: AdPlacementConfig, extra: Map<String, Any?> = emptyMap()) {
        val explicitProvider = extra["provider"]?.toString()?.takeIf { it.isNotBlank() }
        val network = when {
            explicitProvider != null -> explicitProvider
            placement.customAd != null -> "custom"
            placement.units.any { it.network == "admob" } -> "admob"
            else -> "unknown"
        }
        val requestIdentity = if (event == "ad_load_requested") {
            mapOf(
                "provider_request_id" to "${SystemClock.elapsedRealtime()}-${providerRequestSequence.incrementAndGet()}",
            )
        } else {
            emptyMap()
        }

        SDKTelemetry.track(
            event,
            mapOf(
                "placement" to placement.name,
                "format" to placement.format,
                "network" to network,
                "custom_ad_id" to placement.customAd?.id,
                "campaign" to placement.customAd?.campaignGroup,
            ) + requestIdentity + extra,
        )
    }
}
