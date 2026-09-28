package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomAdTextTest {
    @Test
    fun mapsAdvertiserAndHeadlineToDistinctFields() {
        val display = CustomAdConfig(
            name = "Generic campaign",
            headline = "Powerful App Experience",
            campaignGroup = "IT Wing",
            metadata = mapOf("brand" to mapOf("name" to "IT Wing")),
        ).displayText()

        assertEquals("IT Wing", display.advertiser)
        assertEquals("Powerful App Experience", display.headline)
    }

    @Test
    fun doesNotRepeatBrandWhenHeadlineIsMissingOrSameAsBrand() {
        val missing = CustomAdConfig(name = "IT Wing", campaignGroup = "IT Wing").displayText()
        val repeated = CustomAdConfig(
            name = "IT Wing",
            headline = "it wing",
            campaignGroup = "IT Wing",
        ).displayText()

        assertEquals("IT Wing", missing.advertiser)
        assertEquals(null, missing.headline)
        assertEquals("IT Wing", repeated.advertiser)
        assertEquals(null, repeated.headline)
    }

    @Test
    fun missingBrandDoesNotInventAnAdvertiserOrHideTheHeadline() {
        val display = CustomAdConfig(name = "Install Example App").displayText()

        assertEquals(null, display.advertiser)
        assertEquals("Install Example App", display.headline)
    }
}
