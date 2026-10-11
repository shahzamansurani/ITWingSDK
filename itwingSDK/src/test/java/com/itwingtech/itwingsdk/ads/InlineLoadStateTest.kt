package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineLoadStateTest {
    @Test
    fun staleChildWithoutLoaderStateCannotBypassShimmer() {
        assertFalse(
            InlineLoadReusePolicy.canReuse(
                state = null,
                requestedKey = "home|SMALL",
                hasVisibleContent = true,
            )
        )
    }

    @Test
    fun hiddenContentCannotBeReusedAfterSuppressionReentry() {
        val state = InlineLoadState("home|SMALL", InlineLoadPhase.RENDERED)

        assertFalse(InlineLoadReusePolicy.canReuse(state, "home|SMALL", hasVisibleContent = false))
    }

    @Test
    fun activeLoadingStateCanBeReusedOnlyWithVisibleContent() {
        val state = InlineLoadState("home|BANNER", InlineLoadPhase.LOADING)

        assertTrue(InlineLoadReusePolicy.canReuse(state, "home|BANNER", hasVisibleContent = true))
        assertFalse(InlineLoadReusePolicy.canReuse(state, "home|BANNER", hasVisibleContent = false))
    }

    @Test
    fun differentPlacementCannotReuseExistingInlineState() {
        val state = InlineLoadState("home|LARGE", InlineLoadPhase.RENDERED)

        assertFalse(InlineLoadReusePolicy.canReuse(state, "details|LARGE", hasVisibleContent = true))
    }
}
