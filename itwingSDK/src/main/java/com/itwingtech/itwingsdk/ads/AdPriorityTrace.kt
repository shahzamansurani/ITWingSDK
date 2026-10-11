package com.itwingtech.itwingsdk.ads

import android.app.Activity
import com.itwingtech.itwingsdk.core.StartupTrace

internal object AdPriorityTrace {
    fun request(activity: Activity, format: String, placement: String, source: String) {
        StartupTrace.event(activity, "${format.uppercase()}_REQUEST", "placement=${placement.take(80)} source=$source")
    }

    fun blocked(activity: Activity, format: String, placement: String, reason: String) {
        StartupTrace.event(activity, "AD_BLOCKED", "format=$format placement=${placement.take(80)} reason=$reason")
    }

    fun loaded(activity: Activity, format: String, placement: String, source: String) {
        StartupTrace.event(activity, "${format.uppercase()}_AD_LOADED", "placement=${placement.take(80)} source=$source")
    }

    fun impression(activity: Activity, format: String, placement: String) {
        StartupTrace.event(activity, "${format.uppercase()}_IMPRESSION", "placement=${placement.take(80)}")
    }
}
