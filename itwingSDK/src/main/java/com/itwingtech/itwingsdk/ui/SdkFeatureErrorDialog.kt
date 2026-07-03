package com.itwingtech.itwingsdk.ui

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
import com.itwingtech.itwingsdk.utils.safeCallback
import java.util.concurrent.ConcurrentHashMap

internal object SdkFeatureErrorDialog {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastShownAt = ConcurrentHashMap<String, Long>()
    private const val THROTTLE_MS = 4_000L

    fun show(
        activity: Activity?,
        feature: String,
        primaryColor: Int,
        reason: String,
        onRetry: (() -> Unit)? = null,
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val key = feature.trim().ifBlank { "sdk_feature" }.lowercase()
        val now = System.currentTimeMillis()
        val previous = lastShownAt[key] ?: 0L
        if (now - previous < THROTTLE_MS) return
        lastShownAt[key] = now

        val action: () -> Unit = action@{
            if (activity.isFinishing || activity.isDestroyed) return@action
            runCatching {
                val content = LayoutInflater.from(activity).inflate(R.layout.dialog_itwing_action, null, false)
                val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.58) Color.BLACK else Color.WHITE
                content.findViewById<TextView>(R.id.itwing_action_title).text =
                    "${feature.trim().ifBlank { "SDK feature" }} unavailable"
                content.findViewById<TextView>(R.id.itwing_action_description).text =
                    reason.cleanReason()
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
            Unit
        }

        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun String.cleanReason(): String {
        val normalized = trim().ifBlank {
            "This SDK feature could not load required data. Check your internet connection and try again."
        }
        return when {
            normalized.contains("network_dns_unavailable", ignoreCase = true) ->
                "No internet connection is available. Cached data will be used when possible."
            normalized.contains("network_timeout", ignoreCase = true) ||
                normalized.contains("timed out", ignoreCase = true) ->
                "The request timed out because the connection is slow. Please try again."
            normalized.contains("Unable to resolve host", ignoreCase = true) ||
                normalized.contains("UnknownHost", ignoreCase = true) ->
                "No internet connection is available. Please connect and try again."
            else -> normalized
        }.take(320)
    }

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val maxWidth = (430 * density).toInt()
        val margin = (28 * density).toInt()
        return minOf(maxWidth, screenWidth - margin).coerceAtLeast((300 * density).toInt())
    }
}
