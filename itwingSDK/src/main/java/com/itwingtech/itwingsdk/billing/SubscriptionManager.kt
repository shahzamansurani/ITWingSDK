package com.itwingtech.itwingsdk.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.SubscriptionProductConfig
import com.itwingtech.itwingsdk.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionManager(
    private val configProvider: () -> ITWingConfig,
    private val repositoryProvider: () -> ConfigRepository?,
) {
    private var billingClient: BillingClient? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val productDetails = mutableMapOf<String, ProductDetails>()
    @Volatile
    private var lastBillingMessage: String? = null

    fun connect(activity: Activity, onReady: (() -> Unit)? = null) {
        if (billingClient?.isReady == true) {
            onReady?.invoke()
            return
        }

        billingClient = BillingClient.newBuilder(activity.applicationContext)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build(),
            )
            .enableAutoServiceReconnection()
            .setListener { _, purchases ->
                purchases?.forEach { purchase -> verifyPurchase(purchase) }
            }
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    restorePurchases()
                    onReady?.invoke()
                } else {
                    lastBillingMessage = result.debugMessage
                }
            }

            override fun onBillingServiceDisconnected() {
                lastBillingMessage = "Billing service disconnected"
            }
        })
    }

    fun launchPurchase(activity: Activity, productId: String): BillingResult? {
        val client = billingClient ?: return null
        val details = productDetails[productId] ?: return null
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId == productId }
        val offerToken = offerToken(details, adminProduct) ?: return null
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        return client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
    }

    fun products(): List<SubscriptionProductConfig> = configProvider().subscriptions.products

    fun isAdFree(): Boolean = repositoryProvider()?.isAdFreeEntitled() == true

    fun diagnostics(): Map<String, Any?> = mapOf(
        "connected" to (billingClient?.isReady == true),
        "configured_products" to configProvider().subscriptions.products.size,
        "loaded_products" to productDetails.size,
        "ad_free" to isAdFree(),
        "last_message" to lastBillingMessage,
    )

    fun restorePurchases(onComplete: ((Boolean) -> Unit)? = null) {
        val client = billingClient
        if (client == null || !client.isReady) {
            scope.launch(Dispatchers.IO) {
                runCatching { repositoryProvider()?.restoreSubscriptions() }
                onComplete?.invoke(isAdFree())
            }
            return
        }

        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                lastBillingMessage = result.debugMessage
                onComplete?.invoke(isAdFree())
                return@queryPurchasesAsync
            }

            if (purchases.isEmpty()) {
                scope.launch(Dispatchers.IO) {
                    runCatching { repositoryProvider()?.restoreSubscriptions() }
                    onComplete?.invoke(isAdFree())
                }
                return@queryPurchasesAsync
            }

            purchases.forEach { verifyPurchase(it) }
            onComplete?.invoke(isAdFree())
        }
    }

    private fun queryProducts() {
        val client = billingClient ?: return
        val products = configProvider().subscriptions.products
            .filter { it.store == "google_play" && it.productId.isNotBlank() }
            .distinctBy { it.productId }
            .map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.productId)
                    .setProductType(ProductType.SUBS)
                    .build()
            }

        if (products.isEmpty()) {
            return
        }

        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                this.productDetails.clear()
                detailsResult.productDetailsList.forEach { this.productDetails[it.productId] = it }
            }
        }
    }

    private fun verifyPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            lastBillingMessage = "Purchase is not completed yet: ${purchase.purchaseState}"
            return
        }
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId == productId }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repositoryProvider()?.verifySubscriptionPurchase(
                productId = productId,
                purchaseToken = purchase.purchaseToken,
                basePlanId = adminProduct?.basePlanId,
                offerId = adminProduct?.offerId,
                orderId = purchase.orderId,
                )
            }
            result.onSuccess {
                acknowledgeIfNeeded(purchase)
            }.onFailure {
                lastBillingMessage = it.message
            }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        val client = billingClient ?: return
        if (purchase.isAcknowledged) return

        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build(),
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                lastBillingMessage = result.debugMessage
            }
        }
    }

    private fun offerToken(details: ProductDetails, adminProduct: SubscriptionProductConfig?): String? {
        val offers = details.subscriptionOfferDetails ?: return null
        if (adminProduct?.basePlanId.isNullOrBlank() && adminProduct?.offerId.isNullOrBlank()) {
            return offers.firstOrNull()?.offerToken
        }

        return offers.firstOrNull { offer ->
            (adminProduct?.basePlanId.isNullOrBlank() || offer.basePlanId == adminProduct?.basePlanId)
                && (adminProduct?.offerId.isNullOrBlank() || offer.offerId == adminProduct?.offerId)
        }?.offerToken
    }
}
