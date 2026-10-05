package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig

/** Resolves a campaign for an AdMob failure without changing the configured delivery mode. */
internal fun ITWingConfig.customFallbackFor(placement: AdPlacementConfig): CustomAdConfig? {
    if (!placement.allowsCustomFallback()) return null

    val requested = placement.format.lowercase()
    fun CustomAdConfig.hasCreative(): Boolean = !resolvedMediaUrl().isNullOrBlank() || !html.isNullOrBlank()
    fun CustomAdConfig.matchesFormat(): Boolean {
        val format = this.format.lowercase()
        return format == requested || when (requested) {
            "native" -> format == "image" || format == "html"
            "banner" -> format == "image" || format == "html"
            "rewarded_interstitial" -> format == "interstitial"
            else -> false
        }
    }
    placement.customAd
        ?.takeIf { it.hasCreative() && it.matchesFormat() }
        ?.let { return it }

    val compatible = ads.customAds.filter { ad ->
        ad.hasCreative() && ad.matchesFormat()
    }
    fun CustomAdConfig.targetsPlacement(): Boolean {
        val raw = metadata["placement_names"] ?: metadata["placements"] ?: return false
        return when (raw) {
            is Collection<*> -> raw.any { it?.toString()?.equals(placement.name, true) == true }
            is String -> raw.split(',').any { it.trim().equals(placement.name, true) }
            else -> false
        }
    }
    return compatible.filter { it.targetsPlacement() }
        .minWithOrNull(compareBy<CustomAdConfig>({ it.format.lowercase() != requested }, { it.priority }))
        ?: compatible.filterNot { it.metadata.containsKey("placement_names") || it.metadata.containsKey("placements") }
            .minWithOrNull(compareBy<CustomAdConfig>({ it.format.lowercase() != requested }, { it.priority }))
        ?: compatible.minWithOrNull(compareBy<CustomAdConfig>({ it.format.lowercase() != requested }, { it.priority }))
}

internal fun ITWingConfig.placementWithCustomFallback(placement: AdPlacementConfig): AdPlacementConfig? =
    customFallbackFor(placement)?.let { placement.copy(customAd = it) }
