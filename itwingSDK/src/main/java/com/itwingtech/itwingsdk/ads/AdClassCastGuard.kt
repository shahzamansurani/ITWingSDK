package com.itwingtech.itwingsdk.ads

/** Converts SDK view/ad type mismatches into a recoverable ad failure. */
internal object AdClassCastGuard {
    inline fun run(action: () -> Unit, onMismatch: (ClassCastException) -> Unit) {
        try {
            action()
        } catch (error: ClassCastException) {
            onMismatch(error)
        }
    }
}
