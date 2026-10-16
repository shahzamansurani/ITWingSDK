package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ITWingAdViewSafetyTest {
    @Test
    fun selectorIgnoresForeignRuntimeEntries() {
        val placement = placement(name = "home", format = "native")

        assertEquals(
            placement,
            ITWingAdPlacementSelector.select(listOf("not-a-placement", 7, placement), "home"),
        )
    }

    @Test
    fun selectorIgnoresDisabledAndMissingPlacements() {
        val disabled = placement(name = "home", format = "native", enabled = false)

        assertNull(ITWingAdPlacementSelector.select(listOf(disabled), "home"))
        assertNull(ITWingAdPlacementSelector.select(listOf(Any()), "missing"))
    }

    @Test
    fun selectorSafelyHandlesEmptyAndMalformedLists() {
        assertNull(ITWingAdPlacementSelector.select(emptyList<Any?>(), "home"))
        assertNull(ITWingAdPlacementSelector.select(listOf(null, emptyMap<String, Any?>(), listOf(1)), "home"))
    }

    private fun placement(
        name: String,
        format: String,
        enabled: Boolean = true,
    ) = AdPlacementConfig(
        name = name,
        format = format,
        enabled = enabled,
        testMode = true,
        metadata = mapOf(
            "banner_type" to true,
            "collapsible_position" to emptyMap<String, Any?>(),
        ),
    )
}
