package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerTypeTest {
    @Test
    fun supportedDirectionsMapToGoogleExtras() {
        assertEquals("top", BannerType.COLLAPSIBLE_TOP.collapsibleDirection)
        assertEquals("bottom", BannerType.COLLAPSIBLE_BOTTOM.collapsibleDirection)
        assertEquals(null, BannerType.ADAPTIVE.collapsibleDirection)
    }

    @Test
    fun metadataSupportsExplicitTypeAndSeparatePosition() {
        assertEquals(
            BannerType.COLLAPSIBLE_TOP,
            BannerType.fromMetadata(mapOf("banner_type" to "collapsible_top")),
        )
        assertEquals(
            BannerType.COLLAPSIBLE_BOTTOM,
            BannerType.fromMetadata(
                mapOf("banner_type" to "collapsible", "collapsible_position" to "bottom"),
            ),
        )
        assertEquals(
            BannerType.COLLAPSIBLE_TOP,
            BannerType.fromMetadata(mapOf("collapsible_position" to "TOP")),
        )
    }

    @Test
    fun malformedMetadataFallsBackToAdaptiveWithoutThrowing() {
        assertEquals(
            BannerType.ADAPTIVE,
            BannerType.fromMetadata(
                mapOf("banner_type" to mapOf("unexpected" to true), "collapsible_position" to 1),
            ),
        )
        assertEquals(
            BannerType.ADAPTIVE,
            BannerType.fromMetadata(mapOf("banner_type" to "unsupported")),
        )
    }
}
