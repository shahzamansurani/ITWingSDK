package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOpenPresentationPolicyTest {
    @Test
    fun foregroundPresentationIsAcceptedOnlyForCurrentResumedSessionWithoutConflict() {
        assertNull(AppOpenPresentationPolicy.rejectionReason(true, true, true, false))
        assertEquals("SKIPPED_BACKGROUND", AppOpenPresentationPolicy.rejectionReason(false, true, true, false))
        assertEquals("SKIPPED_NO_RESUMED_ACTIVITY", AppOpenPresentationPolicy.rejectionReason(true, false, true, false))
        assertEquals("SKIPPED_STALE_FOREGROUND_SESSION", AppOpenPresentationPolicy.rejectionReason(true, true, false, false))
        assertEquals("SKIPPED_FULLSCREEN_CONFLICT", AppOpenPresentationPolicy.rejectionReason(true, true, true, true))
        assertNull(AppOpenPresentationPolicy.rejectionReason(true, true, true, true, ownsFullscreenSlot = true))
    }

    @Test
    fun appOpenColdStartAndResumeThresholdAreExplicit() {
        assertEquals(false, AppOpenPresentationPolicy.mayPresentFirstEverLaunch(false))
        assertEquals(true, AppOpenPresentationPolicy.mayPresentFirstEverLaunch(true))
        assertEquals(false, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(false, 30_000L))
        assertEquals(false, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 1_999L))
        assertEquals(true, AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 2_000L))
    }

    @Test
    fun backgroundAndFullscreenTransitionsNeverQualifyAsPresentation() {
        assertEquals(
            "SKIPPED_FIRST_EVER_LAUNCH",
            AppOpenPresentationPolicy.rejectionReason(true, true, true, false, firstEverLaunch = true),
        )
        assertEquals("SKIPPED_BACKGROUND", AppOpenPresentationPolicy.rejectionReason(false, true, true, false))
        assertEquals("SKIPPED_FULLSCREEN_CONFLICT", AppOpenPresentationPolicy.rejectionReason(true, true, true, true))
    }
}
