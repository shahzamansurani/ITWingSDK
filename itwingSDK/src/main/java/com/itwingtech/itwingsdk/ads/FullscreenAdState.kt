package com.itwingtech.itwingsdk.ads

import java.util.concurrent.atomic.AtomicReference

internal object FullscreenAdState {
    private val activeOwner = AtomicReference<String?>(null)

    fun tryBegin(format: String, placement: String): String? {
        val owner = "$format:$placement:${System.nanoTime()}"
        return if (activeOwner.compareAndSet(null, owner)) owner else null
    }

    fun end(owner: String?) {
        if (owner != null) {
            activeOwner.compareAndSet(owner, null)
        }
    }

    fun isActive(): Boolean = activeOwner.get() != null

    fun activeOwner(): String? = activeOwner.get()
}
