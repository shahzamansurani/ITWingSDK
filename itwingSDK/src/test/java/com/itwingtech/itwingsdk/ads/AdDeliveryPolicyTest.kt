package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDeliveryPolicyTest {
    private val customNative = CustomAdConfig(
        id = "native-1",
        format = "native",
        mediaUrl = "https://example.test/native.png",
    )

    private fun placement(
        metadata: Map<String, Any?> = emptyMap(),
        sourceMode: String? = null,
        units: List<AdUnitConfig> = listOf(AdUnitConfig("admob", "ca-app-pub-test", 1)),
    ) = AdPlacementConfig(
        name = "home_native",
        format = "native",
        enabled = true,
        testMode = false,
        metadata = metadata,
        sourceMode = sourceMode,
        units = units,
    )

    @Test fun defaultModeUsesAdMobFirstAndCustomFallback() {
        val config = ITWingConfig(ads = AdsConfig(customAds = listOf(customNative)))
        val current = placement()

        assertEquals("admob_first_custom_fallback", current.deliveryMode())
        assertFalse(current.shouldRenderCustomBeforeAdMob())
        assertEquals(customNative, config.customFallbackFor(current))
    }

    @Test fun explicitAdMobOnlyCannotUseCustomFallback() {
        val config = ITWingConfig(ads = AdsConfig(customAds = listOf(customNative)))
        val current = placement(metadata = mapOf("source" to "admob"))

        assertEquals("admob_only", current.deliveryMode())
        assertFalse(current.allowsCustomFallback())
        assertEquals(null, config.customFallbackFor(current))
    }

    @Test fun fallbackRejectsWrongFormatCreative() {
        val wrongFormat = customNative.copy(format = "banner")
        val config = ITWingConfig(ads = AdsConfig(customAds = listOf(wrongFormat)))
        val current = placement(units = emptyList())

        assertTrue(current.shouldRenderCustomBeforeAdMob())
        assertEquals(null, config.customFallbackFor(current))
    }
}
