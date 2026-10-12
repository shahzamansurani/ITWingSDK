package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementFormatResolverTest {
    @Test
    fun inlineFormatsAreStrictAndSafe() {
        assertEquals("banner", PlacementFormatResolver.inlineFormat(" banner "))
        assertEquals("native", PlacementFormatResolver.inlineFormat("native"))
        assertEquals("native", PlacementFormatResolver.inlineFormat(null))
        assertEquals("native", PlacementFormatResolver.inlineFormat(7))
        assertEquals("native", PlacementFormatResolver.inlineFormat(mapOf("format" to "banner")))
        assertEquals("native", PlacementFormatResolver.inlineFormat(listOf("banner")))
        assertEquals("native", PlacementFormatResolver.inlineFormat("interstitial"))
    }

    @Test
    fun truthyMetadataAcceptsSupportedRemoteRepresentationsOnly() {
        assertTrue(PlacementFormatResolver.safeTruthy(true))
        assertTrue(PlacementFormatResolver.safeTruthy(1))
        assertTrue(PlacementFormatResolver.safeTruthy(" enabled "))
        assertFalse(PlacementFormatResolver.safeTruthy(false))
        assertFalse(PlacementFormatResolver.safeTruthy(0))
        assertFalse(PlacementFormatResolver.safeTruthy(mapOf("value" to true)))
    }
}
