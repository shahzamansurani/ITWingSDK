package com.itwingtech.itwingsdk.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class PlacementFormatResolverTest {
    @Test
    fun supportedFormatsAreNormalized() {
        assertEquals("banner", normalizePlacementFormat(" BANNER "))
        assertEquals("native", normalizePlacementFormat("native"))
    }

    @Test
    fun malformedFormatsUseSafeNativeFallback() {
        assertEquals("native", normalizePlacementFormat(null))
        assertEquals("native", normalizePlacementFormat("interstitial"))
        assertEquals("native", normalizePlacementFormat(42))
        assertEquals("native", normalizePlacementFormat(""))
    }
}
