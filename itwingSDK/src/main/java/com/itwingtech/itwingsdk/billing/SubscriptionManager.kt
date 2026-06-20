package com.itwingtech.itwingsdk.billing

import android.app.Activity
import android.graphics.Color
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
    private val onEntitlementChanged: (Boolean) -> Unit = {},
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
            if (onReady != null) {
                queryProducts { onReady.invoke() }
            }
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
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        purchases?.forEach { purchase -> verifyPurchase(purchase) }
                    }

                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        SDKTelemetry.track("purchase_already_owned_restore_requested")
                        restorePurchases()
                    }

                    BillingClient.BillingResponseCode.USER_CANCELED -> {
                        lastBillingMessage = "Purchase was canceled by the user."
                    }

                    else -> {
                        lastBillingMessage = result.debugMessage
                        purchases?.forEach { purchase -> verifyPurchase(purchase) }
                    }
                }
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
                    drainReadyCallbacks()
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

        val requestedProductId = productId.trim()
        if (requestedProductId in ownedProductIds()) {
            restorePurchases()
            return failedResult(
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                "This product is already active on your Google Play account.",
            )
        }
        val adminProduct = configProvider().subscriptions.products.firstOrNull {
            it.productId.trim() == requestedProductId
        }
            ?: return failedResult(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "Product is not configured in ITWing admin: $productId",
            )
        val productType = adminProduct.billingProductType()
        val details = productDetails[productKey(requestedProductId, productType)]
            ?: productDetails[requestedProductId]
            ?: return failedResult(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "Google Play did not return product details for $requestedProductId. Confirm package name, signed Play build, tester account, active product, and Play Console product ID.",
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
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            restorePurchases()
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            lastBillingMessage = billingLaunchFailureMessage(result, adminProduct, details)
            SDKTelemetry.track(
                "purchase_flow_failed",
                mapOf(
                    "product_id" to requestedProductId,
                    "response_code" to result.responseCode,
                    "message" to lastBillingMessage,
                ),
            )
            return BillingResult.newBuilder()
                .setResponseCode(result.responseCode)
                .setDebugMessage(lastBillingMessage.orEmpty())
                .build()
        }
        SDKTelemetry.track(
            "purchase_flow_launched",
            mapOf(
                "product_id" to requestedProductId,
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
        val products = products().filter { it.isGooglePlayStore() && it.productId.isNotBlank() }
        SDKTelemetry.track("purchase_dialog_shown", mapOf("product_count" to products.size))

        if (activity.isFinishing || activity.isDestroyed) {
            onResult(
                failedResult(
                    BillingClient.BillingResponseCode.ERROR,
                    "Activity is not available.",
                ),
            )
            return
        }

        if (products.isEmpty()) {
            onResult(
                failedResult(
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                    "No enabled Google Play subscription or in-app products are configured in ITWing admin.",
                ),
            )
            return
        }

        fun renderDialog() = mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                onResult(
                    failedResult(
                        BillingClient.BillingResponseCode.ERROR,
                        "Activity is not available.",
                    ),
                )
                return@post
            }

            runCatching {
                PurchaseDialog.show(
                    activity = activity,
                    products = products,
                    primaryColor = purchasePrimaryColor(),
                    detailsProvider = { productId, productType ->
                        productDetails[productKey(productId, productType)] ?: productDetails[productId]
                    },
                    ownedProductIds = ownedProductIds(),
                    launcher = { productId, result -> launchPurchaseWhenReady(activity, productId.trim(), result) },
                    restore = { callback -> restorePurchases(callback) },
                    onResult = onResult,
                )
            }.onFailure { error ->
                onResult(
                    failedResult(
                        BillingClient.BillingResponseCode.ERROR,
                        error.message ?: "Could not open purchase options.",
                    ),
                )
            }
        }

        connect(activity) { restorePurchases { renderDialog() } }
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
        queryPurchases(client, ProductType.SUBS) { subscriptionsSucceeded, subscriptionPurchases ->
            restored.addAll(subscriptionPurchases)
            queryPurchases(client, ProductType.INAPP) { inAppSucceeded, inAppPurchases ->
                restored.addAll(inAppPurchases)
                if (!subscriptionsSucceeded || !inAppSucceeded) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { repositoryProvider()?.restoreSubscriptions() }
                        launch(Dispatchers.Main.immediate) {
                            SDKTelemetry.track("purchase_restore_completed", mapOf("active" to isAdFree(), "purchase_count" to restored.size))
                            onComplete?.invoke(isAdFree())
                        }
                    }
                    return@queryPurchases
                }

                replacePlayOwnership(restored)
                restored.forEach { purchase -> verifyPurchase(purchase) }
                SDKTelemetry.track("purchase_restore_completed", mapOf("active" to isAdFree(), "purchase_count" to restored.size))
                onComplete?.invoke(isAdFree())
            }
        }
    }

    private fun queryProducts(onComplete: (() -> Unit)? = null) {
        val client = billingClient ?: run {
            onComplete?.invoke()
            return
        }
        if (!client.isReady) {
            onComplete?.invoke()
            return
        }
        val products = configProvider().subscriptions.products
            .filter { it.isGooglePlayStore() && it.productId.isNotBlank() }
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
                        .setProductId(it.productId.trim())
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
                logUnfetchedProducts(detailsResult)
                finishQuery()
            }
        }
    }

    private fun ensureProductDetailsLoaded(productId: String, onComplete: () -> Unit) {
        val requestedProductId = productId.trim()
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId.trim() == requestedProductId }
        val productType = adminProduct?.billingProductType()
        if (productType != null && (productDetails[productKey(requestedProductId, productType)] ?: productDetails[requestedProductId]) != null) {
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
        val adminProduct = configProvider().subscriptions.products.firstOrNull { it.productId.trim() == productId.trim() }
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
        recordPlayOwnership(purchase)
        if (adminProduct?.isConsumable() != true) {
            acknowledgeIfNeeded(purchase)
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
                recordPlayOwnership(purchase)
            }.onFailure {
                recordPlayOwnership(purchase)
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
        val desiredBasePlanId = adminProduct?.basePlanId?.trim().takeUnless { it.isNullOrBlank() }
        val desiredOfferId = adminProduct?.offerId?.trim().takeUnless { it.isNullOrBlank() }

        if (desiredBasePlanId == null && desiredOfferId == null) {
            return offers.firstOrNull()?.offerToken
        }

        return offers.firstOrNull { offer ->
            (desiredBasePlanId == null || offer.basePlanId.equals(desiredBasePlanId, ignoreCase = true))
                && (desiredOfferId == null || offer.offerId.equals(desiredOfferId, ignoreCase = true))
        }?.offerToken ?: offers.firstOrNull { offer ->
            desiredBasePlanId != null && offer.basePlanId.equals(desiredBasePlanId, ignoreCase = true)
        }?.offerToken ?: offers.firstOrNull()?.offerToken
    }

    private fun queryPurchases(client: BillingClient, productType: String, onComplete: (Boolean, List<Purchase>) -> Unit) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(productType).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                lastBillingMessage = result.debugMessage
                onComplete(false, emptyList())
                return@queryPurchasesAsync
            }
            onComplete(true, purchases)
        }
    }

    private fun ownedProductIds(): Set<String> = repositoryProvider()?.ownedProductIds().orEmpty()

    private fun recordPlayOwnership(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val productIds = purchase.products.map(String::trim).filter(String::isNotBlank)
        val nonConsumableIds = productIds.filterNot { productId ->
            configProvider().subscriptions.products.firstOrNull { it.productId.trim() == productId }?.isConsumable() == true
        }
        if (nonConsumableIds.isEmpty()) return
        savePlayOwnership(ownedProductIds() + nonConsumableIds)
    }

    private fun replacePlayOwnership(purchases: List<Purchase>) {
        val validProductIds = purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .filter { purchase ->
                GooglePlaySignatureValidator.verify(googlePlayLicenseKey(), purchase.originalJson, purchase.signature) != false
            }
            .flatMap { it.products }
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { productId ->
                configProvider().subscriptions.products.firstOrNull { it.productId.trim() == productId }?.isConsumable() == true
            }
            .toSet()
        savePlayOwnership(validProductIds)
    }

    private fun savePlayOwnership(productIds: Set<String>) {
        val wasAdFree = isAdFree()
        val removesAds = configProvider().subscriptions.products.any { product ->
            product.productId.trim() in productIds && product.removesAds
        }
        repositoryProvider()?.savePlayOwnership(productIds, removesAds)
        val isAdFree = isAdFree()
        if (wasAdFree != isAdFree) {
            mainHandler.post { onEntitlementChanged(isAdFree) }
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

    private fun SubscriptionProductConfig.isGooglePlayStore(): Boolean {
        val normalizedStore = store.trim().lowercase()
        if (normalizedStore.isBlank()) {
            return true
        }
        return when (store.trim().lowercase()) {
            "google_play", "google-play", "google play", "play", "play_store", "google" -> true
            else -> false
        }
    }

    private fun SubscriptionProductConfig.billingProductType(): String {
        val configured = productType.ifBlank {
            metadata["product_type"]?.toString().orEmpty()
        }.trim().lowercase()

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

    private fun billingLaunchFailureMessage(
        result: BillingResult,
        adminProduct: SubscriptionProductConfig,
        details: ProductDetails,
    ): String {
        val offers = details.subscriptionOfferDetails.orEmpty()
        val availableBasePlans = offers.mapNotNull { it.basePlanId.takeIf(String::isNotBlank) }.distinct()
        val availableOffers = offers.mapNotNull { it.offerId?.takeIf(String::isNotBlank) }.distinct()
        val googleMessage = result.debugMessage.takeIf { it.isNotBlank() }
        return googleMessage ?: buildString {
            append("Google Play Billing could not launch purchase for ")
            append(adminProduct.productId.trim())
            append(". Response code ")
            append(result.responseCode)
            append(". Admin base plan: ")
            append(adminProduct.basePlanId ?: "-")
            append(", offer: ")
            append(adminProduct.offerId ?: "-")
            append(". Play base plans returned: ")
            append(availableBasePlans.joinToString().ifBlank { "-" })
            append(". Play offers returned: ")
            append(availableOffers.joinToString().ifBlank { "-" })
            append(". Confirm app is installed from Play/internal testing with the same package and signing key.")
        }
    }

    private fun logUnfetchedProducts(detailsResult: Any) {
        runCatching {
            val method = detailsResult.javaClass.methods.firstOrNull { it.name == "getUnfetchedProductList" }
                ?: return
            val unfetched = method.invoke(detailsResult) as? Iterable<*> ?: return
            val messages = unfetched.mapNotNull { item ->
                val productId = item?.javaClass?.methods?.firstOrNull { it.name == "getProductId" }?.invoke(item) as? String
                val statusCode = item?.javaClass?.methods?.firstOrNull { it.name == "getStatusCode" }?.invoke(item)
                val debugMessage = item?.javaClass?.methods?.firstOrNull { it.name == "getDebugMessage" }?.invoke(item) as? String
                productId?.let { "$it: $statusCode ${debugMessage.orEmpty()}".trim() }
            }
            if (messages.isNotEmpty()) {
                lastBillingMessage = "Unfetched Google Play products: ${messages.joinToString("; ")}"
                SDKTelemetry.track("billing_products_unfetched", mapOf("message" to lastBillingMessage.orEmpty()))
            }
        }
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

    private fun purchasePrimaryColor(): Int {
        val app = configProvider().app
        val colors = app["colors"] as? Map<*, *>
        val colorValue = listOf(
            colors?.get("primary"),
            colors?.get("primary_color"),
            app["primary_color"],
            app["primaryColor"],
        ).firstNotNullOfOrNull { it?.toString()?.takeIf(String::isNotBlank) }
        return runCatching { Color.parseColor(colorValue ?: "#2563EB") }.getOrDefault(Color.rgb(37, 99, 235))
    }

    private fun drainReadyCallbacks() {
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { callback ->
            runCatching { callback() }
        }
    }
}
