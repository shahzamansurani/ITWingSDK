package com.itwingtech.itwingsdk.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.SubscriptionProductConfig

internal object PurchaseDialog {
    fun show(
        activity: Activity,
        products: List<SubscriptionProductConfig>,
        primaryColor: Int,
        detailsProvider: (String, String) -> ProductDetails?,
        launcher: (String, (BillingResult) -> Unit) -> Unit,
        restore: (((Boolean) -> Unit) -> Unit)? = null,
        onResult: (BillingResult) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(failedResult("Activity is not available."))
            return
        }

        if (products.isEmpty()) {
            onResult(failedResult("No subscription or in-app products are configured."))
            return
        }

        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_purchase, null, false)
        val list = content.findViewById<LinearLayout>(R.id.itwing_purchase_products)
        val title = content.findViewById<TextView>(R.id.itwing_purchase_title)
        val subtitle = content.findViewById<TextView>(R.id.itwing_purchase_subtitle)
        val restoreButton = content.findViewById<MaterialButton>(R.id.itwing_purchase_restore)
        val cancelButton = content.findViewById<MaterialButton>(R.id.itwing_purchase_cancel)

        title.text = "Choose your premium plan"
        subtitle.text = "Secure Google Play checkout. Prices, products, and entitlements are loaded from the app's ITWing admin configuration."

        var dialog: AlertDialog? = null

        products.forEach { product ->
            val productType = product.billingProductType()
            val details = detailsProvider(product.productId.trim(), productType)
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_purchase_product, list, false) as LinearLayout
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            row.findViewById<TextView>(R.id.itwing_purchase_product_name).text =
                details?.title?.takeIf { it.isNotBlank() } ?: product.name.ifBlank { product.productId }
            row.findViewById<TextView>(R.id.itwing_purchase_product_price).apply {
                text = product.displayPrice(details)
                setTextColor(primaryColor)
            }
            row.findViewById<TextView>(R.id.itwing_purchase_product_period).text =
                if (productType == BillingClient.ProductType.INAPP) {
                    "One-time purchase"
                } else {
                    details?.subscriptionPeriodLabel() ?: product.billingPeriod.replaceFirstChar { it.titlecase() }
                }

            val description = details?.description?.takeIf { it.isNotBlank() }
                ?: product.metadata["description"]?.toString()?.takeIf { it.isNotBlank() }
                ?: product.entitlements.entries.joinToString(", ") { it.key.replace('_', ' ') }.takeIf { it.isNotBlank() }
                ?: "Secure Google Play checkout. Active purchases are restored automatically."
            row.findViewById<TextView>(R.id.itwing_purchase_product_description).text = description

            row.findViewById<MaterialButton>(R.id.itwing_purchase_product_button).apply {
                backgroundTintList = ColorStateList.valueOf(primaryColor)
                rippleColor = ColorStateList.valueOf(primaryColor.withAlpha(0x33))
                strokeColor = ColorStateList.valueOf(primaryColor)
                setOnClickListener {
                    if (activity.isFinishing || activity.isDestroyed) {
                        onResult(failedResult("Activity is not available."))
                        dialog?.dismiss()
                        return@setOnClickListener
                    }
                    isEnabled = false
                    text = "Opening Google Play..."
                    launcher(product.productId.trim()) { result ->
                        activity.runOnUiThread {
                            onResult(result)
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                dialog?.dismiss()
                            } else {
                                isEnabled = true
                                text = "Continue"
                            }
                        }
                    }
                }
            }

            list.addView(row)
        }

        restoreButton.apply {
            visibility = if (restore == null) View.GONE else View.VISIBLE
            setTextColor(primaryColor)
            strokeColor = ColorStateList.valueOf(primaryColor.withAlpha(0x55))
            rippleColor = ColorStateList.valueOf(primaryColor.withAlpha(0x22))
            setOnClickListener {
                isEnabled = false
                text = "Checking purchases..."
                restore?.invoke { active ->
                    activity.runOnUiThread {
                        isEnabled = true
                        text = if (active) "Purchase restored" else "Restore purchases"
                    }
                }
            }
        }

        cancelButton.apply {
            setTextColor(Color.rgb(100, 116, 139))
            rippleColor = ColorStateList.valueOf(primaryColor.withAlpha(0x18))
        }

        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.rgb(248, 250, 252), activity.dp(22).toFloat()))
            dialog.window?.setLayout(activity.dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
            cancelButton.setOnClickListener {
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun SubscriptionProductConfig.displayPrice(details: ProductDetails?): String {
        googleFormattedPrice(details)?.let { return it }
        if (price != null && !currency.isNullOrBlank()) {
            return "${currency} ${"%.2f".format(price)}"
        }
        return "Price from Google Play"
    }

    private fun googleFormattedPrice(details: ProductDetails?): String? {
        if (details == null) return null
        details.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
            ?.formattedPrice
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return runCatching {
            val oneTime = details.javaClass.methods
                .firstOrNull { it.name == "getOneTimePurchaseOfferDetails" }
                ?.invoke(details)
            oneTime?.javaClass?.methods
                ?.firstOrNull { it.name == "getFormattedPrice" }
                ?.invoke(oneTime) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun ProductDetails.subscriptionPeriodLabel(): String? {
        val phase = subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.lastOrNull()
            ?: return null
        return when (phase.billingPeriod) {
            "P1W" -> "Weekly subscription"
            "P1M" -> "Monthly subscription"
            "P3M" -> "3 month subscription"
            "P6M" -> "6 month subscription"
            "P1Y" -> "Yearly subscription"
            else -> "Subscription"
        }
    }

    private fun SubscriptionProductConfig.billingProductType(): String {
        val configured = productType.ifBlank {
            metadata["product_type"]?.toString().orEmpty()
        }.lowercase()
        return when (configured) {
            "inapp", "in_app", "one_time", "one-time", "one_time_product", "iap", "consumable", "non_consumable" -> BillingClient.ProductType.INAPP
            else -> if (billingPeriod == "lifetime") BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS
        }
    }

    private fun failedResult(message: String): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
            .setDebugMessage(message)
            .build()

    private fun rounded(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        strokeColor?.let { setStroke(1, it) }
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = dp(430)
        val horizontalMargin = dp(28)
        return minOf(maxWidth, screenWidth - horizontalMargin).coerceAtLeast(dp(300))
    }
}
