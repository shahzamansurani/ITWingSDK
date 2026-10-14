package com.itwingtech.itwingsdk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitEngagementPolicyTest {
    @Test
    fun threeStarsRemainInFeedbackPath() {
        assertFalse(ExitEngagementPolicy.isHighRating(3f))
    }

    @Test
    fun fourAndFiveStarsUseReviewPath() {
        assertTrue(ExitEngagementPolicy.isHighRating(4f))
        assertTrue(ExitEngagementPolicy.isHighRating(5f))
    }
}
