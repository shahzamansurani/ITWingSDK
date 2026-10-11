package com.itwingtech.itwingsdk.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineRecoveryRegistryTest {
    @Test
    fun duplicatePlacementRegistrationIsIgnored() {
        val registry = InlineRecoveryRegistry()
        var calls = 0

        assertTrue(registry.addIfAbsent("native:screen:placement", { calls++ }))
        assertFalse(registry.addIfAbsent("native:screen:placement", { calls++ }))

        registry.drain().single().invoke()

        assertEquals(1, calls)
        assertTrue(registry.isEmpty())
    }

    @Test
    fun drainedCallbacksCannotRunAgainAfterRepeatedInteraction() {
        val registry = InlineRecoveryRegistry()
        var calls = 0
        registry.addIfAbsent("banner:screen:placement", { calls++ })

        registry.drain().forEach { it() }
        registry.drain().forEach { it() }

        assertEquals(1, calls)
    }

    @Test
    fun clearingDestroyedScreenDropsRecoveryCallbacks() {
        val registry = InlineRecoveryRegistry()
        var calls = 0
        registry.addIfAbsent("native:destroyed:placement", { calls++ })

        registry.clear()
        registry.drain().forEach { it() }

        assertEquals(0, calls)
    }
}
