package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerTypeTest {
    @Test
    fun resolvesSupportedCollapsibleMetadata() {
        assertEquals(BannerType.COLLAPSIBLE_TOP, BannerType.fromMetadata(mapOf("banner_type" to "top")))
        assertEquals(BannerType.COLLAPSIBLE_BOTTOM, BannerType.fromMetadata(mapOf("banner_type" to "bottom")))
        assertEquals(BannerType.COLLAPSIBLE_TOP, BannerType.fromMetadata(mapOf("banner_type" to "collapsible_top")))
        assertEquals(BannerType.COLLAPSIBLE_TOP, BannerType.fromMetadata(mapOf("banner_type" to "collapsible", "collapsible_position" to "top")))
        assertEquals(BannerType.COLLAPSIBLE_BOTTOM, BannerType.fromMetadata(mapOf("collapsible_position" to "bottom")))
    }

    @Test
    fun malformedMetadataFallsBackToAdaptive() {
        assertEquals(BannerType.ADAPTIVE, BannerType.fromMetadata(mapOf("banner_type" to "unsupported")))
        assertEquals(BannerType.ADAPTIVE, BannerType.fromMetadata(mapOf("banner_type" to "  ")))
        assertEquals(BannerType.ADAPTIVE, BannerType.fromMetadata(emptyMap()))
    }
}
