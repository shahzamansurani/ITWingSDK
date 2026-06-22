package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.utils.safeCallback

internal object AdFailureDialog {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(
        activity: Activity,
        primaryColor: Int,
        reason: String,
        onRetry: (() -> Unit)? = null,
    ) {
        val action = {
            if (!activity.isFinishing && !activity.isDestroyed) {
                runCatching {
                    val builder = AlertDialog.Builder(activity)
                        .setTitle(R.string.ad_failed_title)
                        .setMessage(activity.getString(R.string.ad_failed_reason, reason.cleanReason()))
                        .setNegativeButton(R.string.cancel, null)

                    if (onRetry != null) {
                        builder.setPositiveButton(R.string.try_again) { _, _ -> safeCallback(onRetry) }
                    }

                    builder.create().also { dialog ->
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(primaryColor)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(primaryColor)
                        }
                        dialog.show()
                    }
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun String.cleanReason(): String =
        trim().ifBlank { "No ad is available right now. Please try again." }.take(300)
}

internal fun ITWingConfig.adPrimaryColor(): Int {
    val colors = app["colors"] as? Map<*, *>
    val value = listOf(
        colors?.get("primary"),
        colors?.get("primary_color"),
        app["primary_color"],
        app["primaryColor"],
    ).firstNotNullOfOrNull { it?.toString()?.takeIf(String::isNotBlank) }

    return runCatching { Color.parseColor(value ?: "#2563EB") }
        .getOrDefault(Color.rgb(37, 99, 235))
}
