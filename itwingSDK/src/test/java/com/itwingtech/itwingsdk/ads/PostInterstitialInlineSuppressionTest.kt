package com.itwingtech.itwingsdk.ads

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostInterstitialInlineSuppressionTest {
    @Before
    fun resetBefore() = PostInterstitialInlineSuppression.resetForTests()

    @After
    fun resetAfter() = PostInterstitialInlineSuppression.resetForTests()

    @Test
    fun suppressionStartsOnlyAfterAnInterstitialIsActuallyPresented() {
        PostInterstitialInlineSuppression.markPresented(enabled = false)
        assertFalse(PostInterstitialInlineSuppression.isSuppressed(enabled = true))

        PostInterstitialInlineSuppression.markPresented(enabled = true)
        assertTrue(PostInterstitialInlineSuppression.isSuppressed(enabled = true))
    }

    @Test
    fun disabledSettingNeverSuppressesAndClearsAnExistingCycle() {
        PostInterstitialInlineSuppression.markPresented(enabled = true)

        assertFalse(PostInterstitialInlineSuppression.isSuppressed(enabled = false))
        assertFalse(PostInterstitialInlineSuppression.isSuppressed(enabled = true))
    }

    @Test
    fun nextInterstitialCallConsumesPreviousCycle() {
        PostInterstitialInlineSuppression.markPresented(enabled = true)
        PostInterstitialInlineSuppression.consumeAtNextInterstitialCall()

        assertFalse(PostInterstitialInlineSuppression.isSuppressed(enabled = true))
    }

    @Test
    fun failedOrSkippedNextInterstitialDoesNotCreateANewCycle() {
        PostInterstitialInlineSuppression.consumeAtNextInterstitialCall()

        assertFalse(PostInterstitialInlineSuppression.isSuppressed(enabled = true))
    }
}
