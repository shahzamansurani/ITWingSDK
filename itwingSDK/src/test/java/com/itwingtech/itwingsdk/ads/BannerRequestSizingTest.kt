package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BannerRequestSizingTest {
    @Test
    fun usesMeasuredInnerHostAndNeverFallsBackToScreenWidth() {
        assertEquals(300, BannerRequestSizing.widthDp(900, 3f))
        assertNull(BannerRequestSizing.widthDp(0, 3f))
    }

    @Test
    fun onlyCollapsibleTypesProduceGoogleDirection() {
        assertNull(BannerRequestSizing.collapsibleDirection(BannerType.ADAPTIVE))
        assertEquals("top", BannerRequestSizing.collapsibleDirection(BannerType.COLLAPSIBLE_TOP))
        assertEquals("bottom", BannerRequestSizing.collapsibleDirection(BannerType.COLLAPSIBLE_BOTTOM))
    }
}
