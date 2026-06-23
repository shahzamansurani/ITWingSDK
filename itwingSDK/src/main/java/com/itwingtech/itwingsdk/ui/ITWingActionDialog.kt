package com.itwingtech.itwingsdk.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.ads.NativeType
import com.itwingtech.itwingsdk.core.ITWingSDK

class ITWingActionDialog internal constructor(
    private val activity: Activity,
    private val defaultsProvider: () -> Map<*, *>,
    private val primaryColorProvider: () -> Int,
) {
    private var dialog: AlertDialog? = null
    private var nativeContainer: FrameLayout? = null

    @JvmOverloads
    fun show(
        title: String? = null,
        description: String? = null,
        positiveText: String? = null,
        negativeText: String? = null,
        nativePlacement: String? = null,
        nativeType: String? = null,
        onPositive: Runnable? = null,
        onNegative: Runnable? = null,
        onCancel: Runnable? = null,
    ) {
        if (!activity.isUsable()) {
            onCancel?.run()
            return
        }

        val defaults = defaultsProvider()
        if (!defaults.boolean("enabled", true)) {
            onCancel?.run()
            return
        }

        dismiss()

        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_itwing_action, null, false)
        val primaryColor = primaryColorProvider()
        val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.58) Color.BLACK else Color.WHITE
        val resolvedTitle = title ?: defaults.string("title") ?: "Continue?"
        val resolvedDescription = description ?: defaults.string("description") ?: "Choose how you want to continue."
        val resolvedPositive = positiveText ?: defaults.string("positive_text") ?: "Continue"
        val resolvedNegative = negativeText ?: defaults.string("negative_text") ?: "Cancel"
        val resolvedNativePlacement = nativePlacement ?: defaults.string("native_placement")
        val resolvedNativeType = nativeType ?: defaults.string("native_type")

        content.findViewById<TextView>(R.id.itwing_action_title).text = resolvedTitle
        content.findViewById<TextView>(R.id.itwing_action_description).text = resolvedDescription
        content.findViewById<TextView>(R.id.itwing_action_close).setOnClickListener {
            dismiss()
            onCancel?.run()
        }

        nativeContainer = content.findViewById(R.id.itwing_action_native_container)
        if (!resolvedNativePlacement.isNullOrBlank() && !resolvedNativeType.equals("none", ignoreCase = true)) {
            nativeContainer?.visibility = View.VISIBLE
            ITWingSDK.ads.loadNative(
                activity = activity,
                container = nativeContainer!!,
                placement = resolvedNativePlacement,
                nativeType = when (resolvedNativeType?.lowercase()) {
                    "small" -> NativeType.SMALL
                    "large" -> NativeType.LARGE
                    else -> null
                },
            )
        }

        content.findViewById<MaterialButton>(R.id.itwing_action_positive).apply {
            text = resolvedPositive
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(onPrimary)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))
            setOnClickListener {
                dismiss()
                onPositive?.run()
            }
        }

        content.findViewById<MaterialButton>(R.id.itwing_action_negative).apply {
            text = resolvedNegative
            setTextColor(primaryColor)
            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 120))
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 28))
            setOnClickListener {
                dismiss()
                onNegative?.run()
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .create()
            .also { alert ->
                alert.setOnCancelListener {
                    onCancel?.run()
                }
                alert.setOnDismissListener {
                    nativeContainer?.let { container -> ITWingSDK.ads.destroyNative(container) }
                    nativeContainer = null
                }
                alert.setOnShowListener {
                    alert.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    alert.window?.setLayout(activity.dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
                }
                alert.show()
            }
    }

    fun dismiss() {
        nativeContainer?.let { container -> ITWingSDK.ads.destroyNative(container) }
        nativeContainer = null
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val maxWidth = (430 * density).toInt()
        val margin = (28 * density).toInt()
        return minOf(maxWidth, screenWidth - margin).coerceAtLeast((300 * density).toInt())
    }

    private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
        else -> default
    }

    private fun Map<*, *>.string(key: String): String? =
        this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}
