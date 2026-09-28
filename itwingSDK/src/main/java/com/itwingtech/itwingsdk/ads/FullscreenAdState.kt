package com.itwingtech.itwingsdk.ads

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal object FullscreenAdState {
    private val activeOwner = AtomicReference<String?>(null)
    private val lastEndedAtMs = AtomicLong(0L)

    fun tryBegin(format: String, placement: String): String? {
        val owner = "$format:$placement:${System.nanoTime()}"
        return if (activeOwner.compareAndSet(null, owner)) owner else null
    }

    fun end(owner: String?) {
        if (owner != null) {
            if (activeOwner.compareAndSet(owner, null)) {
                lastEndedAtMs.set(android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    fun isActive(): Boolean = activeOwner.get() != null

    fun activeOwner(): String? = activeOwner.get()

    fun wasRecentlyEnded(windowMs: Long = 1_500L): Boolean {
        val endedAt = lastEndedAtMs.get()
        return endedAt > 0L && android.os.SystemClock.elapsedRealtime() - endedAt in 0..windowMs
    }
}
