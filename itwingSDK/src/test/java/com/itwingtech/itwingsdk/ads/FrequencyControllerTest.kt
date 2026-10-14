package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.AdPlacementConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyControllerTest {
    private fun placement(dailyCap: Int?) = AdPlacementConfig(
        name = "home_banner",
        format = "banner",
        enabled = true,
        testMode = false,
        dailyCap = dailyCap,
    )

    @Test
    fun absentOrNonPositiveDailyCapDoesNotBlock() {
        listOf(null, 0, -1).forEach { cap ->
            val controller = FrequencyController()
            val current = placement(cap)

            assertTrue(controller.canShow(current))
            controller.markShown(current)
            assertTrue(controller.canShow(current))
        }
    }

    @Test
    fun dailyCapBlocksAfterSuccessfulPresentation() {
        val controller = FrequencyController()
        val current = placement(1)

        assertTrue(controller.canShow(current))
        controller.markShown(current)
        assertFalse(controller.canShow(current))
    }

    @Test
    fun dailyCapAllowsConfiguredNumberOfPresentations() {
        val controller = FrequencyController()
        val current = placement(2)

        assertTrue(controller.canShow(current))
        controller.markShown(current)
        assertTrue(controller.canShow(current))
        controller.markShown(current)
        assertFalse(controller.canShow(current))
    }

    @Test
    fun dailyCapResetsOnTheNextLocalDay() {
        var now = System.currentTimeMillis()
        val controller = FrequencyController(nowProvider = { now })
        val current = placement(1)

        controller.markShown(current)
        assertFalse(controller.canShow(current))

        now += 48L * 60L * 60L * 1_000L
        assertTrue(controller.canShow(current))
    }
}
