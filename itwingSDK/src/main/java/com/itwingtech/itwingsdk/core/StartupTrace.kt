package com.itwingtech.itwingsdk.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.util.UUID

internal object StartupTrace {
    private const val TAG = "ITWingSDKStartup"
    @Volatile private var traceId = "not-started"

    fun begin(context: Context, details: String = "") {
        if (!isDebuggable(context)) return
        traceId = UUID.randomUUID().toString().take(8)
        event(context, "SDK_INIT_START", details)
    }

    fun event(context: Context?, stage: String, details: String = "") {
        if (context == null || !isDebuggable(context)) return
        val line = "traceId=$traceId stage=$stage" + if (details.isBlank()) "" else " $details"
        Log.i(TAG, line)
        runCatching { File(context.filesDir, "itwing-sdk-startup-debug.log").appendText("$line\n") }
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
