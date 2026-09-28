package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDeliveryPolicyTest {
    @Test
    fun validAdMobUnitWinsEvenWhenPlacementHasCustomCreative() {
        val placement = placement(
            units = listOf(AdUnitConfig("admob", "ca-app-pub-test/unit", 1)),
            customAd = CustomAdConfig(id = "fallback", imageUrl = "https://cdn.example.test/fallback.jpg"),
        )

        assertEquals("ca-app-pub-test/unit", placement.adMobUnitOrNull()?.adUnitId)
        assertFalse(placement.shouldRenderCustomBeforeAdMob())
    }

    @Test
    fun blankOrOtherNetworkUnitsDoNotBlockCustomFallbackMode() {
        val placement = placement(
            units = listOf(AdUnitConfig("other", "unit", 1), AdUnitConfig("admob", "  ", 2)),
            customAd = CustomAdConfig(id = "only", imageUrl = "https://cdn.example.test/ad.jpg"),
        )

        assertNull(placement.adMobUnitOrNull())
        assertTrue(placement.shouldRenderCustomBeforeAdMob())
    }

    @Test
    fun attachedCustomCreativeIsResolvedAsFallbackForTheSamePlacement() {
        val fallback = CustomAdConfig(id = "same-placement-fallback", imageUrl = "https://cdn.example.test/ad.jpg")
        val placement = placement(
            units = listOf(AdUnitConfig("admob", "ca-app-pub-test/unit", 1)),
            customAd = fallback,
        )
        val config = ITWingConfig(ads = AdsConfig(globalEnabled = true, placements = listOf(placement)))

        assertEquals(fallback, config.customFallbackFor(placement))
    }

    @Test
    fun configuredRealAndCustomCreativeNormalizeToRealFirstFallbackMode() {
        val fallback = CustomAdConfig(id = "fallback", format = "native", html = "<b>Fallback</b>")
        val placement = placement(
            units = listOf(AdUnitConfig(" AdMob ", " ca-app-pub-test/unit ", 1)),
            customAd = fallback,
        )

        assertEquals("ca-app-pub-test/unit", placement.adMobUnitOrNull()?.adUnitId)
        assertEquals("REAL_WITH_CUSTOM_FALLBACK", placement.deliveryMode(hasCustomFallback = true))
        assertEquals(fallback, ITWingConfig(ads = AdsConfig(customAds = listOf(fallback))).customFallbackFor(placement))
    }

    private fun placement(
        units: List<AdUnitConfig>,
        customAd: CustomAdConfig,
    ) = AdPlacementConfig(
        name = "native_test",
        format = "native",
        enabled = true,
        testMode = true,
        units = units,
        customAd = customAd,
    )
}
