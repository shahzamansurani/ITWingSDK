package com.itwingtech.itwingsdk.data

import com.itwingtech.itwingsdk.core.ITWingSDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigRepositoryVersionTest {
    @Test
    fun everySignedRequestUsesTheRuntimeSdkVersion() {
        assertEquals("X-ITW-SDK-Version", SDK_VERSION_HEADER)
        assertEquals(ITWingSDK.VERSION, sdkVersionHeaderValue())
        assertEquals("1.55", sdkVersionHeaderValue())
        assertNotEquals("1.0.0", sdkVersionHeaderValue())
    }
}
