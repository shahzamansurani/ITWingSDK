package com.itwingtech.itwingsdk.billing

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.core.SubscriptionProductConfig

internal object PurchaseDialog {
    fun show(
        activity: Activity,
        products: List<SubscriptionProductConfig>,
        detailsProvider: (String, String) -> ProductDetails?,
        launcher: (String, (BillingResult) -> Unit) -> Unit,
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

        val content = ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 20)
        }
        content.addView(list)

        val title = TextView(activity).apply {
            text = "Choose a plan"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(15, 23, 42))
        }
        list.addView(title)

        val subtitle = TextView(activity).apply {
            text = "Available purchases are controlled from ITWing admin and verified after checkout."
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, 6, 0, 18)
        }
        list.addView(subtitle)

        var dialog: AlertDialog? = null

        products.forEach { product ->
            val productType = product.billingProductType()
            val details = detailsProvider(product.productId, productType)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }

            row.addView(TextView(activity).apply {
                text = details?.title?.takeIf { it.isNotBlank() } ?: product.name.ifBlank { product.productId }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(15, 23, 42))
            })

            row.addView(TextView(activity).apply {
                text = buildString {
                    append(product.displayPrice(details))
                    append(" - ")
                    append(if (productType == BillingClient.ProductType.INAPP) "One-time purchase" else product.billingPeriod.replaceFirstChar { it.titlecase() })
                }
                textSize = 13f
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, 4, 0, 10)
            })

            val button = MaterialButton(activity).apply {
                text = "Continue"
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener {
                    isEnabled = false
                    text = "Opening Google Play..."
                    launcher(product.productId) { result ->
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
            row.addView(button)

            list.addView(row)
            list.addView(View(activity).apply {
                setBackgroundColor(Color.rgb(226, 232, 240))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }

        list.addView(ProgressBar(activity).apply {
            visibility = View.GONE
        })

        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.white)
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
}
