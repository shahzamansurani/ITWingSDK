package com.itwingtech.itwingsdk.data

import android.content.Context
import android.provider.Settings
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.AdUnitConfig
import com.itwingtech.itwingsdk.core.AdsConfig
import com.itwingtech.itwingsdk.core.ApiKeyConfig
import com.itwingtech.itwingsdk.core.ApiProviderConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.InAppUpdateConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingOptions
import com.itwingtech.itwingsdk.core.NotificationConfig
import com.itwingtech.itwingsdk.core.SubscriptionConfig
import com.itwingtech.itwingsdk.core.SubscriptionProductConfig
import com.itwingtech.itwingsdk.security.RequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID


class ConfigRepository(
    private val context: Context,
    private val apiKey: String,
    private val options: ITWingOptions,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(options.bootstrapTimeoutMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
        .readTimeout(options.bootstrapTimeoutMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
        .writeTimeout(options.bootstrapTimeoutMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
        .callTimeout((options.bootstrapTimeoutMs * 2).coerceAtLeast(2_000), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val signer = RequestSigner(apiKey)
    private val store =EncryptedConfigStore(context)

    suspend fun bootstrap(): ITWingConfig = postConfig("/bootstrap", null)

    suspend fun syncConfig(version: Int): ITWingConfig? = runCatching {
        postConfig("/config/sync", version)
    }.getOrNull()

    fun loadCachedConfig(): ITWingConfig? = store.load()

    fun isAdFreeEntitled(): Boolean = store.isAdFreeEntitled()

    fun consumeFirstOpen(): Boolean = store.consumeFirstOpen()

    suspend fun verifySubscriptionPurchase(
        productId: String,
        purchaseToken: String,
        basePlanId: String? = null,
        offerId: String? = null,
        orderId: String? = null,
    ): JSONObject = signedPost(
        "/subscriptions/verify",
        JSONObject()
            .put("install_id", installId())
            .put("store", "google_play")
            .put("product_id", productId)
            .put("purchase_token", purchaseToken)
            .apply {
                if (!basePlanId.isNullOrBlank()) put("base_plan_id", basePlanId)
                if (!offerId.isNullOrBlank()) put("offer_id", offerId)
                if (!orderId.isNullOrBlank()) put("order_id", orderId)
            },
    ).also { updateEntitlementFromResponse(it) }

    suspend fun restoreSubscriptions(): JSONObject = signedPost(
        "/subscriptions/restore",
        JSONObject().put("install_id", installId()),
    ).also { updateEntitlementFromResponse(it) }

    suspend fun submitAttribution(values: Map<String, String?>): JSONObject = signedPost(
        "/attribution/install-referrer",
        JSONObject()
            .put("install_id", installId())
            .apply {
                values.forEach { (key, value) ->
                    if (!value.isNullOrBlank()) put(key, value)
                }
            },
    )

    suspend fun submitAnalytics(events: List<JSONObject>): JSONObject = signedPost(
        "/analytics/events",
        JSONObject()
            .put("install_id", installId())
            .put("events", org.json.JSONArray(events)),
    )

    suspend fun registerNotificationDevice(token: String, provider: String = "fcm"): JSONObject = signedPost(
        "/notifications/device",
        JSONObject()
            .put("install_id", installId())
            .put("provider", provider)
            .put("token", token)
            .put("platform", "android")
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("country", detectCountry()),
    )

    suspend fun submitCustomAdEvent(customAdId: String, eventType: String, metadata: JSONObject = JSONObject()): JSONObject = signedPost(
        "/custom-ads/$customAdId/events",
        JSONObject()
            .put("install_id", installId())
            .put("event_type", eventType)
            .put("country", detectCountry())
            .put("metadata", metadata),
    )

    fun enqueueAnalyticsEvent(event: JSONObject) {
        store.appendAnalyticsEvent(event)
    }

    suspend fun flushAnalytics(batchSize: Int = 50): Boolean {
        val events = store.analyticsEvents(batchSize)
        if (events.isEmpty()) return true

        return runCatching {
            submitAnalytics(events)
            store.removeAnalyticsEvents(events.size)
            true
        }.getOrDefault(false)
    }

    suspend fun proxy(provider: String, path: String, method: String = "GET", payload: JSONObject? = null): Response =
        signedRequest("/proxy/${provider.trim('/')}/${path.trim('/')}", method, payload)

    fun updateEntitlementFromResponse(root: JSONObject) {
        val data = root.optJSONObject("data") ?: root
        val active = data.optBoolean("active", false)
        val removesAds = data.optBoolean("removes_ads", false) ||
                data.optJSONArray("purchases")?.let { purchases ->
                    (0 until purchases.length()).any { index ->
                        val item = purchases.optJSONObject(index)
                        item?.optBoolean("active", false) == true && item.optBoolean("removes_ads", false)
                    }
                } == true
        val expiresAt = data.optString("expires_at").takeIf(String::isNotBlank)
            ?: data.optJSONArray("purchases")?.let { purchases ->
                (0 until purchases.length()).mapNotNull { index ->
                    purchases.optJSONObject(index)?.takeIf {
                        it.optBoolean("active", false) && it.optBoolean("removes_ads", false)
                    }?.optString("expires_at")?.takeIf(String::isNotBlank)
                }.maxOrNull()
            }
        store.saveEntitlement(active, removesAds, expiresAt)
    }

    private suspend fun postConfig(path: String, lastVersion: Int?): ITWingConfig =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("install_id", installId())
                .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .put("premium", store.isAdFreeEntitled())
                .apply {
                    detectCountry().takeIf { it.length == 2 }?.let { put("country", it) }
                    if (lastVersion != null) put("last_config_version", lastVersion)
                }
                .toString()

            val root = signedPost(path, JSONObject(body))
            val data = root.optJSONObject("data") ?: root
            val app = data.optJSONObject("app")
            val ads = data.optJSONObject("ads")
            val notifications = data.optJSONObject("notifications")
            val subscriptions = data.optJSONObject("subscriptions")
            val parsed = ITWingConfig(
                configVersion = data.optInt("config_version", 0),
                ttlSeconds = data.optInt("ttl_seconds", 3600),
                app = app?.toMap() ?: emptyMap(),
                ads = AdsConfig(
                    globalEnabled = ads?.optBoolean("global_enabled", false) ?: false,
                    premiumDisablesAds = ads?.optBoolean("premium_disables_ads", true) ?: true,
                    blockedReason = ads?.optString("blocked_reason").takeIf { !it.isNullOrBlank() },
                    testMode = ads?.optBoolean("test_mode", false) ?: false,
                    admobAppId = ads?.optString("admob_app_id"),
                    futureFormats = ads?.optJSONArray("future_formats")?.toStringList()
                        ?: emptyList(),
                    placements = parsePlacements(ads),
                    customAds = parseCustomAds(ads),
                ),
                features = data.optJSONObject("features")?.toMap() ?: emptyMap(),
                remoteConfig = data.optJSONObject("remote_config")?.toMap() ?: emptyMap(),
                analytics = data.optJSONObject("analytics")?.toMap() ?: emptyMap(),
                notifications = NotificationConfig(
                    provider = notifications?.optString("provider", "fcm") ?: "fcm",
                    enabled = notifications?.optBoolean("enabled", false) ?: false,
                    onesignalAppId = notifications?.optString("onesignal_app_id")
                        ?.takeIf(String::isNotBlank),
                    fcmSenderId = notifications?.optString("fcm_sender_id")?.takeIf(String::isNotBlank),
                    fcmTopics = notifications?.optJSONArray("fcm_topics")?.toStringList() ?: emptyList(),
                    deviceRegistrationEndpoint = notifications?.optString("device_registration_endpoint", "/notifications/device")
                        ?: "/notifications/device",
                    promptForPermission = notifications?.optBoolean(
                        "prompt_for_permission",
                        false
                    )
                        ?: false,
                    segments = notifications?.optJSONArray("segments")?.toStringList()
                        ?: emptyList(),
                    tags = notifications?.optJSONObject("tags")?.toStringMap() ?: emptyMap(),
                ),
                subscriptions = SubscriptionConfig(
                    enabled = subscriptions?.optBoolean("enabled", false) ?: false,
                    verifyEndpoint = subscriptions?.optString("verify_endpoint", "/subscriptions/verify")
                        ?: "/subscriptions/verify",
                    restoreEndpoint = subscriptions?.optString("restore_endpoint", "/subscriptions/restore")
                        ?: "/subscriptions/restore",
                    products = parseSubscriptionProducts(subscriptions),
                ),
                apiProviders = parseApiProviders(data.optJSONObject("api_providers")),
                apiKeys = parseApiKeys(data.optJSONObject("api_keys")),
            )
            store.save(parsed)
            parsed
        }

    private fun parsePlacements(ads: JSONObject?): List<AdPlacementConfig> {
        val placements = ads?.optJSONArray("placements") ?: return emptyList()
        return (0 until placements.length()).mapNotNull { index ->
            val item = placements.optJSONObject(index) ?: return@mapNotNull null
            val units = item.optJSONArray("units")
            AdPlacementConfig(
                name = item.optString("name"),
                format = item.optString("format"),
                enabled = item.optBoolean("enabled", false),
                testMode = item.optBoolean("test_mode", false),
                priority = item.optInt("priority", 100),
                triggerInterval = item.optNullableInt("trigger_interval"),
                refreshSeconds = item.optNullableInt("refresh_seconds"),
                cooldownSeconds = item.optNullableInt("cooldown_seconds"),
                sessionCap = item.optNullableInt("session_cap"),
                dailyCap = item.optNullableInt("daily_cap"),
                metadata = item.optJSONObject("metadata")?.toMap() ?: emptyMap(),
                customAd = parseCustomAd(item.optJSONObject("custom_ad")),
                units = if (units == null) emptyList() else (0 until units.length()).mapNotNull { unitIndex ->
                    val unit = units.optJSONObject(unitIndex) ?: return@mapNotNull null
                    AdUnitConfig(
                        network = unit.optString("network"),
                        adUnitId = unit.optString("ad_unit_id"),
                        waterfallOrder = unit.optInt("waterfall_order", 1),
                    )
                },
            )
        }
    }

    private fun parseCustomAds(ads: JSONObject?): List<CustomAdConfig> {
        val customAds = ads?.optJSONArray("custom_ads") ?: return emptyList()
        return (0 until customAds.length()).mapNotNull { index ->
            val item = customAds.optJSONObject(index) ?: return@mapNotNull null
            CustomAdConfig(
                id = item.optString("id"),
                name = item.optString("name"),
                campaignGroup = item.optString("campaign_group").takeIf(String::isNotBlank),
                format = item.optString("format"),
                priority = item.optInt("priority", 100),
                dailyFrequencyCap = item.optNullableInt("daily_frequency_cap"),
                sessionFrequencyCap = item.optNullableInt("session_frequency_cap"),
                deviceTargeting = item.optString("device_targeting").takeIf(String::isNotBlank),
                headline = item.optString("headline").takeIf(String::isNotBlank),
                body = item.optString("body").takeIf(String::isNotBlank),
                cta = item.optString("cta").takeIf(String::isNotBlank),
                targetUrl = item.optString("target_url").takeIf(String::isNotBlank),
                imageUrl = item.optString("image_url").takeIf(String::isNotBlank),
                videoUrl = item.optString("video_url").takeIf(String::isNotBlank),
                html = item.optString("html").takeIf(String::isNotBlank),
                metadata = item.optJSONObject("metadata")?.toMap() ?: emptyMap(),
            )
        }
    }

    private fun parseCustomAd(item: JSONObject?): CustomAdConfig? {
        if (item == null) return null
        return CustomAdConfig(
            id = item.optString("id"),
            name = item.optString("name"),
            campaignGroup = item.optString("campaign_group").takeIf(String::isNotBlank),
            format = item.optString("format"),
            priority = item.optInt("priority", 100),
            dailyFrequencyCap = item.optNullableInt("daily_frequency_cap"),
            sessionFrequencyCap = item.optNullableInt("session_frequency_cap"),
            deviceTargeting = item.optString("device_targeting").takeIf(String::isNotBlank),
            headline = item.optString("headline").takeIf(String::isNotBlank),
            body = item.optString("body").takeIf(String::isNotBlank),
            cta = item.optString("cta").takeIf(String::isNotBlank),
            targetUrl = item.optString("target_url").takeIf(String::isNotBlank),
            imageUrl = item.optString("image_url").takeIf(String::isNotBlank),
            videoUrl = item.optString("video_url").takeIf(String::isNotBlank),
            html = item.optString("html").takeIf(String::isNotBlank),
            metadata = item.optJSONObject("metadata")?.toMap() ?: emptyMap(),
        )
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        return keys().asSequence().associateWith { key ->
            when (val value = opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is org.json.JSONArray -> (0 until value.length()).map { value.opt(it) }
                else -> value
            }
        }
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        return keys().asSequence().associateWith { key -> optString(key) }
    }

    private fun parseApiKeys(keys: JSONObject?): Map<String, ApiKeyConfig> {
        if (keys == null) return emptyMap()
        return keys.keys().asSequence().mapNotNull { key ->
            val item = keys.optJSONObject(key) ?: return@mapNotNull null
            key to ApiKeyConfig(
                name = item.optCleanString("name") ?: key,
                value = item.optCleanString("value").orEmpty(),
                provider = item.optCleanString("provider"),
                proxyEndpoint = item.optCleanString("proxy_endpoint"),
                baseUrl = item.optCleanString("base_url"),
                description = item.optCleanString("description"),
            )
        }.toMap()
    }

    private fun parseApiProviders(providers: JSONObject?): Map<String, ApiProviderConfig> {
        if (providers == null) return emptyMap()
        return providers.keys().asSequence().mapNotNull { provider ->
            val item = providers.optJSONObject(provider) ?: return@mapNotNull null
            provider to ApiProviderConfig(
                provider = item.optCleanString("provider") ?: provider,
                proxyEndpoint = item.optCleanString("proxy_endpoint").orEmpty(),
                healthyKeyCount = item.optInt("healthy_key_count", 0),
                dailyQuota = item.optInt("daily_quota", 0),
                dailyUsage = item.optInt("daily_usage", 0),
            )
        }.toMap()
    }

    private fun parseSubscriptionProducts(subscriptions: JSONObject?): List<SubscriptionProductConfig> {
        val products = subscriptions?.optJSONArray("products") ?: return emptyList()
        return (0 until products.length()).mapNotNull { index ->
            val item = products.optJSONObject(index) ?: return@mapNotNull null
            SubscriptionProductConfig(
                id = item.optString("id"),
                name = item.optString("name"),
                store = item.optString("store", "google_play"),
                productId = item.optString("product_id"),
                basePlanId = item.optString("base_plan_id").takeIf(String::isNotBlank),
                offerId = item.optString("offer_id").takeIf(String::isNotBlank),
                billingPeriod = item.optString("billing_period", "monthly"),
                price = if (item.has("price") && !item.isNull("price")) item.optDouble("price") else null,
                currency = item.optString("currency").takeIf(String::isNotBlank),
                removesAds = item.optBoolean("removes_ads", true),
                entitlements = item.optJSONObject("entitlements")?.toMap() ?: emptyMap(),
                metadata = item.optJSONObject("metadata")?.toMap() ?: emptyMap(),
            )
        }
    }

    private fun JSONObject.optCleanString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name, "").trim()
        return value.takeUnless {
            it.isBlank() ||
                    it.equals("null", ignoreCase = true) ||
                    it.equals("undefined", ignoreCase = true)
        }
    }

    private suspend fun signedPost(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        signedRequest(path, "POST", payload).use { response ->
            val responseBody = response.body.string().orEmpty()
            if (!response.isSuccessful) error("SDK request failed: ${response.code} ${responseBody.take(300)}")
            JSONObject(responseBody)
        }
    }

    private suspend fun signedRequest(path: String, method: String, payload: JSONObject? = null): Response = withContext(Dispatchers.IO) {
        val body = payload?.toString() ?: ""
        val timestamp = Instant.now().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = sha256(body)
        val normalizedMethod = method.uppercase()
        val signature = signer.sign(normalizedMethod, "/api/sdk/v1$path", timestamp, nonce, bodyHash)

        val builder = Request.Builder()
            .url(options.endpoint + path)
            .header("Accept", "application/json")
            .header("X-ITW-Key", apiKey)
            .header("X-ITW-Timestamp", timestamp)
            .header("X-ITW-Nonce", nonce)
            .header("X-ITW-Signature", signature)
            .header("X-ITW-Platform", "android")
            .header("X-ITW-App-Identifier", context.packageName)
            .header("X-ITW-SDK-Version", "1.0.0")

        val request = if (normalizedMethod == "GET") {
            builder.get().build()
        } else {
            builder.method(normalizedMethod, body.toRequestBody("application/json".toMediaType())).build()
        }

        try {
            client.newCall(request).execute()
        } catch (e: UnknownHostException) {
            throw IllegalStateException(
                "network_dns_unavailable: unable to resolve ${request.url.host}. Check device internet, DNS, VPN/private DNS, or server domain records.",
                e,
            )
        }
    }

    private fun installId(): String {
        store.installId()?.let { return it }
        val value = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        store.saveInstallId(value)
        return value
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun detectCountry(): String {
        return try {
            val tm =
                context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            when {
                !tm.simCountryIso.isNullOrEmpty() ->
                    tm.simCountryIso.uppercase()

                !tm.networkCountryIso.isNullOrEmpty() ->
                    tm.networkCountryIso.uppercase()

                else ->
                    context.resources.configuration.locales[0].country.uppercase()
            }
        } catch (e: Exception) {
            context.resources.configuration.locales[0].country.uppercase()
        }
    }
}
