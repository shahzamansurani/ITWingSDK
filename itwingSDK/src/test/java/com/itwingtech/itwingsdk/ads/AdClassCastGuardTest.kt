package com.itwingtech.itwingsdk.ads

import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class AdClassCastGuardTest {
    @Test
    fun incompatibleSdkViewTypeIsReportedInsteadOfEscapingToHost() {
        val hostCallback = mutableListOf<String>()
        var mismatch: ClassCastException? = null

        AdClassCastGuard.run(
            action = {
                val unexpected: Any = "not-an-android-view-group"
                @Suppress("USELESS_CAST")
                unexpected as ViewGroup
            },
            onMismatch = {
                mismatch = it
                hostCallback += "completed"
            },
        )

        assertEquals(ClassCastException::class.java, mismatch?.javaClass)
        assertEquals(listOf("completed"), hostCallback)
    }

    @Test
    fun unrelatedFailuresAreNotSilentlyConvertedToClassCastFailures() {
        val expected = IllegalStateException("unrelated")
        val actual = runCatching {
            AdClassCastGuard.run(
                action = { throw expected },
                onMismatch = { throw AssertionError("not a ClassCastException", it) },
            )
        }.exceptionOrNull()

        assertEquals(expected, actual)
    }
}
