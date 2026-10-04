package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.core.CustomAdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun inlineSuppressionOnlyFollowsActualPresentation() {
        PostInterstitialInlineSuppression.resetForTests()
        PostInterstitialInlineSuppression.markPresented(true)
        assertTrue(PostInterstitialInlineSuppression.isSuppressed(true))
        PostInterstitialInlineSuppression.consumeAtNextInterstitialCall()
        assertFalse(PostInterstitialInlineSuppression.isSuppressed(true))
    }

    @Test fun disabledInlineSuppressionClearsState() {
        PostInterstitialInlineSuppression.resetForTests()
        PostInterstitialInlineSuppression.markPresented(true)
        assertFalse(PostInterstitialInlineSuppression.isSuppressed(false))
        assertFalse(PostInterstitialInlineSuppression.isSuppressed(true))
    }

    @Test fun duplicateBrandDoesNotBecomeHeadline() {
        val text = CustomAdConfig(name = "Brand", headline = "Brand", campaignGroup = "Brand").displayText()
        assertEquals("Brand", text.advertiser)
        assertEquals(null, text.headline)
    }

    @Test fun appOpenResumeRequiresTwoSeconds() {
        assertFalse(AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(false, 10_000))
        assertFalse(AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 1_999))
        assertTrue(AppOpenPresentationPolicy.shouldAutomaticallyPresentResume(true, 2_000))
    }
}
