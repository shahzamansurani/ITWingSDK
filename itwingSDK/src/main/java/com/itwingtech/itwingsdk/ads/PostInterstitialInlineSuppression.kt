package com.itwingtech.itwingsdk.ads

import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped suppression for inline ads after a real fullscreen interstitial presentation. */
internal object PostInterstitialInlineSuppression {
    private val suppressed = AtomicBoolean(false)

    /** The next public interstitial call consumes the previous destination suppression cycle. */
    fun consumeAtNextInterstitialCall() {
        suppressed.set(false)
    }

    fun isSuppressed(enabled: Boolean): Boolean {
        if (!enabled) suppressed.set(false)
        return enabled && suppressed.get()
    }

    fun markPresented(enabled: Boolean) {
        if (enabled) suppressed.set(true)
    }

    internal fun resetForTests() {
        suppressed.set(false)
    }
}
