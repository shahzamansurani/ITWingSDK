package com.itwingtech.itwingsdk.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.use
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.BillingClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.SubscriptionPlanInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicBoolean

class ITWingPremiumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshInFlight = AtomicBoolean(false)
    private var observedLifecycle: Lifecycle? = null
    private var customTitle: String? = null
    private var customDescription: String? = null
    private var showRestore = true

    private val icon: ImageView
    private val title: TextView
    private val description: TextView
    private val badge: TextView
    private val details: View
    private val planText: TextView
    private val billingText: TextView
    private val priceText: TextView
    private val expiryText: TextView
    private val message: TextView
    private val progress: ProgressBar
    private val purchaseButton: MaterialButton
    private val restoreButton: MaterialButton

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            refresh(restoreFromGooglePlay = true)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            detachLifecycleObserver()
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_itwing_premium, this, true)
        icon = findViewById(R.id.itwing_premium_icon)
        title = findViewById(R.id.itwing_premium_title)
        description = findViewById(R.id.itwing_premium_description)
        badge = findViewById(R.id.itwing_premium_badge)
        details = findViewById(R.id.itwing_premium_details)
        planText = findViewById(R.id.itwing_premium_plan)
        billingText = findViewById(R.id.itwing_premium_billing)
        priceText = findViewById(R.id.itwing_premium_price)
        expiryText = findViewById(R.id.itwing_premium_expiry)
        message = findViewById(R.id.itwing_premium_message)
        progress = findViewById(R.id.itwing_premium_progress)
        purchaseButton = findViewById(R.id.itwing_premium_purchase)
        restoreButton = findViewById(R.id.itwing_premium_restore)

        context.obtainStyledAttributes(attrs, R.styleable.ITWingPremiumView, defStyleAttr, 0).use { values ->
            customTitle = values.getString(R.styleable.ITWingPremiumView_ITWingPremiumTitle)
            customDescription = values.getString(R.styleable.ITWingPremiumView_ITWingPremiumDescription)
            showRestore = values.getBoolean(R.styleable.ITWingPremiumView_ITWingPremiumShowRestore, true)
        }

        radius = dp(14).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(Color.WHITE)
        strokeWidth = dp(1)
        setStrokeColor(Color.rgb(226, 232, 240))

        purchaseButton.setOnClickListener { launchPurchase() }
        restoreButton.setOnClickListener { openSubscriptionManagement() }
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachLifecycleObserver()
        refresh(restoreFromGooglePlay = false)
    }

    override fun onDetachedFromWindow() {
        detachLifecycleObserver()
        mainHandler.removeCallbacksAndMessages(null)
        refreshInFlight.set(false)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        refresh(restoreFromGooglePlay = true)
    }

    private fun launchPurchase() {
        val activity = context.findActivity()
        if (activity == null || !activity.isUsable()) {
            showMessage(context.getString(R.string.premium_screen_closing))
            return
        }
        val active = ITWingSDK.isAdFree() || ITWingSDK.getCurrentSubscription()?.active == true
        if (active && !ITWingSDK.canChangeSubscriptionPlan()) {
            render()
            return
        }
        setBusy(true, context.getString(R.string.premium_checking))
        runCatching {
            ITWingSDK.showPurchaseDialog(activity) { result ->
                runOnMain {
                    setBusy(false)
                    if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                        showMessage(null)
                    } else if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        showMessage(result.debugMessage.takeIf(String::isNotBlank))
                    }
                    render()
                }
            }
        }.onFailure { error ->
            runOnMain {
                setBusy(false)
                showMessage(error.message)
                render()
            }
        }
    }

    private fun refresh(restoreFromGooglePlay: Boolean, announceResult: Boolean = false) {
        runOnMain {
            if (!isAttachedToWindow) return@runOnMain
            applyPrimaryColor()
            render()
            if (!restoreFromGooglePlay || !refreshInFlight.compareAndSet(false, true)) return@runOnMain

            setBusy(true, context.getString(R.string.premium_checking))
            ITWingSDK.restorePurchases { restored ->
                runOnMain {
                    refreshInFlight.set(false)
                    if (!isAttachedToWindow) return@runOnMain
                    setBusy(false)
                    if (!announceResult) showMessage(null)
                    render()
                    if (announceResult) {
                        showMessage(
                            context.getString(
                                if (restored) R.string.premium_restored else R.string.premium_not_found
                            )
                        )
                    }
                }
            }
        }
    }

    private fun render() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::render)
            return
        }
        applyPrimaryColor()
        title.text = customTitle ?: context.getString(R.string.premium_title)
        description.text = customDescription ?: context.getString(R.string.premium_description)

        val plan = ITWingSDK.getCurrentSubscription()
        val active = plan?.active == true || (plan == null && ITWingSDK.isAdFree())
        when {
            active -> renderActive(plan)
            plan != null -> renderExpired(plan)
            else -> renderAvailable()
        }
    }

    private fun renderActive(plan: SubscriptionPlanInfo?) {
        badge.text = context.getString(R.string.premium_status_active)
        details.isVisible = plan != null
        plan?.let(::renderDetails)
        val canChangePlan = ITWingSDK.canChangeSubscriptionPlan()
        purchaseButton.text = context.getString(
            if (canChangePlan) R.string.premium_change_plan else R.string.premium_active_button
        )
        purchaseButton.isEnabled = canChangePlan
        purchaseButton.alpha = if (canChangePlan) 1f else 0.72f
        restoreButton.text = context.getString(R.string.premium_manage_subscription)
        restoreButton.isVisible = plan?.productType?.equals("inapp", ignoreCase = true) != true
    }

    private fun renderExpired(plan: SubscriptionPlanInfo) {
        badge.text = context.getString(R.string.premium_status_expired)
        details.isVisible = true
        renderDetails(plan)
        purchaseButton.text = context.getString(R.string.premium_renew)
        purchaseButton.isEnabled = true
        purchaseButton.alpha = 1f
        restoreButton.text = context.getString(R.string.premium_manage_subscription)
        restoreButton.isVisible = !plan.productType.equals("inapp", ignoreCase = true)
    }

    private fun renderAvailable() {
        badge.text = context.getString(R.string.premium_status_available)
        details.isVisible = false
        purchaseButton.text = context.getString(R.string.premium_purchase)
        purchaseButton.isEnabled = true
        purchaseButton.alpha = 1f
        restoreButton.isVisible = false
    }

    private fun renderDetails(plan: SubscriptionPlanInfo) {
        planText.text = "${context.getString(R.string.premium_plan_label)}: ${plan.name}"
        val productType = if (plan.productType.equals("inapp", ignoreCase = true)) {
            context.getString(R.string.premium_one_time)
        } else {
            context.getString(R.string.premium_subscription)
        }
        val period = plan.billingPeriod.replace('_', ' ').replaceFirstChar { it.titlecase() }
        billingText.text = "${context.getString(R.string.premium_billing_label)}: $productType | $period"
        priceText.text = "${context.getString(R.string.premium_price_label)}: ${plan.displayPrice()}"
        expiryText.text = "${context.getString(R.string.premium_expiry_label)}: ${plan.displayExpiry()}"
    }

    private fun SubscriptionPlanInfo.displayPrice(): String {
        return formattedPrice?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.premium_price_play)
    }

    private fun SubscriptionPlanInfo.displayExpiry(): String {
        val expiry = expiresAt
        return when {
            !expiry.isNullOrBlank() -> formatExpiry(expiry)
            productType.equals("inapp", ignoreCase = true) -> context.getString(R.string.premium_lifetime)
            else -> context.getString(R.string.premium_expiry_syncing)
        }
    }

    private fun formatExpiry(value: String): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault(value)

    private fun setBusy(busy: Boolean, text: String? = null) {
        progress.isVisible = busy
        val active = ITWingSDK.isAdFree() || ITWingSDK.getCurrentSubscription()?.active == true
        purchaseButton.isEnabled = !busy && (!active || ITWingSDK.canChangeSubscriptionPlan())
        restoreButton.isEnabled = !busy
        if (busy && !text.isNullOrBlank()) showMessage(text)
    }

    private fun showMessage(value: String?) {
        message.text = value.orEmpty()
        message.isVisible = !value.isNullOrBlank()
    }

    private fun openSubscriptionManagement() {
        val activity = context.findActivity()
        if (activity == null || !activity.isUsable()) {
            showMessage(context.getString(R.string.premium_screen_closing))
            return
        }

        val plan = ITWingSDK.getCurrentSubscription()
        val packageName = activity.packageName
        val url = if (plan?.productId.isNullOrBlank()) {
            "https://play.google.com/store/account/subscriptions"
        } else {
            "https://play.google.com/store/account/subscriptions?sku=${Uri.encode(plan?.productId)}&package=${Uri.encode(packageName)}"
        }

        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            showMessage(error.message ?: context.getString(R.string.premium_manage_subscription_failed))
        }
    }

    private fun applyPrimaryColor() {
        val primary = listOf("primary", "primary_color")
            .firstNotNullOfOrNull { key -> ITWingSDK.getColor(key).takeIf(String::isNotBlank) }
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: Color.rgb(37, 99, 235)
        val onPrimary = if (ColorUtils.calculateLuminance(primary) > 0.58) Color.BLACK else Color.WHITE
        icon.backgroundTintList = ColorStateList.valueOf(primary)
        badge.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28))
        badge.setTextColor(primary)
        purchaseButton.backgroundTintList = ColorStateList.valueOf(primary)
        purchaseButton.setTextColor(onPrimary)
        restoreButton.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 110))
        restoreButton.setTextColor(primary)
        progress.indeterminateTintList = ColorStateList.valueOf(primary)
    }

    private fun attachLifecycleObserver() {
        if (observedLifecycle != null) return
        val lifecycle = (context.findActivity() as? LifecycleOwner)?.lifecycle ?: return
        observedLifecycle = lifecycle
        lifecycle.addObserver(lifecycleObserver)
    }

    private fun detachLifecycleObserver() {
        observedLifecycle?.removeObserver(lifecycleObserver)
        observedLifecycle = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
