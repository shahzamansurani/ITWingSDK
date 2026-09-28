package com.itwingtech.itwingsdk.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiBaseUrlResolverTest {
    @Test
    fun configuredUrlIsNormalizedWithoutAndroidContext() {
        assertEquals("https://api.example.test/v1/", ApiBaseUrlResolver.resolve(" https://api.example.test/v1 ", ""))
    }

    @Test
    fun absentOrInvalidConfiguredUrlUsesBackwardCompatibleDefault() {
        assertEquals("https://fallback.example/", ApiBaseUrlResolver.resolve(null, "https://fallback.example"))
        assertEquals("https://fallback.example/", ApiBaseUrlResolver.resolve("file:///tmp", "https://fallback.example"))
        assertEquals("", ApiBaseUrlResolver.resolve(null, ""))
    }
}
