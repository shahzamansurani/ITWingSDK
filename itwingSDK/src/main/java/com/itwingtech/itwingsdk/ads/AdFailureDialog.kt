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
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.ui.GlassDialogWindow
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
                    val retryColor = sdkColor("ad_failure_button_color", "dialog_positive_button_color", fallback = primaryColor)
                    val retryTextFallback = if (ColorUtils.calculateLuminance(retryColor) > 0.58) Color.BLACK else Color.WHITE
                    content.findViewById<TextView>(R.id.itwing_action_title).apply {
                        text = activity.getString(R.string.ad_failed_title)
                        setTextColor(sdkColor("dialog_title_color", "ad_failure_text_color", "text_color", fallback = Color.rgb(17, 24, 39)))
                    }
                    content.findViewById<TextView>(R.id.itwing_action_description).apply {
                        text = activity.getString(R.string.ad_failed_reason, reason.cleanReason())
                        setTextColor(sdkColor("ad_failure_text_color", "dialog_description_color", "secondary_text_color", fallback = Color.rgb(107, 114, 128)))
                    }
                    content.findViewById<View>(R.id.itwing_action_native_container).visibility = View.GONE

                    val dialog = AlertDialog.Builder(activity)
                        .setView(content)
                        .create()

                    content.findViewById<TextView>(R.id.itwing_action_close).setOnClickListener {
                        dialog.dismiss()
                    }
                    content.findViewById<MaterialButton>(R.id.itwing_action_negative).apply {
                        text = activity.getString(R.string.cancel)
                        val cancelColor = sdkColor("dialog_negative_text_color", fallback = primaryColor)
                        setTextColor(cancelColor)
                        strokeColor = ColorStateList.valueOf(sdkColor("dialog_negative_stroke_color", fallback = ColorUtils.setAlphaComponent(cancelColor, 120)))
                        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(cancelColor, 28))
                        setOnClickListener { dialog.dismiss() }
                    }
                    content.findViewById<MaterialButton>(R.id.itwing_action_positive).apply {
                        visibility = if (onRetry == null) View.GONE else View.VISIBLE
                        text = activity.getString(R.string.try_again)
                        backgroundTintList = ColorStateList.valueOf(retryColor)
                        setTextColor(sdkColor("dialog_positive_text_color", fallback = retryTextFallback))
                        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(retryColor, 44))
                        setOnClickListener {
                            dialog.dismiss()
                            onRetry?.let { retry -> safeCallback(retry) }
                        }
                    }

                    dialog.setOnShowListener {
                        GlassDialogWindow.apply(dialog.window, activity.dialogWidth())
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

    private fun sdkColor(vararg keys: String, fallback: Int): Int {
        keys.forEach { key ->
            ITWingSDK.getColor(key)
                .takeIf { it.isNotBlank() }
                ?.let { value -> runCatching { Color.parseColor(value) }.getOrNull() }
                ?.let { return it }
        }
        return fallback
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
