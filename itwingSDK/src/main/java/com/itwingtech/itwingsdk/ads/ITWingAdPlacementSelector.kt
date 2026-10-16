package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig

/**
 * Selects a placement without trusting the runtime element type of decoded or cached data.
 */
internal object ITWingAdPlacementSelector {
    fun select(entries: List<*>, placementName: String): AdPlacementConfig? =
        entries.asSequence()
            .mapNotNull { it as? AdPlacementConfig }
            .firstOrNull { it.name == placementName && it.enabled }
}
