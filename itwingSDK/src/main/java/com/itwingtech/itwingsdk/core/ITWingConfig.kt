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
    val wallpapers: WallpaperConfig = WallpaperConfig(),
    val ringtones: MediaLibraryConfig = MediaLibraryConfig(kind = "ringtones", listEndpoint = "/media/ringtones", eventEndpoint = "/media/ringtones/{id}/events"),
    val videos: MediaLibraryConfig = MediaLibraryConfig(kind = "videos", listEndpoint = "/media/videos", eventEndpoint = "/media/videos/{id}/events"),
    val vpnServers: MediaLibraryConfig = MediaLibraryConfig(kind = "vpn_servers", listEndpoint = "/media/vpn_servers", eventEndpoint = "/media/vpn_servers/{id}/events"),
    val subscriptions: SubscriptionConfig = SubscriptionConfig(),
    val firebase: FirebaseConfig = FirebaseConfig(),
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
    val androidTargetUrl: String? = null,
    val iosTargetUrl: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
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
    val provider: String = "itwing",
    val enabled: Boolean = false,
    val deviceRegistrationEndpoint: String = "/notifications/device",
    val pendingEndpoint: String = "/notifications/pending",
    val eventEndpoint: String = "/notifications/{id}/event",
    val pollIntervalSeconds: Int = 300,
    val promptForPermission: Boolean = false,
    val topics: List<String> = emptyList(),
    val segments: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)

data class WallpaperConfig(
    val enabled: Boolean = false,
    val topLimit: Int = 10,
    val defaultSort: String = "trending",
    val listEndpoint: String = "/wallpapers",
    val eventEndpoint: String = "/wallpapers/{id}/events",
    val placements: Map<String, WallpaperPlacementConfig> = emptyMap(),
)

data class WallpaperPlacementConfig(
    val name: String = "",
    val type: String = "wallpapers",
    val enabled: Boolean = true,
    val limit: Int? = null,
    val trendingLimit: Int? = null,
    val sort: String? = null,
    val columns: Int? = null,
    val horizontal: Boolean? = null,
    val itemWidthDp: Int? = null,
    val itemHeightDp: Int? = null,
    val itemSpacingDp: Int? = null,
    val cornerRadiusDp: Int? = null,
    val showTitle: Boolean? = null,
    val premiumUnlockPlacement: String? = null,
    val categoryDisplayMode: String? = null,
    val contentSource: String? = null,
    val categoryId: String? = null,
    val timeRange: String? = null,
    val selectedWallpaperIds: List<String> = emptyList(),
    val selectedCategoryIds: List<String> = emptyList(),
    val inlineAdEnabled: Boolean = false,
    val inlineAdPlacement: String? = null,
    val inlineAdInterval: Int = 0,
    val inlineAdStartAfter: Int = 0,
    val inlineAdMaxAds: Int = 0,
)

data class MediaLibraryConfig(
    val kind: String = "",
    val enabled: Boolean = false,
    val topLimit: Int = 10,
    val defaultSort: String = "trending",
    val listEndpoint: String = "",
    val eventEndpoint: String = "",
    val placements: Map<String, MediaPlacementConfig> = emptyMap(),
)

data class MediaPlacementConfig(
    val name: String = "",
    val type: String = "items",
    val enabled: Boolean = true,
    val limit: Int? = null,
    val sort: String? = null,
    val columns: Int? = null,
    val horizontal: Boolean? = null,
    val showTitle: Boolean? = null,
    val premiumUnlockPlacement: String? = null,
    val categoryDisplayMode: String? = null,
    val contentSource: String? = null,
    val categoryId: String? = null,
    val selectedItemIds: List<String> = emptyList(),
    val inlineAdEnabled: Boolean = false,
    val inlineAdPlacement: String? = null,
    val inlineAdInterval: Int = 0,
    val inlineAdStartAfter: Int = 0,
    val inlineAdMaxAds: Int = 0,
)

data class ApiKeyConfig(
    val id: String? = null,
    val name: String = "",
    val value: String = "",
    val provider: String? = null,
    val proxyEndpoint: String? = null,
    val baseUrl: String? = null,
    val description: String? = null,
    val dailyQuota: Int? = null,
    val dailyUsage: Int = 0,
    val poolSize: Int = 1,
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
    val productType: String = "subscription",
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

data class SubscriptionPlanInfo(
    val productId: String,
    val basePlanId: String? = null,
    val offerId: String? = null,
    val name: String,
    val productType: String,
    val billingPeriod: String,
    val price: Double?,
    val currency: String?,
    val formattedPrice: String? = null,
    val active: Boolean,
    val removesAds: Boolean,
    val expiresAt: String?,
)

data class FirebaseConfig(
    val enabled: Boolean = false,
    val projectId: String? = null,
    val googleAppId: String? = null,
    val apiKey: String? = null,
    val gcmSenderId: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class InAppUpdateConfig(
    val enabled: Boolean = false,
    val type: String = "flexible",
    val minStalenessDays: Int = 0,
    val priority: Int = 0,
)
