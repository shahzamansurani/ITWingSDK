package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.analytics.AnalyticsClient
import com.itwingtech.itwingsdk.analytics.InstallReferrerReporter
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.billing.SubscriptionManager
import com.itwingtech.itwingsdk.data.ConfigRepository
import com.itwingtech.itwingsdk.ui.ITWingActionDialog
import com.itwingtech.itwingsdk.ui.ITWingLoadingDialog
import com.itwingtech.itwingsdk.updates.InAppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import androidx.core.net.toUri
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object ITWingSDK {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var repository: ConfigRepository? = null
    private var config = ITWingConfig()
    private val runtime = AppRuntimeManager(
        configProvider = { config },
        adManagerProvider = { ads },
    )
    @Volatile
    private var mobileAdsInitialized = false
    @Volatile
    private var startupPreloadDone = false
    @Volatile
    private var bootstrapFinished = false
    @Volatile
    private var bootstrapInFlight = false
    @Volatile
    private var lastError: String? = "not_initialized"
    @Volatile
    private var connectionState: String = "not_initialized"
    @Volatile
    private var lifecycleTrackingRegistered = false
    @Volatile
    private var foregroundActivityCount = 0
    @Volatile
    private var foregroundStartedAtMs = 0L
    private val readyCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val initListeners = CopyOnWriteArrayList<SDKInitListener>()

    val ads: AdManager = AdManager(configProvider = { config }, suppressAdsProvider = { ::subscriptions.isInitialized && subscriptions.isAdFree() })
    lateinit var analytics: AnalyticsClient private set
    lateinit var updates: InAppUpdateManager private set
    lateinit var subscriptions: SubscriptionManager private set

    @JvmStatic
    fun initialize(activity: Activity, apiKey: String, onReady: () -> Unit) {
        initialize(activity, apiKey, ITWingOptions(), readyListener(onReady))
    }

    @JvmStatic
    fun initialize(activity: Activity, apiKey: String, options: ITWingOptions, onReady: () -> Unit) {
        initialize(activity, apiKey, options, readyListener(onReady))
    }

    @JvmStatic
    fun initialize(activity: Activity, apiKey: String, listener: SDKInitListener) {
        initialize(activity, apiKey, ITWingOptions(), listener)
    }

    @JvmStatic
    @JvmOverloads
    fun initialize(activity: Activity, apiKey: String, options: ITWingOptions = ITWingOptions(), listener: SDKInitListener? = null) {
        listener?.let { initListeners.add(it) }
        repository = ConfigRepository(activity.applicationContext, apiKey, options)
        bootstrapFinished = false
        bootstrapInFlight = true
        connectionState = "bootstrap_in_progress"
        lastError = "bootstrap_in_progress"
        analytics = AnalyticsClient(repository!!)
        SDKTelemetry.configure(
            context = activity.applicationContext,
            analyticsProvider = { analyticsOrNull() },
            repositoryProvider = { repository },
        )
        notifyListeners { it.onAnalyticsReady() }
        registerLifecycleAutomation(activity.application)
        analytics.track(
            "sdk_initialize_requested",
            mapOf(
                "activity" to activity.javaClass.simpleName,
                "endpoint" to options.endpoint,
            ),
        )
        if (repository!!.consumeFirstOpen()) {
            analytics.track("first_open")
            analytics.track("install")
            InstallReferrerReporter(activity.applicationContext, repository!!).collect()
        }
        repository!!.consumeAppUpdate()?.let { (previous, current) ->
            analytics.track("app_update", mapOf("previous_version" to previous, "current_version" to current))
        }
        analytics.track("app_open")
        analytics.track("session_start")
        NotificationRuntimeManager.registerFcmDevice(activity.applicationContext, repository!!)
        updates = InAppUpdateManager { config }
        updates.bind(activity)
        subscriptions = SubscriptionManager({ config }, { repository }) { adFree ->
            if (adFree) ads.onEntitlementActivated()
        }
        scope.launch {
            /*
             * Load cached config first
             * for instant startup.
             */
            config = repository?.loadCachedConfig() ?: ITWingConfig()
            if (config.configVersion > 0) {
                connectionState = "cached_config_loaded"
                lastError = null
                analytics.track("sdk_cached_config_loaded", mapOf("config_version" to config.configVersion))
                notifyListeners { it.onConfigLoaded(config) }
                notifyListeners { it.onOfflineMode("Loaded cached SDK config; refreshing remote config in background.") }
            }

            /*
             * Initialize SDK early
             * but DO NOT preload ads yet.
             */
            if (config.configVersion > 0) {
                FirebaseRuntimeManager.configure(activity.applicationContext, config.firebase)
                SDKTelemetry.track(
                    "firebase_configured",
                    mapOf(
                        "enabled" to config.firebase.enabled,
                        "analytics_enabled" to config.firebase.analyticsEnabled,
                        "crashlytics_enabled" to config.firebase.crashlyticsEnabled,
                        "auth_enabled" to config.firebase.authEnabled,
                    ),
                )
                NotificationRuntimeManager.configure(activity, config, repository)
                notifyListeners { it.onNotificationsReady() }
                updates.check(activity)
                subscriptions.connect(activity) {
                    notifyListeners { it.onBillingReady() }
                    subscriptions.restorePurchases {
                        initializeMobileAds(activity) {
                            ads.startAutomaticAppOpen(activity)
                        }
                    }
                }
            }

            /*
             * Fetch fresh remote config
             */
            runCatching { repository!!.bootstrap() }.onSuccess { remote ->
                config = remote
                lastError = null
                connectionState = "ready"
                bootstrapFinished = true
                bootstrapInFlight = false
                analytics.track(
                    "sdk_bootstrap_succeeded",
                    mapOf(
                        "config_version" to remote.configVersion,
                        "placements" to remote.ads.placements.size,
                        "custom_ads" to remote.ads.customAds.size,
                        "subscriptions" to remote.subscriptions.products.size,
                        "notifications_enabled" to remote.notifications.enabled,
                        "firebase_enabled" to remote.firebase.enabled,
                    ),
                )
                notifyReady(true)
                notifyListeners { it.onConfigLoaded(remote) }
                FirebaseRuntimeManager.configure(activity.applicationContext, config.firebase)
                SDKTelemetry.track(
                    "firebase_configured",
                    mapOf(
                        "enabled" to config.firebase.enabled,
                        "analytics_enabled" to config.firebase.analyticsEnabled,
                        "crashlytics_enabled" to config.firebase.crashlyticsEnabled,
                        "auth_enabled" to config.firebase.authEnabled,
                    ),
                )
                NotificationRuntimeManager.configure(activity, config, repository)
                notifyListeners { it.onNotificationsReady() }
                updates.check(activity)
                subscriptions.connect(activity) {
                    notifyListeners { it.onBillingReady() }
                    subscriptions.restorePurchases {
                        initializeMobileAds(activity) {
                            preloadAdsIfNeeded(activity)
                            ads.startAutomaticAppOpen(activity)
                            notifyListeners { it.onAdsReady() }
                        }
                    }
                }

            }.onFailure {
                val cachedConfigAvailable = config.configVersion > 0
                val networkFailure = it.isNetworkFailure()
                val message = it.toSdkErrorMessage()
                lastError = message
                connectionState = when {
                    cachedConfigAvailable && networkFailure -> "ready_from_cache_network_unavailable"
                    cachedConfigAvailable -> "ready_from_cache_bootstrap_failed"
                    networkFailure -> "network_unavailable"
                    else -> "bootstrap_failed"
                }
                bootstrapFinished = true
                bootstrapInFlight = false
                notifyReady(cachedConfigAvailable)
                SDKTelemetry.track("sdk_bootstrap_failed", mapOf("message" to message, "network_failure" to networkFailure))
                SDKTelemetry.recordNonFatal(it, mapOf("state" to connectionState))
                if (cachedConfigAvailable) {
                    notifyListeners { listener -> listener.onOfflineMode(message) }
                } else {
                    notifyListeners { listener -> listener.onError(message) }
                    notifyListeners { listener -> listener.onRetry(message) }
                }
            }
        }
    }

    /*
     * Only preload startup ads ONCE.
     */
    private fun preloadAdsIfNeeded(activity: Activity) {
        if (startupPreloadDone) {
            return
        }
        startupPreloadDone = true

        /*
         * IMPORTANT:
         *
         * Only preload interstitials.
         *
         * Rewarded:
         * load contextually.
         *
         * App Open:
         * load on foreground.
         *
         * Rewarded Interstitial:
         * load contextually.
         */
        ads.preloadAll(activity)
    }

    private fun initializeMobileAds(activity: Activity, onInitialized: () -> Unit = {}) {
        if (mobileAdsInitialized) {
            onInitialized()
            return
        }
        val appId = config.ads.admobAppId?.takeIf { it.isNotBlank() } ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            MobileAds.initialize(activity, InitializationConfig.Builder(appId).build()) {
                mobileAdsInitialized = true
                onInitialized()
            }
        }
    }

    @JvmStatic
    fun refreshConfig(onComplete: ((Boolean) -> Unit)? = null) {
        SDKTelemetry.track("config_refresh_requested", mapOf("current_version" to config.configVersion))
        scope.launch {
            val updated = runCatching {
                repository?.syncConfig(config.configVersion)?.let {
                    config = it
                    notifyListeners { listener -> listener.onConfigLoaded(config) }
                    true
                } ?: false
            }.onFailure {
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "config_refresh"))
            }.getOrDefault(false)
            SDKTelemetry.track(
                if (updated) "config_refresh_succeeded" else "config_refresh_no_update",
                mapOf("config_version" to config.configVersion),
            )
            onComplete?.invoke(updated)
        }
    }

    @JvmStatic
    fun isReady(): Boolean { return config.configVersion > 0 }

    @JvmStatic
    fun lastError(): String? = lastError

    @JvmStatic
    fun connectionState(): String = connectionState

    @JvmStatic
    fun diagnostics(): Map<String, Any?> = mapOf(
        "ready" to isReady(),
        "state" to connectionState,
        "last_error" to lastError,
        "config_version" to config.configVersion,
        "bootstrap_finished" to bootstrapFinished,
        "bootstrap_in_flight" to bootstrapInFlight,
    )

    @JvmStatic
    fun onReady(callback: (Boolean) -> Unit) {
        if (bootstrapFinished || config.configVersion > 0) {
            mainHandler.post { callback(config.configVersion > 0) }
            return
        }
        readyCallbacks.add(callback)
    }

    @JvmStatic
    fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
        return config.features[key].asBoolean(defaultValue)
    }

    @JvmStatic
    fun getString(key: String, defaultValue: String = ""): String {
        return config.remoteConfig[key]?.toString() ?: defaultValue
    }

    @JvmStatic
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return config.remoteConfig[key].asBoolean(defaultValue)
    }

    @JvmStatic
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return when (val value = config.remoteConfig[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    @JvmStatic
    fun getDouble(key: String, defaultValue: Double = 0.0): Double {
        return when (val value = config.remoteConfig[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    @JvmStatic
    fun getRemoteConfig(key: String): Map<String, Any?> {
        return config.remoteConfig[key] as? Map<String, Any?> ?: emptyMap()
    }

    @JvmStatic
    fun getRemoteModule(name: String): Map<String, Any?> {
        val modules = config.remoteConfig["modules"] as? Map<*, *> ?: return emptyMap()
        return modules[name] as? Map<String, Any?> ?: emptyMap()
    }

    @JvmStatic
    fun getApiConfig(key: String): ApiKeyConfig? {
        config.apiKeys[key]?.let { return it.sanitizedApiKeyConfig() }
        return config.apiProviders[key]?.let {
            ApiKeyConfig(
                name = it.provider,
                provider = it.provider,
                proxyEndpoint = it.proxyEndpoint.cleanConfigString(),
                baseUrl = null,
                description = "Server-side API provider proxy. Raw keys are not exposed to the app.",
            )
        }
    }

    @JvmStatic
    fun getApiKey(key: String, defaultValue: String = ""): String {
        val apiConfig = config.apiKeys[key] ?: return defaultValue
        val value = apiConfig.value.cleanConfigString() ?: return defaultValue
        reportApiKeyUsage(key, apiConfig)
        return value
    }

    @JvmStatic
    fun getApiBaseUrl(key: String, defaultValue: String = ""): String {
        return config.apiKeys[key]?.baseUrl.normalizeBaseUrl()
            ?: defaultValue.normalizeBaseUrl()
            ?: defaultValue
    }

    @JvmStatic
    fun getApiProxyEndpoint(key: String, defaultValue: String = ""): String {
        return config.apiKeys[key]?.proxyEndpoint.cleanConfigString()
            ?: config.apiProviders[key]?.proxyEndpoint.cleanConfigString()
            ?: defaultValue.cleanConfigString()
            ?: defaultValue
    }

    @JvmStatic
    fun getApiProxyBaseUrl(key: String, defaultValue: String = ""): String {
        val endpoint = repository?.endpointBaseUrl().cleanConfigString() ?: return defaultValue
        val proxy = getApiProxyEndpoint(key).cleanConfigString() ?: return defaultValue
        return (endpoint.trimEnd('/') + "/" + proxy.trim('/')).normalizeBaseUrl()
            ?: defaultValue.normalizeBaseUrl()
            ?: defaultValue
    }

    @JvmStatic
    fun getApiProxyUrl(key: String, path: String = "", defaultValue: String = ""): String {
        val base = getApiProxyBaseUrl(key, defaultValue).normalizeBaseUrl() ?: return defaultValue
        val cleanPath = path.trim('/')
        return if (cleanPath.isBlank()) base else base + cleanPath
    }

    @JvmStatic
    fun apiProxyInterceptor(): Interceptor? = repository?.sdkSigningInterceptor()

    @JvmStatic
    fun createApiProxyOkHttpClient(baseClient: OkHttpClient? = null): OkHttpClient {
        val builder = baseClient?.newBuilder() ?: OkHttpClient.Builder()
        repository?.sdkSigningInterceptor()?.let { builder.addInterceptor(it) }
        return builder.build()
    }

    @JvmStatic
    fun isAdFree(): Boolean {
        return ::subscriptions.isInitialized && subscriptions.isAdFree()
    }

    @JvmStatic
    fun getCurrentSubscription(): SubscriptionPlanInfo? {
        return if (::subscriptions.isInitialized) subscriptions.currentSubscription() else null
    }

    @JvmStatic
    fun canChangeSubscriptionPlan(): Boolean {
        return ::subscriptions.isInitialized && subscriptions.canChangeSubscriptionPlan()
    }

    @JvmStatic
    fun restorePurchases(onComplete: ((Boolean) -> Unit)? = null) {
        if (::subscriptions.isInitialized) {
            subscriptions.restorePurchases(onComplete)
        } else {
            onComplete?.invoke(false)
        }
    }

    @JvmStatic
    fun syncNotificationToken(token: String, provider: String = "itwing") {
        SDKTelemetry.track("notification_token_sync_requested", mapOf("provider" to provider))
        NotificationRuntimeManager.registerDeviceToken(token, provider, repository)
    }

    @JvmStatic
    fun billingDiagnostics(): Map<String, Any?> {
        return if (::subscriptions.isInitialized) subscriptions.diagnostics() else emptyMap()
    }

    @JvmStatic
    fun getAppTitle(defaultValue: String = ""): String { return config.app["title"] as? String ?: config.app["name"] as? String ?: defaultValue }

    @JvmStatic
    fun getAppUrl(kind: String): String? {
        val legal = config.app["legal"] as? Map<*, *>
        fun legalUrl(type: String): String? = (legal?.get(type) as? Map<*, *>)?.get("url") as? String
        return when (kind)
        {
            "privacy" -> config.app["privacy_policy_url"] as? String ?: legalUrl("privacy_policy")
            "terms" -> config.app["terms_url"] as? String ?: legalUrl("terms")
            "disclaimer" -> config.app["disclaimer_url"] as? String ?: legalUrl("disclaimer")
            else -> null
        }
    }

    @JvmStatic
    fun getLegalContent(kind: String): String? {
        val legal = config.app["legal"] as? Map<*, *>
        return (legal?.get(legalKey(kind)) as? Map<*, *>)?.get("content") as? String
    }

    @JvmStatic
    fun getLegalFormat(kind: String, defaultValue: String = "markdown"): String {
        val legal = config.app["legal"] as? Map<*, *>
        return (legal?.get(legalKey(kind)) as? Map<*, *>)?.get("format") as? String ?: defaultValue
    }

    @JvmStatic
    fun isMaintenanceMode(): Boolean { return config.app["maintenance"] as? Boolean ?: false }

    @JvmStatic
    fun getAppStatus(defaultValue: String = "active"): String {
        return config.app["status"] as? String ?: defaultValue
    }

    @JvmStatic
    fun getAppIconUrl(): Uri? {
        return (config.app["launcher_icon_url"] as? String)?.toUriOrNull()
            ?: (config.app["icon_url"] as? String)?.toUriOrNull()
            ?: (config.app["splash_logo_url"] as? String)?.toUriOrNull()
    }

    @JvmStatic
    fun getSplashLogoUrl(): Uri? {
        return (config.app["splash_logo_url"] as? String)?.toUriOrNull() ?: getAppIconUrl()
    }

    @JvmStatic
    fun getLauncherIconUrl(): Uri? {
        return (config.app["launcher_icon_url"] as? String)?.toUriOrNull() ?: getAppIconUrl()
    }

    @JvmStatic
    fun getLogoUri(): Uri? = getSplashLogoUrl() ?: getAppIconUrl()

    @JvmStatic
    fun getAppLogoUri(): Uri? = getLogoUri()

    @JvmStatic
    fun getSplashDelayMs(defaultValue: Long = 7000L): Long {
        val splash = config.app["splash"] as? Map<*, *> ?: return defaultValue
        val seconds = splash["seconds"] as? Number ?: return defaultValue
        return seconds.toLong().coerceIn(0L, 10L) * 1000L
    }

    @JvmStatic
    fun getSplashAdFormat(defaultValue: String = "none"): String {
        val splash = config.app["splash"] as? Map<*, *> ?: return defaultValue
        return splash["ad_format"] as? String ?: defaultValue
    }

    @JvmStatic
    fun getColor(name: String, defaultValue: String = ""): String {
        val colors = config.app["colors"] as? Map<*, *> ?: return defaultValue
        return colors[name] as? String ?: defaultValue
    }

    @JvmStatic
    fun showInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_interstitial", mapOf("placement" to placement)) {
            ads.showInterstitial(activity, placement, onComplete)
        }

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onReward: () -> Unit, onComplete: () -> Unit = {}) =
        runSdkCall("show_rewarded", mapOf("placement" to placement)) {
            ads.showRewarded(activity, placement, onReward, onComplete)
        }

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_rewarded", mapOf("placement" to placement)) {
            ads.showRewarded(activity, placement, onComplete)
        }

    @JvmStatic
    fun showRewardedInterstitial(activity: Activity, placement: String, onReward: () -> Unit = {}, onComplete: () -> Unit = {}) =
        runSdkCall("show_rewarded_interstitial", mapOf("placement" to placement)) {
            ads.showRewardedInterstitial(activity, placement, onReward, onComplete)
        }

    @JvmStatic
    fun showAppOpen(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_app_open", mapOf("placement" to placement)) {
            ads.showAppOpen(activity, placement, onComplete)
        }

    @JvmStatic
    fun getCustomAds(format: String? = null): List<CustomAdConfig> {
        return config.ads.customAds
            .filter { format == null || it.format == format }
            .sortedBy { it.priority }
    }

    @JvmStatic
    fun trackCustomAdImpression(customAdId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackCustomAdEvent(customAdId, "impression", metadata)
    }

    @JvmStatic
    fun trackCustomAdClick(customAdId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackCustomAdEvent(customAdId, "click", metadata)
    }

    @JvmStatic
    fun trackCustomAdEvent(customAdId: String, eventType: String, metadata: Map<String, Any?> = emptyMap()) {
        scope.launch {
            val payload = JSONObject()
            metadata.forEach { (key, value) -> payload.put(key, value) }
            analyticsOrNull()?.track("custom_ad_$eventType", metadata + mapOf("custom_ad_id" to customAdId))
            runCatching { repository?.submitCustomAdEvent(customAdId, eventType, payload) }
        }
    }

    @JvmStatic
    fun showSplash(activity: Activity, onComplete: () -> Unit = {}) {
        val startedAt = System.currentTimeMillis()
        fun showRuntimeSplash() {
            if (::updates.isInitialized) {
                updates.checkBeforeSplash(activity) {
                    runtime.showSplash(activity, onComplete)
                }
            } else {
                runtime.showSplash(activity, onComplete)
            }
        }

        fun runWhenReady() {
            val waitedMs = System.currentTimeMillis() - startedAt
            if ((bootstrapFinished && config.configVersion > 0) || (!bootstrapInFlight && config.configVersion > 0) || waitedMs >= 4000L) {
                showRuntimeSplash()
                return
            }
            mainHandler.postDelayed({ runWhenReady() }, 100L)
        }
        runWhenReady()
    }

    @JvmStatic
    fun launchSubscriptionPurchase(activity: Activity, productId: String): com.android.billingclient.api.BillingResult {
        if (::subscriptions.isInitialized) {
            subscriptions.launchPurchaseWhenReady(activity, productId) { }
            return com.android.billingclient.api.BillingResult.newBuilder()
                .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.OK)
                .setDebugMessage("Purchase flow requested. Google Play Billing will open when ready.")
                .build()
        }

        return com.android.billingclient.api.BillingResult.newBuilder()
            .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
            .setDebugMessage("Billing is not initialized yet.")
            .build()
    }

    @JvmStatic
    fun launchSubscriptionPurchase(
        activity: Activity,
        productId: String,
        onResult: ((com.android.billingclient.api.BillingResult) -> Unit)?,
    ) {
        if (::subscriptions.isInitialized) {
            subscriptions.launchPurchaseWhenReady(activity, productId) { result -> onResult?.invoke(result) }
        } else {
            onResult?.invoke(
                com.android.billingclient.api.BillingResult.newBuilder()
                    .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                    .setDebugMessage("Billing is not initialized yet.")
                    .build()
            )
        }
    }

    @JvmStatic
    fun showPurchaseDialog(activity: Activity, onResult: ((com.android.billingclient.api.BillingResult) -> Unit)? = null) {
        if (::subscriptions.isInitialized) {
            subscriptions.showPurchaseDialog(activity) { result -> onResult?.invoke(result) }
        } else {
            onResult?.invoke(
                com.android.billingclient.api.BillingResult.newBuilder()
                    .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                    .setDebugMessage("Billing is not initialized yet.")
                    .build()
            )
        }
    }

    @JvmStatic
    fun createLoadingDialog(activity: Activity): ITWingLoadingDialog {
        return ITWingLoadingDialog(activity) { config.app["loading_lottie_url"] as? String }
    }

    @JvmStatic
    @JvmOverloads
    fun showLoadingDialog(activity: Activity, lottieUrl: String? = null): ITWingLoadingDialog {
        return createLoadingDialog(activity).also {
            it.show(lottieUrl ?: config.app["loading_lottie_url"] as? String)
        }
    }

    @JvmStatic
    fun createActionDialog(activity: Activity): ITWingActionDialog {
        return ITWingActionDialog(
            activity = activity,
            defaultsProvider = { config.app["host_dialog"] as? Map<*, *> ?: emptyMap<Any?, Any?>() },
            primaryColorProvider = { appPrimaryColorInt() },
        )
    }

    @JvmSynthetic
    fun showActionDialog(activity: Activity, onPositive: () -> Unit): ITWingActionDialog {
        return showActionDialog(activity = activity, onPositive = Runnable(onPositive))
    }

    @JvmStatic
    @JvmOverloads
    fun showActionDialog(
        activity: Activity,
        title: String? = null,
        description: String? = null,
        positiveText: String? = null,
        negativeText: String? = null,
        nativePlacement: String? = null,
        nativeType: String? = null,
        onPositive: Runnable? = null,
        onNegative: Runnable? = null,
        onCancel: Runnable? = null,
    ): ITWingActionDialog {
        return createActionDialog(activity).also {
            it.show(
                title = title,
                description = description,
                positiveText = positiveText,
                negativeText = negativeText,
                nativePlacement = nativePlacement,
                nativeType = nativeType,
                onPositive = onPositive,
                onNegative = onNegative,
                onCancel = onCancel,
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    fun bindSubscriptionControls(
        activity: Activity,
        statusView: TextView? = null,
        subscribeButton: View? = null,
        restoreButton: View? = null,
        activeText: String = "Premium active",
        inactiveText: String = "Premium inactive",
    ) {
        val updateUi = {
            val active = isAdFree()
            statusView?.text = if (active) activeText else inactiveText
            subscribeButton?.isEnabled = !active
            restoreButton?.isEnabled = true
        }

        fun showToast(message: String, long: Boolean = false) {
            if (!activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(
                    activity,
                    message,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }

        fun messageFor(result: BillingResult): String {
            return when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    if (isAdFree()) "Purchase active" else "Purchase is pending. It will activate after Google Play confirms it."
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase cancelled"
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> activeText
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Purchase is unavailable right now"
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "Google Play Billing is not available right now"
                else -> result.debugMessage.takeIf { it.isNotBlank() } ?: "Purchase failed"
            }
        }

        mainHandler.post(updateUi)

        subscribeButton?.setOnClickListener {
            if (isAdFree()) {
                showToast(activeText)
                mainHandler.post(updateUi)
                return@setOnClickListener
            }

            subscribeButton.isEnabled = false
            showPurchaseDialog(activity) { result ->
                mainHandler.post {
                    updateUi()
                    showToast(messageFor(result), long = true)
                }
            }
        }

        restoreButton?.setOnClickListener {
            restoreButton.isEnabled = false
            restorePurchases { restored ->
                mainHandler.post {
                    updateUi()
                    showToast(if (restored) "Purchase restored" else "No active purchase found")
                }
            }
        }

        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    mainHandler.post(updateUi)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                }
            })
        }
    }

    @JvmStatic
    @JvmOverloads
    fun checkForUpdates(activity: Activity, force: Boolean = true) {
        if (::updates.isInitialized) {
            updates.check(activity, force)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun checkForUpdates(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        force: Boolean = true,
        onResult: ((String) -> Unit)? = null,
    ) {
        if (::updates.isInitialized) {
            updates.check(activity, force, launcher, onResult)
        } else {
            onResult?.invoke("SDK is not initialized yet.")
        }
    }

    @JvmStatic
    fun resumeInAppUpdate(activity: Activity) {
        if (::updates.isInitialized) {
            updates.onResume(activity)
        }
    }

    @JvmStatic
    fun firebaseAuth(): com.google.firebase.auth.FirebaseAuth? = FirebaseRuntimeManager.auth()

    private fun notifyReady(success: Boolean) {
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { callback -> mainHandler.post { callback(success) } }
        if (success) {
            notifyListeners { it.onReady() }
        }
    }

    private fun legalKey(kind: String): String = when (kind) {
        "privacy" -> "privacy_policy"
        "terms" -> "terms"
        "disclaimer" -> "disclaimer"
        else -> kind
    }

//    private fun registerLifecycleAutomation(application: Application) {
//        if (lifecycleTrackingRegistered) return
//        lifecycleTrackingRegistered = true
//        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
//            override fun onActivityResumed(activity: Activity) {
//                activeActivity = activity
//                if (::analytics.isInitialized) {
//                    analytics.track("screen_view", mapOf("screen" to activity.javaClass.simpleName))
//                }
//            }
//            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
//            override fun onActivityStarted(activity: Activity) {}
//            override fun onActivityPaused(activity: Activity) {}
//            override fun onActivityStopped(activity: Activity) {}
//            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
//            override fun onActivityDestroyed(activity: Activity) {}
//        })
//    }

    private fun registerLifecycleAutomation(application: Application) {

        if (lifecycleTrackingRegistered) return

        lifecycleTrackingRegistered = true

        application.registerActivityLifecycleCallbacks(

            object : Application.ActivityLifecycleCallbacks {

                override fun onActivityResumed(activity: Activity) {

                    /*
                     |--------------------------------------------------------------------------
                     | Store weak reference only
                     |--------------------------------------------------------------------------
                     */

                    activeActivity = WeakReference(activity)
                    ads.updateForegroundActivity(activity)
                    NotificationRuntimeManager.reportOpened(activity.intent?.getStringExtra("itwing_notification_id"))
                    NotificationRuntimeManager.syncNow()
                    if (::updates.isInitialized) {
                        updates.onResume(activity)
                    }

                    /*
                     |--------------------------------------------------------------------------
                     | Analytics
                     |--------------------------------------------------------------------------
                     */

                    if (::analytics.isInitialized) {

                        analytics.track(
                            "screen_view",
                            mapOf(
                                "screen" to activity.javaClass.simpleName
                            )
                        )
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                }

                override fun onActivityStarted(
                    activity: Activity
                ) {
                    foregroundActivityCount += 1
                    if (foregroundActivityCount == 1) {
                        foregroundStartedAtMs = System.currentTimeMillis()
                        SDKTelemetry.track(
                            "app_foreground",
                            mapOf("activity" to activity.javaClass.simpleName),
                        )
                    }
                }

                override fun onActivityPaused(
                    activity: Activity
                ) {
                }

                override fun onActivityStopped(
                    activity: Activity
                ) {
                    foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                    if (foregroundActivityCount == 0) {
                        val durationMs = if (foregroundStartedAtMs > 0L) {
                            System.currentTimeMillis() - foregroundStartedAtMs
                        } else {
                            0L
                        }
                        SDKTelemetry.track(
                            "app_background",
                            mapOf(
                                "activity" to activity.javaClass.simpleName,
                                "session_duration_ms" to durationMs,
                            ),
                        )
                        analyticsOrNull()?.flush()
                    }
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) {
                }

                override fun onActivityDestroyed(
                    activity: Activity
                ) {

                    /*
                     |--------------------------------------------------------------------------
                     | Clear weak reference if destroyed
                     |--------------------------------------------------------------------------
                     */

                    if (activeActivity?.get() === activity) {

                        activeActivity?.clear()

                        activeActivity = null
                    }
                }
            }
        )
    }

    /*
     |--------------------------------------------------------------------------
     | Weak Activity Reference
     |--------------------------------------------------------------------------
     |
     | NEVER store Activity strongly inside singleton/static objects.
     | WeakReference prevents memory leaks.
     |
     */

    @Volatile
    private var activeActivity: WeakReference<Activity>? = null

    /*
     |--------------------------------------------------------------------------
     | Safe Current Activity Access
     |--------------------------------------------------------------------------
     */

    private fun getActiveActivity(): Activity? {
        val activity = activeActivity?.get()
        return if (
            activity == null ||
            activity.isFinishing ||
            activity.isDestroyed
        ) {
            null
        } else {
            activity
        }
    }

//    @Volatile
//    private var activeActivity: Activity? = null

    private fun notifyListeners(callback: (SDKInitListener) -> Unit) {
        initListeners.forEach { listener -> mainHandler.post { runCatching { callback(listener) } } }
    }

    private fun analyticsOrNull(): AnalyticsClient? = if (::analytics.isInitialized) analytics else null

    private fun runSdkCall(operation: String, properties: Map<String, Any?> = emptyMap(), block: () -> Unit) {
        SDKTelemetry.track("sdk_call_requested", mapOf("operation" to operation) + properties)
        runCatching { block() }.onFailure {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to operation) + properties)
            SDKTelemetry.track("sdk_call_failed", mapOf("operation" to operation, "message" to (it.message ?: "unknown")) + properties)
        }
    }

    private fun readyListener(onReady: () -> Unit): SDKInitListener {
        return object : SDKInitListener {
            override fun onReady() = onReady()
        }
    }

    private fun appPrimaryColorInt(): Int {
        val color = listOf(
            getColor("primary"),
            getColor("primary_color"),
            config.app["primary_color"]?.toString().orEmpty(),
        ).firstOrNull { it.isNotBlank() } ?: "#2563EB"
        return runCatching { android.graphics.Color.parseColor(color) }
            .getOrDefault(android.graphics.Color.rgb(37, 99, 235))
    }

    private fun ApiKeyConfig.sanitizedApiKeyConfig(): ApiKeyConfig {
        return copy(
            id = id.cleanConfigString(),
            value = value.cleanConfigString().orEmpty(),
            provider = provider.cleanConfigString(),
            proxyEndpoint = proxyEndpoint.cleanConfigString(),
            baseUrl = baseUrl.normalizeBaseUrl(),
            description = description.cleanConfigString(),
        )
    }

    private fun reportApiKeyUsage(key: String, apiConfig: ApiKeyConfig) {
        scope.launch(Dispatchers.IO) {
            val response = runCatching {
                repository?.reportApiKeyUsage(key, apiConfig.id)
            }.getOrNull()
            val shouldRotate = response
                ?.optJSONObject("data")
                ?.optBoolean("rotate", false) == true
            if (shouldRotate) {
                refreshConfig()
            }
        }
    }

    private fun String?.cleanConfigString(): String? {
        val value = this?.trim() ?: return null
        return value.takeUnless {
            it.isBlank() ||
                it.equals("null", ignoreCase = true) ||
                it.equals("undefined", ignoreCase = true)
        }
    }

    private fun String?.normalizeBaseUrl(): String? {
        val value = cleanConfigString() ?: return null
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return if (value.endsWith('/')) value else "$value/"
    }

    private fun Throwable.isNetworkFailure(): Boolean {
        return this is UnknownHostException ||
            this is SocketTimeoutException ||
            message?.contains("network_dns_unavailable", ignoreCase = true) == true ||
            cause?.isNetworkFailure() == true
    }

    private fun Throwable.toSdkErrorMessage(): String {
        val raw = message ?: cause?.message ?: "unknown"
        return when {
            raw.contains("network_dns_unavailable", ignoreCase = true) -> raw
            this is SocketTimeoutException || cause is SocketTimeoutException ->
                "network_timeout: SDK config request timed out. Cached config will be used when available."
            else -> raw
        }
    }

    private fun String.toUriOrNull(): Uri? = takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }

    private fun Any?.asBoolean(defaultValue: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> toInt() != 0
            is String -> equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)
            else -> defaultValue
        }
    }
}
