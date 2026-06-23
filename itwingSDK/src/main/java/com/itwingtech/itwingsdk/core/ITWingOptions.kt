package com.itwingtech.itwingsdk.core

data class ITWingOptions(
    val endpoint: String = "https://sdk.itwingtech.com/api/sdk/v1",
    val bootstrapTimeoutMs: Long = 4_000,
    val strictSslPinning: Boolean = false,
    val analyticsEnabled: Boolean = true,
    val autoApplyResponsiveLayout: Boolean = false,
)
