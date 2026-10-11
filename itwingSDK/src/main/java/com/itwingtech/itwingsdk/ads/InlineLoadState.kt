package com.itwingtech.itwingsdk.ads

internal enum class InlineLoadPhase {
    LOADING,
    RENDERED,
}

internal data class InlineLoadState(
    val activeKey: String,
    val phase: InlineLoadPhase,
)

internal object InlineLoadReusePolicy {
    fun canReuse(
        state: InlineLoadState?,
        requestedKey: String,
        hasVisibleContent: Boolean,
    ): Boolean = state != null &&
            state.activeKey == requestedKey &&
            hasVisibleContent
}
