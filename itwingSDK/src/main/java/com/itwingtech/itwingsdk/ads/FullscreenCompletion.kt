package com.itwingtech.itwingsdk.ads

import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.atomic.AtomicBoolean

internal class FullscreenCompletion(
    private val onComplete: () -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete() {
        if (completed.compareAndSet(false, true)) {
            safeCallback(onComplete)
        }
    }
}
