package com.itwingtech.itwingsdk.ads

import android.util.Log
import com.itwingtech.itwingsdk.core.AdPlacementConfig

/** Debug-only diagnostics for the real-first decision; never writes ad unit IDs or creative data. */
internal object AdPriorityTrace {
    private const val TAG = "ITWingAdPriority"

    fun decision(placement: AdPlacementConfig, hasFallback: Boolean) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val unit = placement.adMobUnitOrNull()
        Log.d(
            TAG,
            "Placement=${placement.name} Format=${placement.format} " +
                "Mode=${placement.deliveryMode(hasFallback)} AdMobUnitPresent=${unit != null} " +
                "CustomFallbackAvailable=$hasFallback",
        )
    }

    fun event(placement: AdPlacementConfig, event: String, detail: String? = null) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(TAG, "Placement=${placement.name} Format=${placement.format} $event${detail?.let { " reason=$it" }.orEmpty()}")
    }
}
