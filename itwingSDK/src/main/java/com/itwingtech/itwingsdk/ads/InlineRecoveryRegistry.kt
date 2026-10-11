package com.itwingtech.itwingsdk.ads

/** Keeps one recovery callback per inline placement instance while recovery is pending. */
internal class InlineRecoveryRegistry {
    private val callbacks = LinkedHashMap<String, () -> Unit>()

    @Synchronized
    fun addIfAbsent(key: String, callback: () -> Unit): Boolean {
        if (callbacks.containsKey(key)) return false
        callbacks[key] = callback
        return true
    }

    @Synchronized
    fun drain(): List<() -> Unit> {
        if (callbacks.isEmpty()) return emptyList()
        val pending = callbacks.values.toList()
        callbacks.clear()
        return pending
    }

    @Synchronized
    fun clear() {
        callbacks.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = callbacks.isEmpty()
}
