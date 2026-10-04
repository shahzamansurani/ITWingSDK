package com.itwingtech.itwingsdk.ads

internal object AdClassCastGuard {
    inline fun run(action: () -> Unit, onMismatch: (ClassCastException) -> Unit) {
        try {
            action()
        } catch (error: ClassCastException) {
            onMismatch(error)
        }
    }
}
