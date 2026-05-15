package com.itwingtech.itwingsdk.utils

import android.os.Handler
import android.os.Looper

private val mainHandler = Handler(Looper.getMainLooper())

fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        mainHandler.post {
            block()
        }
    }
}

inline fun safeCallback(crossinline callback: () -> Unit) {
    runOnMain {
        runCatching {
            callback() }.onFailure {
            it.printStackTrace()
        }
    }
}