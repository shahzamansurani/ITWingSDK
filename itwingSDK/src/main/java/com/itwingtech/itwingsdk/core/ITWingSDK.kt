package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.itwingtech.itwingsdk.ads.AdManager
import com.itwingtech.itwingsdk.analytics.AnalyticsClient
import com.itwingtech.itwingsdk.analytics.InstallReferrerReporter
import com.itwingtech.itwingsdk.billing.SubscriptionManager
import com.itwingtech.itwingsdk.data.ConfigRepository
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
        notifyListeners { it.onAnalyticsReady() }
        registerLifecycleAutomation(activity.application)
        if (repository!!.consumeFirstOpen()) {
            analytics.track("first_open")
            InstallReferrerReporter(activity.applicationContext, repository!!).collect()
        }
        analytics.track("app_open")
        analytics.track("session_start")
        updates = InAppUpdateManager { config }
        subscriptions = SubscriptionManager({ config }, { repository })
        scope.launch {
            /*
             * Load cached config first
             * for instant startup.
             */
            config = repository?.loadCachedConfig() ?: ITWingConfig()
            if (config.configVersion > 0) {
                connectionState = "cached_config_loaded"
                lastError = null
                notifyListeners { it.onConfigLoaded(config) }
                notifyListeners { it.onOfflineMode("Loaded cached SDK config; refreshing remote config in background.") }
            }

            /*
             * Initialize SDK early
             * but DO NOT preload ads yet.
             */
            if (config.configVersion > 0) {
                initializeMobileAds(activity) {
                    ads.startAutomaticAppOpen(activity)
                }
                NotificationRuntimeManager.configure(activity.applicationContext, config, repository)
                notifyListeners { it.onNotificationsReady() }
                updates.check(activity)
                subscriptions.connect(activity)
                notifyListeners { it.onBillingReady() }
                subscriptions.restorePurchases()
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
                notifyReady(true)
                notifyListeners { it.onConfigLoaded(remote) }
                initializeMobileAds(activity) {
                    preloadAdsIfNeeded(activity)
                    ads.startAutomaticAppOpen(activity)
                    notifyListeners { it.onAdsReady() }
                }
                NotificationRuntimeManager.configure(activity.applicationContext, config, repository)
                notifyListeners { it.onNotificationsReady() }
                updates.check(activity)
                subscriptions.connect(activity)
                notifyListeners { it.onBillingReady() }
                subscriptions.restorePurchases()

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
                analytics.track("sdk_bootstrap_failed", mapOf("message" to message, "network_failure" to networkFailure))
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
        scope.launch {
            val updated = runCatching {
                repository?.syncConfig(config.configVersion)?.let {
                    config = it
                    notifyListeners { listener -> listener.onConfigLoaded(config) }
                    true
                } ?: false
            }.getOrDefault(false)
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
    fun getApiConfig(key: String): ApiKeyConfig? {
        config.apiKeys[key]?.let { return it }
        return config.apiProviders[key]?.let {
            ApiKeyConfig(
                name = it.provider,
                provider = it.provider,
                proxyEndpoint = it.proxyEndpoint,
                baseUrl = null,
                description = "Server-side API provider proxy. Raw keys are not exposed to the app.",
            )
        }
    }

    @JvmStatic
    fun getApiKey(key: String, defaultValue: String = ""): String {
        return config.apiKeys[key]?.value?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    @JvmStatic
    fun getApiBaseUrl(key: String, defaultValue: String = ""): String {
        return config.apiKeys[key]?.baseUrl?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    @JvmStatic
    fun getApiProxyEndpoint(key: String, defaultValue: String = ""): String {
        return config.apiKeys[key]?.proxyEndpoint?.takeIf { it.isNotBlank() }
            ?: config.apiProviders[key]?.proxyEndpoint?.takeIf { it.isNotBlank() }
            ?: defaultValue
    }

    @JvmStatic
    fun isAdFree(): Boolean {
        return ::subscriptions.isInitialized && subscriptions.isAdFree()
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
    fun syncNotificationToken(token: String, provider: String = "fcm") {
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
    fun getSplashDelayMs(defaultValue: Long = 2000L): Long {
        val splash = config.app["splash"] as? Map<*, *> ?: return defaultValue
        val seconds = splash["seconds"] as? Number ?: return defaultValue
        return seconds.toLong().coerceIn(0L, 10L) * 1000L
    }

    @JvmStatic
    fun getSplashAdFormat(defaultValue: String = "app_open"): String {
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
        ads.showInterstitial(activity, placement, onComplete)

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onReward: () -> Unit, onComplete: () -> Unit = {}) =
        ads.showRewarded(activity, placement, onReward, onComplete)

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        ads.showRewarded(activity, placement, onComplete)

    @JvmStatic
    fun showRewardedInterstitial(activity: Activity, placement: String, onReward: () -> Unit = {}, onComplete: () -> Unit = {}) =
        ads.showRewardedInterstitial(activity, placement, onReward, onComplete)

    @JvmStatic
    fun showAppOpen(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        ads.showAppOpen(activity, placement, onComplete)

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
        fun runWhenReady() {
            val waitedMs = System.currentTimeMillis() - startedAt
            if ((bootstrapFinished && config.configVersion > 0) || (!bootstrapInFlight && config.configVersion > 0) || waitedMs >= 4000L) {
                runtime.showSplash(activity, onComplete)
                return
            }
            mainHandler.postDelayed({ runWhenReady() }, 100L)
        }
        runWhenReady()
    }

    @JvmStatic
    fun launchSubscriptionPurchase(activity: Activity, productId: String) =
        subscriptions.launchPurchase(activity, productId)

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
                }

                override fun onActivityPaused(
                    activity: Activity
                ) {
                }

                override fun onActivityStopped(
                    activity: Activity
                ) {
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

    private fun readyListener(onReady: () -> Unit): SDKInitListener {
        return object : SDKInitListener {
            override fun onReady() = onReady()
        }
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


//package com.itwingtech.itwingsdk.core
//
//import android.app.Activity
//import android.app.Application
//import android.net.Uri
//import android.os.Bundle
//import com.google.android.libraries.ads.mobile.sdk.MobileAds
//import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
//import com.itwingtech.itwingsdk.ads.AdManager
//import com.itwingtech.itwingsdk.analytics.AnalyticsClient
//import com.itwingtech.itwingsdk.analytics.InstallReferrerReporter
//import com.itwingtech.itwingsdk.billing.SubscriptionManager
//import com.itwingtech.itwingsdk.data.ConfigRepository
//import com.itwingtech.itwingsdk.updates.InAppUpdateManager
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.SupervisorJob
//import kotlinx.coroutines.launch
//import android.os.Handler
//import android.os.Looper
//import java.util.concurrent.CopyOnWriteArrayList
//import org.json.JSONObject
//import java.lang.ref.WeakReference
//import androidx.core.net.toUri
//
//object ITWingSDK {
//    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
//    private val mainHandler = Handler(Looper.getMainLooper())
//    private var repository: ConfigRepository? = null
//    private var config = ITWingConfig()
//    private val runtime = AppRuntimeManager(
//        configProvider = { config },
//        adManagerProvider = { ads },
//    )
//    @Volatile
//    private var mobileAdsInitialized = false
//    @Volatile
//    private var startupPreloadDone = false
//    @Volatile
//    private var bootstrapFinished = false
//    @Volatile
//    private var bootstrapInFlight = false
//    @Volatile
//    private var lastError: String? = "not_initialized"
//    @Volatile
//    private var connectionState: String = "not_initialized"
//    @Volatile
//    private var lifecycleTrackingRegistered = false
//    private val readyCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
//    private val initListeners = CopyOnWriteArrayList<SDKInitListener>()
//
//    val ads: AdManager = AdManager(configProvider = { config }, suppressAdsProvider = { ::subscriptions.isInitialized && subscriptions.isAdFree() })
//    lateinit var analytics: AnalyticsClient private set
//    lateinit var updates: InAppUpdateManager private set
//    lateinit var subscriptions: SubscriptionManager private set
//    @JvmStatic
//    @JvmOverloads
//    fun initialize(activity: Activity, apiKey: String, listener: SDKInitListener? = null) {
//        listener?.let { initListeners.add(it) }
//        repository = ConfigRepository(activity.applicationContext, apiKey, ITWingOptions())
//        bootstrapFinished = false
//        bootstrapInFlight = true
//        connectionState = "bootstrap_in_progress"
//        lastError = "bootstrap_in_progress"
//        analytics = AnalyticsClient(repository!!)
//        notifyListeners { it.onAnalyticsReady() }
//        registerLifecycleAutomation(activity.application)
//        if (repository!!.consumeFirstOpen()) {
//            analytics.track("first_open")
//            InstallReferrerReporter(activity.applicationContext, repository!!).collect()
//        }
//        analytics.track("app_open")
//        analytics.track("session_start")
//        updates = InAppUpdateManager { config }
//        subscriptions = SubscriptionManager({ config }, { repository })
//        scope.launch {
//            /*
//             * Load cached config first
//             * for instant startup.
//             */
//            config = repository?.loadCachedConfig() ?: ITWingConfig()
//            if (config.configVersion > 0) {
//                connectionState = "cached_config_loaded"
//                lastError = null
//                notifyListeners { it.onConfigLoaded(config) }
//                notifyListeners { it.onOfflineMode("Using cached SDK config while remote bootstrap is pending.") }
//            }
//
//            /*
//             * Initialize SDK early
//             * but DO NOT preload ads yet.
//             */
//            if (config.configVersion > 0) {
//                initializeMobileAds(activity) {
//                    ads.startAutomaticAppOpen(activity)
//                }
//                NotificationRuntimeManager.configure(activity.applicationContext, config, repository)
//                notifyListeners { it.onNotificationsReady() }
//                updates.check(activity)
//                subscriptions.connect(activity)
//                notifyListeners { it.onBillingReady() }
//                subscriptions.restorePurchases()
//            }
//
//            /*
//             * Fetch fresh remote config
//             */
//            runCatching { repository!!.bootstrap() }.onSuccess { remote ->
//                config = remote
//                lastError = null
//                connectionState = "ready"
//                bootstrapFinished = true
//                bootstrapInFlight = false
//                notifyReady(true)
//                notifyListeners { it.onConfigLoaded(remote) }
//                initializeMobileAds(activity) {
//                    preloadAdsIfNeeded(activity)
//                    ads.startAutomaticAppOpen(activity)
//                    notifyListeners { it.onAdsReady() }
//                }
//                NotificationRuntimeManager.configure(activity.applicationContext, config, repository)
//                notifyListeners { it.onNotificationsReady() }
//                updates.check(activity)
//                subscriptions.connect(activity)
//                notifyListeners { it.onBillingReady() }
//                subscriptions.restorePurchases()
//
//            }.onFailure {
//                lastError = it.message ?: "unknown"
//                connectionState = if (config.configVersion > 0) "ready_from_cache_bootstrap_failed" else "bootstrap_failed"
//                bootstrapFinished = true
//                bootstrapInFlight = false
//                notifyReady(config.configVersion > 0)
//                analytics.track("sdk_bootstrap_failed", mapOf("message" to (it.message ?: "unknown")))
//                notifyListeners { listener -> listener.onError(lastError ?: "unknown") }
//                if (config.configVersion > 0) {
//                    notifyListeners { listener -> listener.onOfflineMode(lastError ?: "remote bootstrap failed") }
//                } else {
//                    notifyListeners { listener -> listener.onRetry(lastError ?: "remote bootstrap failed") }
//                }
//            }
//        }
//    }
//
//    /*
//     * Only preload startup ads ONCE.
//     */
//    private fun preloadAdsIfNeeded(activity: Activity) {
//        if (startupPreloadDone) {
//            return
//        }
//        startupPreloadDone = true
//
//        /*
//         * IMPORTANT:
//         *
//         * Only preload interstitials.
//         *
//         * Rewarded:
//         * load contextually.
//         *
//         * App Open:
//         * load on foreground.
//         *
//         * Rewarded Interstitial:
//         * load contextually.
//         */
//        ads.preloadAll(activity)
//    }
//
//    private fun initializeMobileAds(activity: Activity, onInitialized: () -> Unit = {}) {
//        if (mobileAdsInitialized) {
//            onInitialized()
//            return
//        }
//        val appId = config.ads.admobAppId?.takeIf { it.isNotBlank() } ?: return
//        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
//            MobileAds.initialize(activity, InitializationConfig.Builder(appId).build()) {
//                mobileAdsInitialized = true
//                onInitialized()
//            }
//        }
//    }
//
//    @JvmStatic
//    fun refreshConfig(onComplete: ((Boolean) -> Unit)? = null) {
//        scope.launch {
//            val updated = runCatching {
//                repository?.syncConfig(config.configVersion)?.let {
//                    config = it
//                    notifyListeners { listener -> listener.onConfigLoaded(config) }
//                    true
//                } ?: false
//            }.getOrDefault(false)
//            onComplete?.invoke(updated)
//        }
//    }
//
//    @JvmStatic
//    fun isReady(): Boolean { return config.configVersion > 0 }
//
//    @JvmStatic
//    fun lastError(): String? = lastError
//
//    @JvmStatic
//    fun connectionState(): String = connectionState
//
//    @JvmStatic
//    fun diagnostics(): Map<String, Any?> = mapOf(
//        "ready" to isReady(),
//        "state" to connectionState,
//        "last_error" to lastError,
//        "config_version" to config.configVersion,
//        "bootstrap_finished" to bootstrapFinished,
//        "bootstrap_in_flight" to bootstrapInFlight,
//    )
//
//    @JvmStatic
//    fun onReady(callback: (Boolean) -> Unit) {
//        if (bootstrapFinished || config.configVersion > 0) {
//            mainHandler.post { callback(config.configVersion > 0) }
//            return
//        }
//        readyCallbacks.add(callback)
//    }
//
//    @JvmStatic
//    fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
//        return config.features[key].asBoolean(defaultValue)
//    }
//
//    @JvmStatic
//    fun getString(key: String, defaultValue: String = ""): String {
//        return config.remoteConfig[key]?.toString() ?: defaultValue
//    }
//
//    @JvmStatic
//    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
//        return config.remoteConfig[key].asBoolean(defaultValue)
//    }
//
//    @JvmStatic
//    fun getInt(key: String, defaultValue: Int = 0): Int {
//        return when (val value = config.remoteConfig[key]) {
//            is Number -> value.toInt()
//            is String -> value.toIntOrNull() ?: defaultValue
//            else -> defaultValue
//        }
//    }
//
//    @JvmStatic
//    fun getApiConfig(key: String): ApiKeyConfig? {
//        config.apiKeys[key]?.let { return it }
//        return config.apiProviders[key]?.let {
//            ApiKeyConfig(
//                name = it.provider,
//                provider = it.provider,
//                proxyEndpoint = it.proxyEndpoint,
//                baseUrl = null,
//                description = "Server-side API provider proxy. Raw keys are not exposed to the app.",
//            )
//        }
//    }
//
//    @JvmStatic
//    fun getApiKey(key: String, defaultValue: String = ""): String {
//        return config.apiKeys[key]?.value?.takeIf { it.isNotBlank() } ?: defaultValue
//    }
//
//    @JvmStatic
//    fun getApiBaseUrl(key: String, defaultValue: String = ""): String {
//        return config.apiKeys[key]?.baseUrl?.takeIf { it.isNotBlank() } ?: defaultValue
//    }
//
//    @JvmStatic
//    fun getApiProxyEndpoint(key: String, defaultValue: String = ""): String {
//        return config.apiKeys[key]?.proxyEndpoint?.takeIf { it.isNotBlank() }
//            ?: config.apiProviders[key]?.proxyEndpoint?.takeIf { it.isNotBlank() }
//            ?: defaultValue
//    }
//
//    @JvmStatic
//    fun isAdFree(): Boolean {
//        return ::subscriptions.isInitialized && subscriptions.isAdFree()
//    }
//
//    @JvmStatic
//    fun restorePurchases(onComplete: ((Boolean) -> Unit)? = null) {
//        if (::subscriptions.isInitialized) {
//            subscriptions.restorePurchases(onComplete)
//        } else {
//            onComplete?.invoke(false)
//        }
//    }
//
//    @JvmStatic
//    fun syncNotificationToken(token: String, provider: String = "fcm") {
//        NotificationRuntimeManager.registerDeviceToken(token, provider, repository)
//    }
//
//    @JvmStatic
//    fun billingDiagnostics(): Map<String, Any?> {
//        return if (::subscriptions.isInitialized) subscriptions.diagnostics() else emptyMap()
//    }
//
//    @JvmStatic
//    fun getAppTitle(defaultValue: String = ""): String { return config.app["title"] as? String ?: config.app["name"] as? String ?: defaultValue }
//
//    @JvmStatic
//    fun getAppUrl(kind: String): String? {
//        val legal = config.app["legal"] as? Map<*, *>
//        fun legalUrl(type: String): String? = (legal?.get(type) as? Map<*, *>)?.get("url") as? String
//        return when (kind)
//        {
//            "privacy" -> config.app["privacy_policy_url"] as? String ?: legalUrl("privacy_policy")
//            "terms" -> config.app["terms_url"] as? String ?: legalUrl("terms")
//            "disclaimer" -> config.app["disclaimer_url"] as? String ?: legalUrl("disclaimer")
//            else -> null
//        }
//    }
//
//    @JvmStatic
//    fun getLegalContent(kind: String): String? {
//        val legal = config.app["legal"] as? Map<*, *>
//        return (legal?.get(legalKey(kind)) as? Map<*, *>)?.get("content") as? String
//    }
//
//    @JvmStatic
//    fun getLegalFormat(kind: String, defaultValue: String = "markdown"): String {
//        val legal = config.app["legal"] as? Map<*, *>
//        return (legal?.get(legalKey(kind)) as? Map<*, *>)?.get("format") as? String ?: defaultValue
//    }
//
//    @JvmStatic
//    fun isMaintenanceMode(): Boolean { return config.app["maintenance"] as? Boolean ?: false }
//
//    @JvmStatic
//    fun getAppStatus(defaultValue: String = "active"): String {
//        return config.app["status"] as? String ?: defaultValue
//    }
//
//    @JvmStatic
//    fun getAppIconUrl(): Uri? {
//        return (config.app["launcher_icon_url"] as? String)?.toUriOrNull()
//            ?: (config.app["icon_url"] as? String)?.toUriOrNull()
//            ?: (config.app["splash_logo_url"] as? String)?.toUriOrNull()
//    }
//
//    @JvmStatic
//    fun getSplashLogoUrl(): Uri? {
//        return (config.app["splash_logo_url"] as? String)?.toUriOrNull() ?: getAppIconUrl()
//    }
//
//    @JvmStatic
//    fun getLauncherIconUrl(): Uri? {
//        return (config.app["launcher_icon_url"] as? String)?.toUriOrNull() ?: getAppIconUrl()
//    }
//
//    @JvmStatic
//    fun getLogoUri(): Uri? = getSplashLogoUrl() ?: getAppIconUrl()
//
//    @JvmStatic
//    fun getAppLogoUri(): Uri? = getLogoUri()
//
//    @JvmStatic
//    fun getSplashDelayMs(defaultValue: Long = 2000L): Long {
//        val splash = config.app["splash"] as? Map<*, *> ?: return defaultValue
//        val seconds = splash["seconds"] as? Number ?: return defaultValue
//        return seconds.toLong().coerceIn(0L, 10L) * 1000L
//    }
//
//    @JvmStatic
//    fun getSplashAdFormat(defaultValue: String = "app_open"): String {
//        val splash = config.app["splash"] as? Map<*, *> ?: return defaultValue
//        return splash["ad_format"] as? String ?: defaultValue
//    }
//
//    @JvmStatic
//    fun getColor(name: String, defaultValue: String = ""): String {
//        val colors = config.app["colors"] as? Map<*, *> ?: return defaultValue
//        return colors[name] as? String ?: defaultValue
//    }
//
//    @JvmStatic
//    fun showInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
//        ads.showInterstitial(activity, placement, onComplete)
//
//    @JvmStatic
//    fun showRewarded(activity: Activity, placement: String, onReward: () -> Unit, onComplete: () -> Unit = {}) =
//        ads.showRewarded(activity, placement, onReward, onComplete)
//
//    @JvmStatic
//    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
//        ads.showRewarded(activity, placement, onComplete)
//
//    @JvmStatic
//    fun showRewardedInterstitial(activity: Activity, placement: String, onReward: () -> Unit = {}, onComplete: () -> Unit = {}) =
//        ads.showRewardedInterstitial(activity, placement, onReward, onComplete)
//
//    @JvmStatic
//    fun showAppOpen(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
//        ads.showAppOpen(activity, placement, onComplete)
//
//    @JvmStatic
//    fun getCustomAds(format: String? = null): List<CustomAdConfig> {
//        return config.ads.customAds
//            .filter { format == null || it.format == format }
//            .sortedBy { it.priority }
//    }
//
//    @JvmStatic
//    fun trackCustomAdImpression(customAdId: String, metadata: Map<String, Any?> = emptyMap()) {
//        trackCustomAdEvent(customAdId, "impression", metadata)
//    }
//
//    @JvmStatic
//    fun trackCustomAdClick(customAdId: String, metadata: Map<String, Any?> = emptyMap()) {
//        trackCustomAdEvent(customAdId, "click", metadata)
//    }
//
//    @JvmStatic
//    fun trackCustomAdEvent(customAdId: String, eventType: String, metadata: Map<String, Any?> = emptyMap()) {
//        scope.launch {
//            val payload = JSONObject()
//            metadata.forEach { (key, value) -> payload.put(key, value) }
//            analyticsOrNull()?.track("custom_ad_$eventType", metadata + mapOf("custom_ad_id" to customAdId))
//            runCatching { repository?.submitCustomAdEvent(customAdId, eventType, payload) }
//        }
//    }
//
//    @JvmStatic
//    fun showSplash(activity: Activity, onComplete: () -> Unit = {}) {
//        val startedAt = System.currentTimeMillis()
//        fun runWhenReady() {
//            val waitedMs = System.currentTimeMillis() - startedAt
//            if ((bootstrapFinished && config.configVersion > 0) || (!bootstrapInFlight && config.configVersion > 0) || waitedMs >= 4000L) {
//                runtime.showSplash(activity, onComplete)
//                return
//            }
//            mainHandler.postDelayed({ runWhenReady() }, 100L)
//        }
//        runWhenReady()
//    }
//
//    @JvmStatic
//    fun launchSubscriptionPurchase(activity: Activity, productId: String) =
//        subscriptions.launchPurchase(activity, productId)
//
//    private fun notifyReady(success: Boolean) {
//        val callbacks = readyCallbacks.toList()
//        readyCallbacks.clear()
//        callbacks.forEach { callback -> mainHandler.post { callback(success) } }
//        if (success) {
//            notifyListeners { it.onReady() }
//        }
//    }
//
//    private fun legalKey(kind: String): String = when (kind) {
//        "privacy" -> "privacy_policy"
//        "terms" -> "terms"
//        "disclaimer" -> "disclaimer"
//        else -> kind
//    }
//
////    private fun registerLifecycleAutomation(application: Application) {
////        if (lifecycleTrackingRegistered) return
////        lifecycleTrackingRegistered = true
////        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
////            override fun onActivityResumed(activity: Activity) {
////                activeActivity = activity
////                if (::analytics.isInitialized) {
////                    analytics.track("screen_view", mapOf("screen" to activity.javaClass.simpleName))
////                }
////            }
////            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
////            override fun onActivityStarted(activity: Activity) {}
////            override fun onActivityPaused(activity: Activity) {}
////            override fun onActivityStopped(activity: Activity) {}
////            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
////            override fun onActivityDestroyed(activity: Activity) {}
////        })
////    }
//
//    private fun registerLifecycleAutomation(application: Application) {
//
//        if (lifecycleTrackingRegistered) return
//
//        lifecycleTrackingRegistered = true
//
//        application.registerActivityLifecycleCallbacks(
//
//            object : Application.ActivityLifecycleCallbacks {
//
//                override fun onActivityResumed(activity: Activity) {
//
//                    /*
//                     |--------------------------------------------------------------------------
//                     | Store weak reference only
//                     |--------------------------------------------------------------------------
//                     */
//
//                    activeActivity = WeakReference(activity)
//
//                    /*
//                     |--------------------------------------------------------------------------
//                     | Analytics
//                     |--------------------------------------------------------------------------
//                     */
//
//                    if (::analytics.isInitialized) {
//
//                        analytics.track(
//                            "screen_view",
//                            mapOf(
//                                "screen" to activity.javaClass.simpleName
//                            )
//                        )
//                    }
//                }
//
//                override fun onActivityCreated(
//                    activity: Activity,
//                    savedInstanceState: Bundle?
//                ) {
//                }
//
//                override fun onActivityStarted(
//                    activity: Activity
//                ) {
//                }
//
//                override fun onActivityPaused(
//                    activity: Activity
//                ) {
//                }
//
//                override fun onActivityStopped(
//                    activity: Activity
//                ) {
//                }
//
//                override fun onActivitySaveInstanceState(
//                    activity: Activity,
//                    outState: Bundle
//                ) {
//                }
//
//                override fun onActivityDestroyed(
//                    activity: Activity
//                ) {
//
//                    /*
//                     |--------------------------------------------------------------------------
//                     | Clear weak reference if destroyed
//                     |--------------------------------------------------------------------------
//                     */
//
//                    if (activeActivity?.get() === activity) {
//
//                        activeActivity?.clear()
//
//                        activeActivity = null
//                    }
//                }
//            }
//        )
//    }
//
//    /*
//     |--------------------------------------------------------------------------
//     | Weak Activity Reference
//     |--------------------------------------------------------------------------
//     |
//     | NEVER store Activity strongly inside singleton/static objects.
//     | WeakReference prevents memory leaks.
//     |
//     */
//
//    @Volatile
//    private var activeActivity: WeakReference<Activity>? = null
//
//    /*
//     |--------------------------------------------------------------------------
//     | Safe Current Activity Access
//     |--------------------------------------------------------------------------
//     */
//
//    private fun getActiveActivity(): Activity? {
//        val activity = activeActivity?.get()
//        return if (
//            activity == null ||
//            activity.isFinishing ||
//            activity.isDestroyed
//        ) {
//            null
//        } else {
//            activity
//        }
//    }
//
////    @Volatile
////    private var activeActivity: Activity? = null
//
//    private fun notifyListeners(callback: (SDKInitListener) -> Unit) {
//        initListeners.forEach { listener -> mainHandler.post { runCatching { callback(listener) } } }
//    }
//
//    private fun analyticsOrNull(): AnalyticsClient? = if (::analytics.isInitialized) analytics else null
//
//    private fun String.toUriOrNull(): Uri? = takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }
//
//    private fun Any?.asBoolean(defaultValue: Boolean): Boolean {
//        return when (this) {
//            is Boolean -> this
//            is Number -> toInt() != 0
//            is String -> equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)
//            else -> defaultValue
//        }
//    }
//}
