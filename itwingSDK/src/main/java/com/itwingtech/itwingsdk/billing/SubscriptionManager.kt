package com.itwingtech.itwingsdk.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.SubscriptionProductConfig
import com.itwingtech.itwingsdk.data.ConfigRepository
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

class SubscriptionManager(
    private val configProvider: () -> ITWingConfig,
    private val repositoryProvider: () -> ConfigRepository?,
) {
    private var billingClient: BillingClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val readyCallbacks = CopyOnWriteArrayList<() -> Unit>()
    @Volatile
    private var lastBillingMessage: String? = null
    @Volatile
    private var connecting = false

    fun connect(activity: Activity, onReady: (() -> Unit)? = null) {
        if (billingClient?.isReady == true) {
            onReady?.invoke()
            return
        }
        onReady?.let { readyCallbacks.add(it) }
        if (connecting) return
        connecting = true

        billingClient = BillingClient.newBuilder(activity.applicationContext)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build(),
            )
            .enableAutoServiceReconnection()
            .setListener { result, purchases ->
                SDKTelemetry.track(
                    "purchase_updated",
                    mapOf(
                        "response_code" to result.responseCode,
                        "message" to result.debugMessage,
                        "purchase_count" to (purchases?.size ?: 0),
                    ),
                )
                purchases?.forEach { purchase -> verifyPurchase(purchase) }
            }
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    SDKTelemetry.track("billing_ready")
                    queryProducts {
                        drainReadyCallbacks()
                    }
                    restorePurchases()
                } else {
                    lastBillingMessage = result.debugMessage
                    SDKTelemetry.track("billing_setup_failed", mapOf("message" to result.debugMessage))
                    readyCallbacks.clear()
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                lastBillingMessage = "Billing service disconnected"
                SDKTelemetry.track("billing_disconnected")
            }
        })
    }

    fun launchPurchase(activity: Activity, productId: String): BillingResult {
        SDKTelemetry.track("purchase_flow_requested", mapOf("product_id" to productId))
        val client = billingClient ?: return failedResult(
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            "Billing client is not connected yet.",
        )
        if (!client.isReady) {
            return failedResult(
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                "Billing service is not ready yet.",
            )
        }

        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId == productId }
            ?: return failedResult(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "Product is not configured in ITWing admin: $productId",
            )
        val productType = adminProduct.billingProductType()
        val details = productDetails[productKey(productId, productType)]
            ?: productDetails[productId]
            ?: return failedResult(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "Product details are not loaded from Google Play yet: $productId",
            )

        val offerToken = offerToken(details, adminProduct)
        if (productType == ProductType.SUBS && offerToken.isNullOrBlank()) {
            return failedResult(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "No eligible Google Play subscription offer was returned for: $productId",
            )
        }

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (!offerToken.isNullOrBlank()) {
            productParamsBuilder.setOfferToken(offerToken)
        }
        val productParams = productParamsBuilder.build()

        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        SDKTelemetry.track(
            "purchase_flow_launched",
            mapOf(
                "product_id" to productId,
                "product_type" to if (productType == ProductType.INAPP) "inapp" else "subscription",
                "response_code" to result.responseCode,
                "message" to result.debugMessage,
            ),
        )
        return result
    }

    fun launchPurchaseWhenReady(activity: Activity, productId: String, onResult: (BillingResult) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(failedResult(BillingClient.BillingResponseCode.ERROR, "Activity is not available."))
            return
        }

        val completed = AtomicBoolean(false)
        fun complete(result: BillingResult) {
            if (completed.compareAndSet(false, true)) {
                onResult(result)
            }
        }

        mainHandler.postDelayed({
            complete(
                failedResult(
                    BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                    "Google Play Billing did not become ready in time. Check Play Store availability and try again.",
                ),
            )
        }, 10_000L)

        connect(activity) {
            ensureProductDetailsLoaded(productId) {
                val firstResult = launchPurchase(activity, productId)
                if (firstResult.shouldRetryAfterProductRefresh()) {
                    queryProducts {
                        complete(launchPurchase(activity, productId))
                    }
                } else {
                    complete(firstResult)
                }
            }
        }
    }

    fun products(): List<SubscriptionProductConfig> = configProvider().subscriptions.products

    fun showPurchaseDialog(activity: Activity, onResult: (BillingResult) -> Unit = {}) {
        val products = products()
            .filter { it.store == "google_play" && it.productId.isNotBlank() }
        SDKTelemetry.track("purchase_dialog_shown", mapOf("product_count" to products.size))
        connect(activity) {
            PurchaseDialog.show(
                activity = activity,
                products = products,
                detailsProvider = { productId, productType ->
                    productDetails[productKey(productId, productType)] ?: productDetails[productId]
                },
                launcher = { productId, result -> launchPurchaseWhenReady(activity, productId, result) },
                onResult = onResult,
            )
        }
    }

    fun isAdFree(): Boolean = repositoryProvider()?.isAdFreeEntitled() == true

    fun diagnostics(): Map<String, Any?> = mapOf(
        "connected" to (billingClient?.isReady == true),
        "configured_products" to configProvider().subscriptions.products.size,
        "loaded_products" to productDetails.size,
        "ad_free" to isAdFree(),
        "last_message" to lastBillingMessage,
    )

    fun restorePurchases(onComplete: ((Boolean) -> Unit)? = null) {
        SDKTelemetry.track("purchase_restore_requested")
        val client = billingClient
        if (client == null || !client.isReady) {
            scope.launch(Dispatchers.IO) {
                runCatching { repositoryProvider()?.restoreSubscriptions() }
                launch(Dispatchers.Main.immediate) { onComplete?.invoke(isAdFree()) }
            }
            return
        }

        val restored = mutableListOf<Purchase>()
        queryPurchases(client, ProductType.SUBS) {
            restored.addAll(it)
            queryPurchases(client, ProductType.INAPP) { inAppPurchases ->
                restored.addAll(inAppPurchases)
                if (restored.isEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { repositoryProvider()?.restoreSubscriptions() }
                        launch(Dispatchers.Main.immediate) {
                            SDKTelemetry.track("purchase_restore_completed", mapOf("active" to isAdFree(), "purchase_count" to 0))
                            onComplete?.invoke(isAdFree())
                        }
                    }
                    return@queryPurchases
                }
                restored.forEach { purchase -> verifyPurchase(purchase) }
                SDKTelemetry.track("purchase_restore_completed", mapOf("active" to isAdFree(), "purchase_count" to restored.size))
                onComplete?.invoke(isAdFree())
            }
        }
    }

    private fun queryProducts(onComplete: (() -> Unit)? = null) {
        val client = billingClient ?: return
        val products = configProvider().subscriptions.products
            .filter { it.store == "google_play" && it.productId.isNotBlank() }
            .groupBy { it.billingProductType() }

        if (products.isEmpty()) {
            onComplete?.invoke()
            return
        }
        productDetails.clear()
        var pendingQueries = products.size

        fun finishQuery() {
            pendingQueries -= 1
            if (pendingQueries <= 0) {
                onComplete?.invoke()
            }
        }

        products.forEach { (productType, adminProducts) ->
            val requestProducts = adminProducts
                .distinctBy { productKey(it.productId, productType) }
                .map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it.productId)
                        .setProductType(productType)
                        .build()
                }

            if (requestProducts.isEmpty()) {
                finishQuery()
                return@forEach
            }

            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(requestProducts).build(),
            ) { result, detailsResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    detailsResult.productDetailsList.forEach {
                        productDetails[productKey(it.productId, productType)] = it
                        productDetails[it.productId] = it
                    }
                } else {
                    lastBillingMessage = result.debugMessage
                }
                finishQuery()
            }
        }
    }

    private fun ensureProductDetailsLoaded(productId: String, onComplete: () -> Unit) {
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId == productId }
        val productType = adminProduct?.billingProductType()
        if (productType != null && (productDetails[productKey(productId, productType)] ?: productDetails[productId]) != null) {
            onComplete()
            return
        }
        queryProducts(onComplete)
    }

    private fun verifyPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            lastBillingMessage = "Purchase is not completed yet: ${purchase.purchaseState}"
            SDKTelemetry.track(
                "purchase_pending",
                mapOf("product_id" to productId, "purchase_state" to purchase.purchaseState),
            )
            return
        }
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId == productId }
        val productType = adminProduct?.billingProductType() ?: ProductType.SUBS
        val signatureVerified = GooglePlaySignatureValidator.verify(
            googlePlayLicenseKey(),
            purchase.originalJson,
            purchase.signature,
        )
        if (signatureVerified == false) {
            lastBillingMessage = "Google Play purchase signature verification failed."
            SDKTelemetry.track(
                "purchase_signature_invalid",
                mapOf("product_id" to productId, "product_type" to if (productType == ProductType.INAPP) "inapp" else "subscription"),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repositoryProvider()?.verifySubscriptionPurchase(
                productId = productId,
                purchaseToken = purchase.purchaseToken,
                productType = if (productType == ProductType.INAPP) "inapp" else "subscription",
                basePlanId = adminProduct?.basePlanId,
                offerId = adminProduct?.offerId,
                orderId = purchase.orderId,
                purchaseSignature = purchase.signature,
                purchaseOriginalJson = purchase.originalJson,
                )
            }
            result.onSuccess {
                SDKTelemetry.track(
                    "purchase_verified",
                    mapOf(
                        "product_id" to productId,
                        "product_type" to if (productType == ProductType.INAPP) "inapp" else "subscription",
                    ),
                )
                finishPurchaseIfNeeded(purchase, adminProduct)
            }.onFailure {
                lastBillingMessage = it.message
                SDKTelemetry.track(
                    "purchase_verify_failed",
                    mapOf("product_id" to productId, "message" to (it.message ?: "verification_failed")),
                )
            }
        }
    }

    private fun finishPurchaseIfNeeded(purchase: Purchase, adminProduct: SubscriptionProductConfig?) {
        if (adminProduct?.isConsumable() == true) {
            consumeIfNeeded(purchase)
            return
        }
        acknowledgeIfNeeded(purchase)
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
                SDKTelemetry.track("purchase_acknowledge_failed", mapOf("message" to result.debugMessage))
            } else {
                SDKTelemetry.track("purchase_acknowledged")
            }
        }
    }

    private fun consumeIfNeeded(purchase: Purchase) {
        val client = billingClient ?: return
        client.consumeAsync(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build(),
        ) { result, _ ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                lastBillingMessage = result.debugMessage
                SDKTelemetry.track("purchase_consume_failed", mapOf("message" to result.debugMessage))
            } else {
                SDKTelemetry.track("purchase_consumed")
            }
        }
    }

    private fun offerToken(details: ProductDetails, adminProduct: SubscriptionProductConfig?): String? {
        if (adminProduct?.billingProductType() == ProductType.INAPP) {
            adminProduct.offerId?.takeIf { it.isNotBlank() }?.let { desiredOfferId ->
                oneTimeOfferToken(details, desiredOfferId)?.let { return it }
            }
            return oneTimeOfferToken(details, null)
        }

        val offers = details.subscriptionOfferDetails ?: return null
        if (adminProduct?.basePlanId.isNullOrBlank() && adminProduct?.offerId.isNullOrBlank()) {
            return offers.firstOrNull()?.offerToken
        }

        return offers.firstOrNull { offer ->
            (adminProduct?.basePlanId.isNullOrBlank() || offer.basePlanId == adminProduct?.basePlanId)
                && (adminProduct?.offerId.isNullOrBlank() || offer.offerId == adminProduct?.offerId)
        }?.offerToken
    }

    private fun queryPurchases(client: BillingClient, productType: String, onComplete: (List<Purchase>) -> Unit) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(productType).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                lastBillingMessage = result.debugMessage
                onComplete(emptyList())
                return@queryPurchasesAsync
            }
            onComplete(purchases)
        }
    }

    private fun failedResult(code: Int, message: String): BillingResult {
        lastBillingMessage = message
        SDKTelemetry.track("purchase_flow_failed", mapOf("response_code" to code, "message" to message))
        return BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage(message)
            .build()
    }

    private fun BillingResult.shouldRetryAfterProductRefresh(): Boolean {
        return responseCode == BillingClient.BillingResponseCode.ERROR ||
            responseCode == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ||
            responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED
    }

    private fun productKey(productId: String, productType: String): String = "$productType:$productId"

    private fun SubscriptionProductConfig.billingProductType(): String {
        val configured = productType.ifBlank {
            metadata["product_type"]?.toString().orEmpty()
        }.lowercase()

        return when (configured) {
            "inapp", "in_app", "one_time", "one-time", "one_time_product", "iap", "consumable", "non_consumable" -> ProductType.INAPP
            else -> if (billingPeriod == "lifetime") ProductType.INAPP else ProductType.SUBS
        }
    }

    private fun SubscriptionProductConfig.isConsumable(): Boolean {
        return when (val value = metadata["consumable"]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
            else -> false
        }
    }

    private fun oneTimeOfferToken(details: ProductDetails, desiredOfferId: String?): String? {
        return runCatching {
            val method = details.javaClass.methods.firstOrNull { it.name == "getOneTimePurchaseOfferDetailsList" }
                ?: return@runCatching null
            val offers = method.invoke(details) as? Iterable<*> ?: return@runCatching null
            offers.firstNotNullOfOrNull { offer ->
                val offerId = runCatching {
                    offer?.javaClass?.methods?.firstOrNull { it.name == "getOfferId" }?.invoke(offer) as? String
                }.getOrNull()
                val purchaseOptionId = runCatching {
                    offer?.javaClass?.methods?.firstOrNull { it.name == "getPurchaseOptionId" }?.invoke(offer) as? String
                }.getOrNull()
                val matches = desiredOfferId.isNullOrBlank() ||
                    offerId == desiredOfferId ||
                    purchaseOptionId == desiredOfferId
                if (!matches) null else runCatching {
                    offer?.javaClass?.methods?.firstOrNull { it.name == "getOfferToken" }?.invoke(offer) as? String
                }.getOrNull()
            }
        }.getOrNull()
    }

    private fun googlePlayLicenseKey(): String? {
        val app = configProvider().app
        return listOf(
            app["google_play_license_key"],
            app["play_license_key"],
            app["googlePlayLicenseKey"],
        ).firstNotNullOfOrNull { value ->
            value?.toString()?.replace("\\s".toRegex(), "")?.takeIf { it.isNotBlank() }
        }
    }

    private fun drainReadyCallbacks() {
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { callback ->
            runCatching { callback() }
        }
    }
}
