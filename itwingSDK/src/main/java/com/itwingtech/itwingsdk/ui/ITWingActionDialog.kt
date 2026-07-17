package com.itwingtech.itwingsdk.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.play.core.review.ReviewManagerFactory
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
    private var restoreHiddenInlineAds: (() -> Unit)? = null
    private var reviewEnabledOverride: Boolean? = null
    private var feedbackEmailOverride: String? = null

    fun setReviewEnabled(enabled: Boolean): ITWingActionDialog = apply {
        reviewEnabledOverride = enabled
    }

    fun setFeedbackEmail(email: String?): ITWingActionDialog = apply {
        feedbackEmailOverride = email?.trim()?.takeIf { it.isNotBlank() }
    }

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
        val resolvedReviewEnabled = reviewEnabledOverride
            ?: defaults.boolean("review_enabled", defaults.boolean("host_dialog_review_enabled", true))
        val resolvedFeedbackEmail = feedbackEmailOverride
            ?: defaults.string("feedback_email", "review_email", "support_email", "contact_email", "developer_email")
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

        val isReviewSectionVisible = configureReviewSection(
            content = content,
            enabled = resolvedReviewEnabled,
            primaryColor = primaryColor,
            onPrimary = onPrimary,
            feedbackEmail = resolvedFeedbackEmail,
        )

        nativeContainer = content.findViewById(R.id.itwing_action_native_container)
        val shouldLoadNative =
            !isReviewSectionVisible &&
                !resolvedNativePlacement.isNullOrBlank() &&
                normalizedNativeType != null
        nativeContainer?.visibility = if (shouldLoadNative) View.VISIBLE else View.GONE
        if (shouldLoadNative) {
            restoreHiddenInlineAds = ITWingSDK.ads.hideInlineAdsForDialog(activity)
        }

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

        val alert = AlertDialog.Builder(activity)
            .setView(content)
            .create()
        alert.setOnCancelListener {
            deliverCallback("cancel_system", onCancel)
        }
        alert.setOnDismissListener {
            nativeContainer?.let { container -> ITWingSDK.ads.destroyNative(container) }
            nativeContainer = null
            restoreHiddenInlineAds?.invoke()
            restoreHiddenInlineAds = null
        }
        alert.setOnShowListener {
            GlassDialogWindow.apply(alert.window, activity.dialogWidth())
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
        dialog = alert
        runCatching {
            alert.show()
        }.onFailure { error ->
            dialog = null
            nativeContainer = null
            restoreHiddenInlineAds?.invoke()
            restoreHiddenInlineAds = null
            SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_show"))
            deliverCallback("cancel_show_failed", onCancel)
        }
    }

    fun dismiss() {
        nativeContainer?.let { container -> ITWingSDK.ads.destroyNative(container) }
        nativeContainer = null
        restoreHiddenInlineAds?.invoke()
        restoreHiddenInlineAds = null
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

    private fun configureReviewSection(
        content: View,
        enabled: Boolean,
        primaryColor: Int,
        onPrimary: Int,
        feedbackEmail: String?,
    ): Boolean {
        val section = content.findViewById<LinearLayout>(R.id.itwing_action_review_section)
        if (!enabled || hasSubmittedReviewFeedback()) {
            section.visibility = View.GONE
            return false
        }

        val feedbackInput = content.findViewById<EditText>(R.id.itwing_action_review_feedback)
        val sendFeedback = content.findViewById<MaterialButton>(R.id.itwing_action_review_send)
        val message = content.findViewById<TextView>(R.id.itwing_action_review_message)
        val ratingBar = content.findViewById<RatingBar>(R.id.itwing_action_ratingbar)

        section.visibility = View.VISIBLE
        sendFeedback.backgroundTintList = ColorStateList.valueOf(primaryColor)
        sendFeedback.setTextColor(onPrimary)
        sendFeedback.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))

        ratingBar.rating = 0f
        ratingBar.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (!fromUser || rating <= 0f) return@setOnRatingBarChangeListener
            if (rating >= 4f) {
                message.text = activity.getString(R.string.itwing_action_review_message)
                feedbackInput.visibility = View.GONE
                sendFeedback.visibility = View.GONE
                launchInAppReview()
            } else {
                feedbackInput.visibility = View.VISIBLE
                sendFeedback.visibility = View.VISIBLE
                message.text = activity.getString(R.string.itwing_action_review_low_hint)
                sendFeedback.setOnClickListener {
                    sendFeedbackEmail(feedbackEmail, rating, feedbackInput.text?.toString().orEmpty())
                }
            }
        }

        return true
    }

    private fun launchInAppReview() {
        if (!activity.isUsable()) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow()
            .addOnSuccessListener { reviewInfo ->
                if (!activity.isUsable()) return@addOnSuccessListener
                manager.launchReviewFlow(activity, reviewInfo)
                    .addOnCompleteListener {
                        markReviewFeedbackSubmitted()
                        Toast.makeText(activity, R.string.itwing_action_review_thanks, Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { error ->
                SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_in_app_review"))
                openPlayStore()
            }
    }

    private fun openPlayStore() {
        val packageName = activity.packageName
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, marketUri))
            markReviewFeedbackSubmitted()
        } catch (_: ActivityNotFoundException) {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                markReviewFeedbackSubmitted()
            }.onFailure { error ->
                SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_play_store_fallback"))
            }
        }
    }

    private fun sendFeedbackEmail(email: String?, rating: Float, feedback: String) {
        val appLabel = runCatching {
            activity.applicationInfo.loadLabel(activity.packageManager).toString()
        }.getOrDefault(activity.packageName)
        val body = activity.getString(
            R.string.itwing_action_review_email_body,
            rating.toInt().toString(),
            feedback,
            Build.VERSION.SDK_INT.toString(),
            Build.DEVICE,
            "${Build.MODEL} (${Build.PRODUCT})",
        )
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            email?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.itwing_action_review_email_subject, appLabel))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.itwing_action_review_send_feedback)))
            markReviewFeedbackSubmitted()
            Toast.makeText(activity, R.string.itwing_action_review_thanks, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_feedback_email"))
        }
    }

    private fun hasSubmittedReviewFeedback(): Boolean {
        return activity.getSharedPreferences("itwing_action_dialog", Activity.MODE_PRIVATE)
            .getBoolean("review_feedback_submitted_${activity.packageName}", false)
    }

    private fun markReviewFeedbackSubmitted() {
        activity.getSharedPreferences("itwing_action_dialog", Activity.MODE_PRIVATE)
            .edit()
            .putBoolean("review_feedback_submitted_${activity.packageName}", true)
            .apply()
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
