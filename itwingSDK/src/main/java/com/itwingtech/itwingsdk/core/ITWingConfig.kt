package com.itwingtech.itwingsdk.core


data class ITWingConfig(
    val configVersion: Int = 0,
    val ttlSeconds: Int = 3600,
    val app: Map<String, Any?> = emptyMap(),
    val ads: AdsConfig = AdsConfig(),
    val features: Map<String, Any?> = emptyMap(),
    val remoteConfig: Map<String, Any?> = emptyMap(),
    val analytics: Map<String, Any?> = emptyMap(),
    val notifications: NotificationConfig = NotificationConfig(),
    val subscriptions: SubscriptionConfig = SubscriptionConfig(),
    val apiProviders: Map<String, ApiProviderConfig> = emptyMap(),
    val apiKeys: Map<String, ApiKeyConfig> = emptyMap(),
)

data class AppRuntimeConfig(
    val name: String? = null,
    val title: String? = null,
    val iconUrl: String? = null,
    val status: String = "active",
    val maintenance: Boolean = false,
    val privacyPolicyUrl: String? = null,
    val termsUrl: String? = null,
    val disclaimerUrl: String? = null,
    val splash: SplashConfig = SplashConfig(),
)

data class SplashConfig(
    val seconds: Int = 2,
    val adFormat: String = "app_open",
)

data class AdsConfig(
    val globalEnabled: Boolean = false,
    val premiumDisablesAds: Boolean = true,
    val blockedReason: String? = null,
    val testMode: Boolean = false,
    val admobAppId: String? = null,
    val futureFormats: List<String> = emptyList(),
    val placements: List<AdPlacementConfig> = emptyList(),
    val customAds: List<CustomAdConfig> = emptyList(),
)

data class CustomAdConfig(
    val id: String = "",
    val name: String = "",
    val campaignGroup: String? = null,
    val format: String = "",
    val priority: Int = 100,
    val dailyFrequencyCap: Int? = null,
    val sessionFrequencyCap: Int? = null,
    val deviceTargeting: String? = null,
    val headline: String? = null,
    val body: String? = null,
    val cta: String? = null,
    val targetUrl: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val html: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class AdPlacementConfig(
    val name: String,
    val format: String,
    val enabled: Boolean,
    val testMode: Boolean,
    val priority: Int = 100,
    val triggerInterval: Int? = null,
    val refreshSeconds: Int? = null,
    val cooldownSeconds: Int? = null,
    val sessionCap: Int? = null,
    val dailyCap: Int? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val customAd: CustomAdConfig? = null,
    val units: List<AdUnitConfig> = emptyList(),
)

data class AdUnitConfig(
    val network: String,
    val adUnitId: String,
    val waterfallOrder: Int,
)

data class NotificationConfig(
    val provider: String = "fcm",
    val enabled: Boolean = false,
    val onesignalAppId: String? = null,
    val fcmSenderId: String? = null,
    val fcmTopics: List<String> = emptyList(),
    val deviceRegistrationEndpoint: String = "/notifications/device",
    val promptForPermission: Boolean = false,
    val segments: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)

data class ApiKeyConfig(
    val name: String = "",
    val value: String = "",
    val provider: String? = null,
    val proxyEndpoint: String? = null,
    val baseUrl: String? = null,
    val description: String? = null
)

data class ApiProviderConfig(
    val provider: String = "",
    val proxyEndpoint: String = "",
    val healthyKeyCount: Int = 0,
    val dailyQuota: Int = 0,
    val dailyUsage: Int = 0,
)

data class SubscriptionConfig(
    val enabled: Boolean = false,
    val verifyEndpoint: String = "/subscriptions/verify",
    val restoreEndpoint: String = "/subscriptions/restore",
    val products: List<SubscriptionProductConfig> = emptyList(),
)

data class SubscriptionProductConfig(
    val id: String = "",
    val name: String = "",
    val store: String = "google_play",
    val productId: String = "",
    val basePlanId: String? = null,
    val offerId: String? = null,
    val billingPeriod: String = "monthly",
    val price: Double? = null,
    val currency: String? = null,
    val removesAds: Boolean = true,
    val entitlements: Map<String, Any?> = emptyMap(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class InAppUpdateConfig(
    val enabled: Boolean = false,
    val type: String = "flexible",
    val minStalenessDays: Int = 0,
    val priority: Int = 0,
)
