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
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.ITWingSDK
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.drawable.toDrawable

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
            safeCallback("cancel_unavailable", onCancel)
            return
        }

        val defaults = defaultsProvider()
        if (!defaults.boolean("enabled", true)) {
            safeCallback("cancel_disabled", onCancel)
            return
        }

        dismiss()
        val callbackDelivered = AtomicBoolean(false)
        fun deliverCallback(name: String, callback: Runnable?) {
            if (callbackDelivered.compareAndSet(false, true)) {
                safeCallback(name, callback)
            }
        }

        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_itwing_action, null, false)
        val primaryColor = primaryColorProvider()
        val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.58) Color.BLACK else Color.WHITE
        val resolvedTitle = title ?: defaults.string("title", "dialog_title", "host_dialog_title") ?: "Continue?"
        val resolvedDescription = description ?: defaults.string("description", "body", "message", "host_dialog_description") ?: "Choose how you want to continue."
        val resolvedPositive = positiveText ?: defaults.string("positive_text", "positiveText", "positive_button", "positiveButton", "host_dialog_positive_text") ?: "Continue"
        val resolvedNegative = negativeText ?: defaults.string("negative_text", "negativeText", "negative_button", "negativeButton", "host_dialog_negative_text") ?: "Cancel"
        val resolvedNativePlacement = nativePlacement ?: defaults.string(
            "native_placement",
            "nativePlacement",
            "native_ad_placement",
            "nativeAdPlacement",
            "host_dialog_native_placement",
        )
        val normalizedNativeType = normalizeNativeType(
            nativeType ?: defaults.string(
                "native_type",
                "nativeType",
                "native_ad_size",
                "nativeAdSize",
                "native_size",
                "host_dialog_native_type",
            ),
            resolvedNativePlacement,
        )

        content.findViewById<TextView>(R.id.itwing_action_title).text = resolvedTitle
        content.findViewById<TextView>(R.id.itwing_action_description).text = resolvedDescription
        content.findViewById<TextView>(R.id.itwing_action_close).setOnClickListener {
            dismiss()
            deliverCallback("cancel_close", onCancel)
        }

        nativeContainer = content.findViewById(R.id.itwing_action_native_container)
        val shouldLoadNative =
            !resolvedNativePlacement.isNullOrBlank() &&
                normalizedNativeType != null
        nativeContainer?.visibility = if (shouldLoadNative) View.VISIBLE else View.GONE

        content.findViewById<MaterialButton>(R.id.itwing_action_positive).apply {
            text = resolvedPositive
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(onPrimary)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))
            setOnClickListener {
                dismiss()
                deliverCallback("positive", onPositive)
            }
        }

        content.findViewById<MaterialButton>(R.id.itwing_action_negative).apply {
            text = resolvedNegative
            setTextColor(primaryColor)
            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 120))
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 28))
            setOnClickListener {
                dismiss()
                deliverCallback("negative", onNegative)
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .create()
            .also { alert ->
                alert.setOnCancelListener {
                    deliverCallback("cancel_system", onCancel)
                }
                alert.setOnDismissListener {
                    nativeContainer?.let { container -> ITWingSDK.ads.destroyNative(container) }
                    nativeContainer = null
                }
                alert.setOnShowListener {
                    alert.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                    alert.window?.setLayout(activity.dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
                    if (shouldLoadNative && activity.isUsable()) {
                        nativeContainer?.let { container ->
                            runCatching {
                                ITWingSDK.ads.loadNativeForDialog(
                                    activity = activity,
                                    container = container,
                                    placement = resolvedNativePlacement,
                                    nativeType = normalizedNativeType,
                                )
                            }.onFailure { error ->
                                container.visibility = View.GONE
                                SDKTelemetry.recordNonFatal(
                                    error,
                                    mapOf("operation" to "action_dialog_native_load", "placement" to resolvedNativePlacement),
                                )
                            }
                        }
                    }
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

    private fun Map<*, *>.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.toString()?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true)
            }
        }

    private fun normalizeNativeType(value: String?, placement: String?): NativeType? {
        val normalized = value?.trim()?.lowercase().orEmpty()
        if (
            normalized.isBlank() &&
            !placement.isNullOrBlank()
        ) {
            return NativeType.LARGE
        }
        if (
            normalized == "none" ||
            normalized == "no_native" ||
            normalized == "disabled" ||
            normalized == "off"
        ) {
            return null
        }
        return when {
            normalized.contains("small") -> NativeType.SMALL
            normalized.contains("large") -> NativeType.LARGE
            !placement.isNullOrBlank() -> NativeType.LARGE
            else -> null
        }
    }

    private fun safeCallback(name: String, callback: Runnable?) {
        if (callback == null) return
        runCatching { callback.run() }.onFailure { error ->
            SDKTelemetry.recordNonFatal(
                error,
                mapOf("operation" to "action_dialog_callback", "callback" to name),
            )
        }
    }
}
