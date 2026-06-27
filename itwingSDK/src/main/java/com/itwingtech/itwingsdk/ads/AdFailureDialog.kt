package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.button.MaterialButton
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
                    val content = LayoutInflater.from(activity).inflate(R.layout.dialog_itwing_action, null, false)
                    val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.58) Color.BLACK else Color.WHITE
                    content.findViewById<TextView>(R.id.itwing_action_title).text =
                        activity.getString(R.string.ad_failed_title)
                    content.findViewById<TextView>(R.id.itwing_action_description).text =
                        activity.getString(R.string.ad_failed_reason, reason.cleanReason())
                    content.findViewById<View>(R.id.itwing_action_native_container).visibility = View.GONE

                    val dialog = AlertDialog.Builder(activity)
                        .setView(content)
                        .create()

                    content.findViewById<TextView>(R.id.itwing_action_close).setOnClickListener {
                        dialog.dismiss()
                    }
                    content.findViewById<MaterialButton>(R.id.itwing_action_negative).apply {
                        text = activity.getString(R.string.cancel)
                        setTextColor(primaryColor)
                        strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 120))
                        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 28))
                        setOnClickListener { dialog.dismiss() }
                    }
                    content.findViewById<MaterialButton>(R.id.itwing_action_positive).apply {
                        visibility = if (onRetry == null) View.GONE else View.VISIBLE
                        text = activity.getString(R.string.try_again)
                        backgroundTintList = ColorStateList.valueOf(primaryColor)
                        setTextColor(onPrimary)
                        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))
                        setOnClickListener {
                            dialog.dismiss()
                            onRetry?.let { retry -> safeCallback(retry) }
                        }
                    }

                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                        dialog.window?.setLayout(activity.dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
                    }
                    dialog.show()
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun String.cleanReason(): String =
        trim().ifBlank { "No ad is available right now. Please try again." }.take(300)

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val maxWidth = (430 * density).toInt()
        val margin = (28 * density).toInt()
        return minOf(maxWidth, screenWidth - margin).coerceAtLeast((300 * density).toInt())
    }
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
