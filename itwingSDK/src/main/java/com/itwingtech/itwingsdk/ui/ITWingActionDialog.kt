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
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.itwingtech.itwingsdk.core.StartupTrace
import java.util.concurrent.atomic.AtomicBoolean

internal object ExitEngagementPolicy {
    const val HIGH_RATING_MINIMUM = 4f

    fun isHighRating(rating: Float): Boolean = rating >= HIGH_RATING_MINIMUM
}

class ITWingActionDialog internal constructor(
    private val activity: Activity,
    private val defaultsProvider: () -> Map<*, *>,
    private val primaryColorProvider: () -> Int,
) {
    private var dialog: AlertDialog? = null
    private var nativeContainer: FrameLayout? = null
    private var restoreHiddenInlineAds: (() -> Unit)? = null
    private var reviewEnabledOverride: Boolean? = null
    private var feedbackModeOverride: Boolean? = null
    private var feedbackEmailOverride: String? = null
    private var exitFlowOverride: Boolean? = null
    private var feedbackSubmitInFlight = false

    private enum class EngagementMode {
        FIRST_RATING,
        FEEDBACK,
        FEEDBACK_SENDING,
        NORMAL_EXIT,
        REVIEW_HANDOFF,
    }

    private var engagementMode = EngagementMode.NORMAL_EXIT

    fun setReviewEnabled(enabled: Boolean): ITWingActionDialog = apply {
        reviewEnabledOverride = enabled
    }

    internal fun setFeedbackMode(enabled: Boolean): ITWingActionDialog = apply {
        feedbackModeOverride = enabled
    }

    internal fun setExitFlow(enabled: Boolean?): ITWingActionDialog = apply {
        exitFlowOverride = enabled
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
        // Exit/feedback primary actions use the approved fixed contrast rule:
        // Admin primary background with white action text.
        val onPrimary = Color.WHITE
        // Keep the visible dialog surface opaque. The window itself remains
        // transparent only so the normal dim layer surrounds the SDK card.
        SdkDialogTheme.apply(content, activity)
        val titleColor = sdkColor("dialog_title_color", "text_color", fallback = Color.rgb(17, 24, 39))
        val descriptionColor = sdkColor("dialog_description_color", "secondary_text_color", fallback = Color.rgb(107, 114, 128))
        val closeColor = sdkColor("dialog_close_icon_color", "icon_tint_color", fallback = descriptionColor)
        val positiveButtonColor = sdkColor("dialog_positive_button_color", fallback = primaryColor)
        val positiveTextColor = Color.WHITE
        val positiveStrokeColor = sdkColor("dialog_positive_stroke_color", fallback = positiveButtonColor)
        // Secondary action stays neutral/outlined. The semantic color aliases
        // for this role may otherwise resolve a text color as a dark fill.
        val negativeButtonColor = Color.TRANSPARENT
        val negativeTextColor = sdkColor("dialog_negative_text_color", fallback = primaryColor)
        val negativeStrokeColor = sdkColor("dialog_negative_stroke_color", fallback = ColorUtils.setAlphaComponent(primaryColor, 120))
        val resolvedTitle = title ?: defaults.string("title", "dialog_title", "host_dialog_title") ?: "Continue?"
        val resolvedDescription = description ?: defaults.string("description", "body", "message", "host_dialog_description") ?: "Choose how you want to continue."
        val resolvedPositive = positiveText ?: defaults.string("positive_text", "positiveText", "positive_button", "positiveButton", "host_dialog_positive_text") ?: "Continue"
        val resolvedNegative = negativeText ?: defaults.string("negative_text", "negativeText", "negative_button", "negativeButton", "host_dialog_negative_text") ?: "Cancel"
        val configuredMode = defaults.string("mode", "dialog_mode", "type")?.lowercase()
        val legacyExitLabels = (
            resolvedPositive.equals("exit", ignoreCase = true) &&
                resolvedNegative.equals("cancel", ignoreCase = true)
            ) || (
            resolvedPositive.equals("cancel", ignoreCase = true) &&
                resolvedNegative.equals("exit", ignoreCase = true)
            )
        val resolvedExitFlow = exitFlowOverride ?: when {
            configuredMode == "exit" -> true
            configuredMode == "feedback" -> false
            legacyExitLabels -> true
            else -> false
        }
        val firstTimeExit = resolvedExitFlow && !hasSubmittedReviewFeedback()
        val resolvedFeedbackMode = feedbackModeOverride
            ?: configuredMode == "feedback"
            || resolvedPositive.equals("send feedback", ignoreCase = true)
            || resolvedTitle.equals("send feedback", ignoreCase = true)
        val resolvedReviewEnabled = if (resolvedExitFlow) {
            firstTimeExit
        } else {
            reviewEnabledOverride
                ?: defaults.boolean("review_enabled", defaults.boolean("host_dialog_review_enabled", true))
        }
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

        content.findViewById<TextView>(R.id.itwing_action_title).apply {
            text = resolvedTitle
            setTextColor(titleColor)
        }
        content.findViewById<TextView>(R.id.itwing_action_description).apply {
            text = resolvedDescription
            setTextColor(descriptionColor)
        }
        content.findViewById<TextView>(R.id.itwing_action_close).apply {
            setTextColor(closeColor)
            setOnClickListener {
                dismiss()
                deliverCallback("cancel_close", onCancel)
            }
        }

        configureReviewSection(
            content = content,
            enabled = resolvedReviewEnabled,
            feedbackMode = resolvedFeedbackMode,
            primaryColor = primaryColor,
            onPrimary = onPrimary,
        )
        engagementMode = when {
            firstTimeExit -> EngagementMode.FIRST_RATING
            resolvedExitFlow -> EngagementMode.NORMAL_EXIT
            resolvedFeedbackMode -> EngagementMode.FEEDBACK
            else -> EngagementMode.NORMAL_EXIT
        }
        StartupTrace.event(
            activity,
            "EXIT_DIALOG_STATE",
            "state=${engagementMode.name} adAllowed=${engagementMode != EngagementMode.FIRST_RATING}",
        )

        nativeContainer = content.findViewById(R.id.itwing_action_native_container)
        nativeContainer?.let { container ->
            // The dialog shell already owns the 18dp content inset. The native
            // renderer owns the ad card inset, so keep the ad card at the same
            // available width as the approved Native layout elsewhere instead of
            // applying two horizontal insets.
            (container.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                val inset = (18 * activity.resources.displayMetrics.density).toInt()
                params.leftMargin = -inset
                params.rightMargin = -inset
                params.marginStart = -inset
                params.marginEnd = -inset
                container.layoutParams = params
            }
        }
        val nativeConfigured =
            !resolvedNativePlacement.isNullOrBlank() &&
                normalizedNativeType != null
        var exitAdStarted = false
        StartupTrace.event(
            activity,
            "EXIT_DIALOG_CONFIG",
            "nativePlacement=${resolvedNativePlacement ?: "none"} nativeType=${normalizedNativeType?.name ?: "none"} nativeConfigured=$nativeConfigured adAllowedInitially=${!firstTimeExit}",
        )
        nativeContainer?.visibility = View.GONE
        if (engagementMode == EngagementMode.FIRST_RATING) {
            StartupTrace.event(
                activity,
                "EXIT_AD_LOAD_GATE",
                "state=FIRST_RATING loadExitAd=false admob=0 custom=0 preload=0 shimmer=0",
            )
        }

        val feedbackInput = content.findViewById<EditText>(R.id.itwing_action_review_feedback)
        val ratingBar = content.findViewById<RatingBar>(R.id.itwing_action_ratingbar)
        val reviewMessage = content.findViewById<TextView>(R.id.itwing_action_review_message)
        val reviewSendButton = content.findViewById<MaterialButton>(R.id.itwing_action_review_send)
        val positiveButton = content.findViewById<MaterialButton>(R.id.itwing_action_positive)
        val positiveProgress = content.findViewById<ProgressBar>(R.id.itwing_action_positive_progress)
        val positiveContainer = content.findViewById<View>(R.id.itwing_action_positive_container)

        fun startExitAdIfAllowed() {
            if (
                exitAdStarted ||
                !nativeConfigured ||
                engagementMode == EngagementMode.FIRST_RATING ||
                engagementMode == EngagementMode.REVIEW_HANDOFF ||
                !activity.isUsable()
            ) return
            exitAdStarted = true
            nativeContainer?.visibility = View.VISIBLE
            restoreHiddenInlineAds = ITWingSDK.ads.hideInlineAdsForDialog(activity)
            StartupTrace.event(
                activity,
                "EXIT_AD_LOAD_ATTEMPT",
                "count=1 state=${engagementMode.name} placement=$resolvedNativePlacement",
            )
            StartupTrace.event(
                activity,
                "EXIT_DIALOG_NATIVE_REQUEST",
                "placement=$resolvedNativePlacement type=${normalizedNativeType?.name}",
            )
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

        fun setPositiveLoading(loading: Boolean) {
            positiveButton.isEnabled = !loading
            positiveButton.alpha = if (loading) 0.72f else 1f
            positiveProgress.visibility = if (loading) View.VISIBLE else View.GONE
            positiveProgress.indeterminateTintList = ColorStateList.valueOf(positiveTextColor)
            positiveButton.text = if (loading) "" else if (resolvedExitFlow && engagementMode == EngagementMode.FEEDBACK) {
                activity.getString(R.string.itwing_action_send_exit)
            } else {
                resolvedPositive
            }
            positiveButton.contentDescription = if (loading) {
                activity.getString(R.string.itwing_action_feedback_sending)
            } else {
                positiveButton.text
            }
        }

        fun submitFeedback(rating: Float, exitAfterSuccess: Boolean) {
            if (feedbackSubmitInFlight) return
            val feedback = feedbackInput.text?.toString()?.trim().orEmpty()
            if (feedback.isBlank()) {
                feedbackInput.error = activity.getString(R.string.itwing_action_feedback_required)
                feedbackInput.requestFocus()
                return
            }
            feedbackSubmitInFlight = true
            engagementMode = EngagementMode.FEEDBACK_SENDING
            reviewSendButton.isEnabled = false
            setPositiveLoading(true)
            sendFeedbackEmail(
                email = resolvedFeedbackEmail,
                rating = rating,
                feedback = feedback,
                onSuccess = {
                    feedbackSubmitInFlight = false
                    markReviewFeedbackSubmitted()
                    setPositiveLoading(false)
                    dismiss()
                    if (exitAfterSuccess) {
                        deliverCallback("positive", onPositive)
                    }
                },
                onFailure = {
                    feedbackSubmitInFlight = false
                    engagementMode = EngagementMode.FEEDBACK
                    reviewSendButton.isEnabled = true
                    setPositiveLoading(false)
                    feedbackInput.error = activity.getString(R.string.itwing_action_feedback_failed)
                },
            )
        }

        positiveButton.apply {
            text = resolvedPositive
            backgroundTintList = ColorStateList.valueOf(positiveButtonColor)
            setTextColor(positiveTextColor)
            strokeColor = ColorStateList.valueOf(positiveStrokeColor)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(positiveButtonColor, 44))
            visibility = View.VISIBLE
            setOnClickListener {
                if (resolvedFeedbackMode) {
                    submitFeedback(ratingBar.rating, exitAfterSuccess = false)
                } else if (engagementMode == EngagementMode.FEEDBACK) {
                    submitFeedback(ratingBar.rating, exitAfterSuccess = true)
                } else {
                    dismiss()
                    deliverCallback("positive", onPositive)
                }
            }
        }
        positiveContainer.visibility = View.VISIBLE
        positiveProgress.indeterminateTintList = ColorStateList.valueOf(positiveTextColor)

        if (resolvedReviewEnabled && !resolvedFeedbackMode) {
            ratingBar.setOnRatingBarChangeListener { _, rating, fromUser ->
                if (!fromUser || rating <= 0f) return@setOnRatingBarChangeListener
                if (ExitEngagementPolicy.isHighRating(rating)) {
                    engagementMode = EngagementMode.REVIEW_HANDOFF
                    reviewMessage.text = activity.getString(R.string.itwing_action_review_message)
                    feedbackInput.visibility = View.GONE
                    reviewSendButton.visibility = View.GONE
                    positiveContainer.visibility = View.GONE
                    launchInAppReview(
                        onHandled = if (firstTimeExit) {
                            {
                                dismiss()
                                deliverCallback("positive", onPositive)
                            }
                        } else {
                            null
                        },
                    )
                } else {
                    engagementMode = EngagementMode.FEEDBACK
                    startExitAdIfAllowed()
                    feedbackInput.visibility = View.VISIBLE
                    reviewMessage.text = activity.getString(R.string.itwing_action_review_low_hint)
                    if (firstTimeExit) {
                        reviewSendButton.visibility = View.GONE
                        positiveContainer.visibility = View.VISIBLE
                        positiveButton.visibility = View.VISIBLE
                        positiveButton.text = activity.getString(R.string.itwing_action_send_exit)
                    } else {
                        reviewSendButton.visibility = View.VISIBLE
                        reviewSendButton.setOnClickListener {
                            submitFeedback(rating, exitAfterSuccess = false)
                        }
                    }
                }
            }
        } else if (resolvedFeedbackMode) {
            reviewSendButton.visibility = View.GONE
        }

        content.findViewById<MaterialButton>(R.id.itwing_action_negative).apply {
            text = resolvedNegative
            backgroundTintList = ColorStateList.valueOf(negativeButtonColor)
            setTextColor(negativeTextColor)
            strokeColor = ColorStateList.valueOf(negativeStrokeColor)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(negativeTextColor, 28))
            // Exit/feedback dialogs already have the top-right close action.
            // Keep the bottom row focused on its single primary action.
            visibility = if (resolvedExitFlow || resolvedFeedbackMode) View.GONE else View.VISIBLE
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
            SdkDialogWindow.apply(alert.window, activity.dialogWidth())
            StartupTrace.event(activity, "EXIT_DIALOG_SHOW", "nativeContainer=${nativeContainer != null} nativeConfigured=$nativeConfigured adAllowed=${!firstTimeExit}")
            if (!firstTimeExit) startExitAdIfAllowed()
        }
        dialog = alert
        runCatching {
            alert.show()
            content.post {
                StartupTrace.event(
                    activity,
                    "EXIT_DIALOG_LAYOUT",
                    "dialogWidth=${content.width} nativeWidth=${nativeContainer?.width ?: 0} nativeHeight=${nativeContainer?.height ?: 0} nativeType=${normalizedNativeType?.name ?: "none"}",
                )
            }
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
        feedbackMode: Boolean,
        primaryColor: Int,
        onPrimary: Int,
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
        val ratingGold = activity.getColor(R.color.orange)
        val ratingNeutral = activity.getColor(R.color.grey)

        section.visibility = View.VISIBLE
        sendFeedback.backgroundTintList = ColorStateList.valueOf(primaryColor)
        sendFeedback.setTextColor(onPrimary)
        sendFeedback.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))
        ratingBar.progressTintList = ColorStateList.valueOf(ratingGold)
        ratingBar.secondaryProgressTintList = ColorStateList.valueOf(ratingGold)
        ratingBar.backgroundTintList = ColorStateList.valueOf(ratingNeutral)

        if (feedbackMode) {
            ratingBar.visibility = View.GONE
            feedbackInput.visibility = View.VISIBLE
            sendFeedback.visibility = View.GONE
            message.text = activity.getString(R.string.itwing_action_review_low_hint)
            return true
        }

        ratingBar.rating = 0f
        feedbackInput.visibility = View.GONE
        sendFeedback.visibility = View.GONE
        message.text = activity.getString(R.string.itwing_action_review_message)
        return true
    }

    private fun launchInAppReview(onHandled: (() -> Unit)? = null) {
        if (!activity.isUsable()) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow()
            .addOnSuccessListener { reviewInfo ->
                if (!activity.isUsable()) return@addOnSuccessListener
                manager.launchReviewFlow(activity, reviewInfo)
                    .addOnCompleteListener {
                        markReviewFeedbackSubmitted()
                        if (!activity.isUsable()) return@addOnCompleteListener
                        Toast.makeText(activity, R.string.itwing_action_review_thanks, Toast.LENGTH_SHORT).show()
                        onHandled?.invoke()
                    }
            }
            .addOnFailureListener { error ->
                SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_in_app_review"))
                openPlayStore(onHandled)
            }
    }

    private fun openPlayStore(onHandled: (() -> Unit)? = null) {
        val packageName = activity.packageName
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, marketUri))
            markReviewFeedbackSubmitted()
            onHandled?.invoke()
        } catch (_: ActivityNotFoundException) {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                markReviewFeedbackSubmitted()
                onHandled?.invoke()
            }.onFailure { error ->
                SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_play_store_fallback"))
            }
        }
    }

    private fun sendFeedbackEmail(
        email: String?,
        rating: Float,
        feedback: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
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
            Toast.makeText(activity, R.string.itwing_action_review_thanks, Toast.LENGTH_SHORT).show()
            onSuccess()
        }.onFailure { error ->
            SDKTelemetry.recordNonFatal(error, mapOf("operation" to "action_dialog_feedback_email"))
            onFailure()
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

    private fun sdkColor(vararg keys: String, fallback: Int): Int {
        keys.forEach { key ->
            ITWingSDK.getSemanticColor(key, defaultValue = "#%08X".format(fallback), context = activity)
                .takeIf { it.isNotBlank() }
                ?.let { value -> runCatching { Color.parseColor(value) }.getOrNull() }
                ?.let { return it }
        }
        return fallback
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
