package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
import com.itwingtech.itwingsdk.ui.SdkFeatureErrorDialog
import com.itwingtech.itwingsdk.updates.InAppUpdateManager
import com.itwingtech.itwingsdk.utils.SensitiveDataSanitizer
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.wallpapers.ITWingWallpapersCallback
import com.itwingtech.itwingsdk.wallpapers.toWallpaperResponse
import com.itwingtech.itwingsdk.media.ITWingMediaCallback
import com.itwingtech.itwingsdk.media.toMediaResponse
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.itwingtech.itwingsdk.flow.ITWingFlowOnboardingActivity
import com.itwingtech.itwingsdk.flow.ITWingFlowSplashActivity
import com.itwingtech.itwingsdk.flow.ITWingFlowTermsActivity
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.itwingtech.itwingsdk.ads.FullscreenAdState
import com.itwingtech.itwingsdk.data.EncryptedConfigStore
import com.itwingtech.itwingsdk.utils.safeCallback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.itwingtech.itwingsdk.ads.ITWingRecyclerAdAdapter
import com.itwingtech.itwingsdk.ads.ITWingRecyclerAdOptions

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
    private var mobileAdsInitializationFinished = false

    @Volatile
    private var startupPreloadDone = false

    @Volatile
    private var startupMediaPreloadVersion = -1

    @Volatile
    private var bootstrapFinished = false

    @Volatile
    private var bootstrapInFlight = false

    private val splashPublicInFlight = AtomicBoolean(false)
    private val splashPublicCallbacks = mutableListOf<(String) -> Unit>()

    @Volatile
    private var lastError: String? = "not_initialized"

    @Volatile
    private var connectionState: String = "not_initialized"

    @Volatile
    private var hostAdsSuppressionReason: String? = null

    @Volatile
    private var lifecycleTrackingRegistered = false

    @Volatile
    private var autoApplyResponsiveLayout = false

    @Volatile
    private var blockAdsWhenVpnActive = false

    @Volatile
    private var remoteBlockAdsWhenVpnActive: Boolean? = null

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var foregroundActivityCount = 0

    @Volatile
    private var foregroundStartedAtMs = 0L
    private val readyCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val inlineAdsReadyCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val initListeners = CopyOnWriteArrayList<SDKInitListener>()

    val ads: AdManager = AdManager(
        configProvider = { config },
        suppressAdsReasonProvider = {
            when {
                hostAdsSuppressionReason != null -> hostAdsSuppressionReason
                isVpnAdBlockingEnabled() && NetworkState.isVpnActive(applicationContext) -> "vpn_active"
                ::subscriptions.isInitialized && subscriptions.isAdFree() -> "subscription"
                else -> null
            }
        },
    )
    lateinit var analytics: AnalyticsClient private set
    lateinit var updates: InAppUpdateManager private set
    lateinit var subscriptions: SubscriptionManager private set

    @Volatile
    private var hostAppActivityReached = false

    @Volatile
    private var notificationTargetActivityName: String? = null

    internal fun canShowInAppNotificationsNow(): Boolean {
        val activity = getActiveActivity() ?: return false
        if (isSdkInternalFlowActivity(activity)) return false
        val target = notificationTargetActivityName
        return if (target.isNullOrBlank()) {
            hostAppActivityReached
        } else {
            activity.javaClass.name == target
        }
    }

    private fun isSdkInternalFlowActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.startsWith("com.itwingtech.itwingsdk.flow.") ||
                name.contains("ITWingFlowSplashActivity") ||
                name.contains("ITWingFlowOnboardingActivity") ||
                name.contains("ITWingFlowTermsActivity")
    }

    internal fun currentConfig(): ITWingConfig = config

    internal fun foregroundActivityOrNull(): Activity? = getActiveActivity()

    internal fun sdkPrimaryColorInt(): Int = appPrimaryColorInt()

    internal fun showSdkFeatureError(
        context: Context,
        feature: String,
        reason: String,
        onRetry: (() -> Unit)? = null,
    ) {
        SdkFeatureErrorDialog.show(
            context.findActivityForSdkDialog() ?: getActiveActivity(),
            feature,
            appPrimaryColorInt(),
            SensitiveDataSanitizer.sanitize(reason),
            onRetry,
        )
    }

    @JvmStatic
    fun initialize(activity: Activity, apiKey: String, onReady: () -> Unit) {
        initialize(activity, apiKey, ITWingOptions(), readyListener(onReady))
    }

    @JvmStatic
    fun startAppFlow(
        activity: Activity,
        apiKey: String,
        mainActivity: Class<out Activity>,
    ) {
        startAppFlow(
            activity = activity,
            apiKey = apiKey,
            mainActivity = mainActivity,
            endpoint = null,
        )
    }

    @JvmStatic
    fun startAppFlow(
        activity: Activity,
        apiKey: String,
        mainActivity: Class<out Activity>,
        config: ITWingStartAppFlowConfig,
    ) {
        startAppFlow(
            activity = activity,
            apiKey = apiKey,
            mainActivity = mainActivity,
            endpoint = config.endpoint,
            autoApplyResponsiveLayout = config.autoApplyResponsiveLayout,
            analyticsEnabled = config.analyticsEnabled,
            bootstrapTimeoutMs = config.bootstrapTimeoutMs,
            strictSslPinning = config.strictSslPinning,
            blockAdsWhenVpnActive = config.blockAdsWhenVpnActive,
            finishCurrent = config.finishCurrent,
            showSplash = config.showSplash,
            showOnboarding = config.showOnboarding,
            requireTerms = config.requireTerms,
            splashStyle = config.splashStyle,
            splash_bg = config.splashBackground,
            splash_title = config.splashTitle,
            splash_sub_title = config.splashSubtitle,
            splash_lottie_anim = config.splashLottie,
            splash_bg_color = config.splashBackgroundColor,
            splash_logo = config.splashLogo,
            splashTitleTextColor = config.splashTitleTextColor,
            splashSubtitleTextColor = config.splashSubtitleTextColor,
            splashTitleTextSizeSp = config.splashTitleTextSizeSp,
            splashSubtitleTextSizeSp = config.splashSubtitleTextSizeSp,
            splashLogoWidthDp = config.splashLogoWidthDp,
            splashLogoHeightDp = config.splashLogoHeightDp,
            splashContentMarginDp = config.splashContentMarginDp,
            splashLottieWidthDp = config.splashLottieWidthDp,
            splashLottieHeightDp = config.splashLottieHeightDp,
            splashLottieBottomMarginDp = config.splashLottieBottomMarginDp,
            splash_onboardings = config.splashOnboardings,
            onboardingPages = config.onboardingPages,
            onboardingImages = config.onboardingImages,
            onboardingBannerPlacement = config.onboardingBannerPlacement,
            onboardingAdScope = config.onboardingAdScope,
            onboardingActivityAdPlacement = config.onboardingActivityAdPlacement,
            onboardingActivityAdFormat = config.onboardingActivityAdFormat,
            onboardingButtonColor = config.onboardingButtonColor,
            onboardingButtonTextColor = config.onboardingButtonTextColor,
            onboardingButtonStrokeColor = config.onboardingButtonStrokeColor,
            onboardingButtonStrokeWidthDp = config.onboardingButtonStrokeWidthDp,
            onboardingButtonTextSizeSp = config.onboardingButtonTextSizeSp,
            onboardingButtonWidthDp = config.onboardingButtonWidthDp,
            onboardingButtonHeightDp = config.onboardingButtonHeightDp,
            onboardingButtonCornerRadiusDp = config.onboardingButtonCornerRadiusDp,
            onboardingBackTintColor = config.onboardingBackTintColor,
            onboardingBackSizeDp = config.onboardingBackSizeDp,
            onboardingControlsMarginDp = config.onboardingControlsMarginDp,
            onboardingBottomBarBackgroundColor = config.onboardingBottomBarBackgroundColor,
            onboardingDotsActiveColor = config.onboardingDotsActiveColor,
            onboardingDotsInactiveColor = config.onboardingDotsInactiveColor,
            onboardingDotActiveWidthDp = config.onboardingDotActiveWidthDp,
            onboardingDotInactiveWidthDp = config.onboardingDotInactiveWidthDp,
            onboardingDotHeightDp = config.onboardingDotHeightDp,
            onboardingDotSpacingDp = config.onboardingDotSpacingDp,
            termsBannerPlacement = config.termsBannerPlacement,
            termsInterstitialPlacement = config.termsInterstitialPlacement,
            termsBackgroundColor = config.termsBackgroundColor,
            termsTextColor = config.termsTextColor,
            termsHeadingTextColor = config.termsHeadingTextColor,
            termsTextSizeSp = config.termsTextSizeSp,
            termsHeadingTextSizeSp = config.termsHeadingTextSizeSp,
            termsContentPaddingDp = config.termsContentPaddingDp,
            termsAcceptButtonText = config.termsAcceptButtonText,
            termsAcceptButtonColor = config.termsAcceptButtonColor,
            termsAcceptButtonTextColor = config.termsAcceptButtonTextColor,
            termsAcceptButtonStrokeColor = config.termsAcceptButtonStrokeColor,
            termsAcceptButtonStrokeWidthDp = config.termsAcceptButtonStrokeWidthDp,
            termsAcceptButtonTextSizeSp = config.termsAcceptButtonTextSizeSp,
            termsAcceptButtonWidthDp = config.termsAcceptButtonWidthDp,
            termsAcceptButtonHeightDp = config.termsAcceptButtonHeightDp,
            termsAcceptButtonCornerRadiusDp = config.termsAcceptButtonCornerRadiusDp,
            termsCheckboxText = config.termsCheckboxText,
            termsCheckboxTextColor = config.termsCheckboxTextColor,
            termsCheckboxTextSizeSp = config.termsCheckboxTextSizeSp,
            termsCheckboxTintColor = config.termsCheckboxTintColor,
            splashUi = config.splashUi,
            onboardingUi = config.onboardingUi,
            termsUi = config.termsUi,
            listener = config.listener,
        )
    }

    @JvmStatic
    fun startAppFlow(
        activity: Activity,
        apiKey: String,
        mainActivity: Class<out Activity>,
        endpoint: String? = null,
        autoApplyResponsiveLayout: Boolean = true,
        analyticsEnabled: Boolean = true,
        bootstrapTimeoutMs: Long = 8_000,
        strictSslPinning: Boolean = false,
        blockAdsWhenVpnActive: Boolean = false,
        finishCurrent: Boolean = true,
        showSplash: Boolean = true,
        showOnboarding: Boolean = true,
        requireTerms: Boolean = true,
        splashStyle: String? = "app_own",
        splash_bg: ImageView? = null,
        splash_title: TextView? = null,
        splash_sub_title: TextView? = null,
        splash_lottie_anim: View? = null,
        splash_bg_color: View? = null,
        splash_logo: ImageView? = null,
        splashTitleTextColor: Int? = null,
        splashSubtitleTextColor: Int? = null,
        splashTitleTextSizeSp: Float? = null,
        splashSubtitleTextSizeSp: Float? = null,
        splashLogoWidthDp: Int? = null,
        splashLogoHeightDp: Int? = null,
        splashContentMarginDp: Int? = null,
        splashLottieWidthDp: Int? = null,
        splashLottieHeightDp: Int? = null,
        splashLottieBottomMarginDp: Int? = null,
        splash_onboardings: SplashOnBoardings = SplashOnBoardings(),
        onboardingPages: List<ITWingOnboardingPage> = emptyList(),
        onboardingImages: List<Int> = emptyList(),
        onboardingBannerPlacement: String? = "banner_adaptive",
        onboardingAdScope: String? = null,
        onboardingActivityAdPlacement: String? = null,
        onboardingActivityAdFormat: String? = null,
        onboardingButtonColor: Int? = null,
        onboardingButtonTextColor: Int? = null,
        onboardingButtonStrokeColor: Int? = null,
        onboardingButtonStrokeWidthDp: Int = 0,
        onboardingButtonTextSizeSp: Float? = null,
        onboardingButtonWidthDp: Int? = null,
        onboardingButtonHeightDp: Int? = null,
        onboardingButtonCornerRadiusDp: Int? = null,
        onboardingBackTintColor: Int? = null,
        onboardingBackSizeDp: Int? = null,
        onboardingControlsMarginDp: Int? = null,
        onboardingBottomBarBackgroundColor: Int? = null,
        onboardingDotsActiveColor: Int? = null,
        onboardingDotsInactiveColor: Int? = null,
        onboardingDotActiveWidthDp: Int? = null,
        onboardingDotInactiveWidthDp: Int? = null,
        onboardingDotHeightDp: Int? = null,
        onboardingDotSpacingDp: Int? = null,
        termsBannerPlacement: String? = "banner_adaptive",
        termsInterstitialPlacement: String? = "interstitial",
        termsBackgroundColor: Int? = null,
        termsTextColor: Int? = null,
        termsHeadingTextColor: Int? = null,
        termsTextSizeSp: Float? = null,
        termsHeadingTextSizeSp: Float? = null,
        termsContentPaddingDp: Int? = null,
        termsAcceptButtonText: String? = null,
        termsAcceptButtonColor: Int? = null,
        termsAcceptButtonTextColor: Int? = null,
        termsAcceptButtonStrokeColor: Int? = null,
        termsAcceptButtonStrokeWidthDp: Int = 0,
        termsAcceptButtonTextSizeSp: Float? = null,
        termsAcceptButtonWidthDp: Int? = null,
        termsAcceptButtonHeightDp: Int? = null,
        termsAcceptButtonCornerRadiusDp: Int? = null,
        termsCheckboxText: String? = null,
        termsCheckboxTextColor: Int? = null,
        termsCheckboxTextSizeSp: Float? = null,
        termsCheckboxTintColor: Int? = null,
        splashUi: ITWingSplashUiStyle = ITWingSplashUiStyle(),
        onboardingUi: ITWingOnboardingUiStyle = ITWingOnboardingUiStyle(),
        termsUi: ITWingTermsUiStyle = ITWingTermsUiStyle(),
        listener: SDKInitListener? = null,
        listner: SDKInitListener? = null,
    ) {
        startAppFlow(
            activity = activity,
            apiKey = apiKey,
            mainActivity = mainActivity,
            splash_bg = splash_bg,
            splash_title = splash_title,
            splash_sub_title = splash_sub_title,
            splash_lottie_anim = splash_lottie_anim,
            splash_bg_color = splash_bg_color,
            splash_logo = splash_logo,
            splash_onboardings = splash_onboardings,
            endpoint = endpoint,
            sdkOptions = ITWingOptions(
                endpoint = endpoint ?: ITWingOptions().endpoint,
                bootstrapTimeoutMs = bootstrapTimeoutMs,
                strictSslPinning = strictSslPinning,
                analyticsEnabled = analyticsEnabled,
                autoApplyResponsiveLayout = autoApplyResponsiveLayout,
                blockAdsWhenVpnActive = blockAdsWhenVpnActive,
            ),
            flowOptions = ITWingAppFlowOptions(
                splashStyle = splashStyle,
                splashTitleTextColor = splashTitleTextColor,
                splashSubtitleTextColor = splashSubtitleTextColor,
                splashTitleTextSizeSp = splashTitleTextSizeSp,
                splashSubtitleTextSizeSp = splashSubtitleTextSizeSp,
                splashLogoWidthDp = splashLogoWidthDp,
                splashLogoHeightDp = splashLogoHeightDp,
                splashContentMarginDp = splashContentMarginDp,
                splashLottieWidthDp = splashLottieWidthDp,
                splashLottieHeightDp = splashLottieHeightDp,
                splashLottieBottomMarginDp = splashLottieBottomMarginDp,
                onboardingImages = onboardingImages,
                onboardingPages = onboardingPages,
                onboardingBannerPlacement = onboardingBannerPlacement,
                termsBannerPlacement = termsBannerPlacement,
                termsInterstitialPlacement = termsInterstitialPlacement,
                termsBackgroundColor = termsBackgroundColor,
                termsTextColor = termsTextColor,
                termsHeadingTextColor = termsHeadingTextColor,
                termsTextSizeSp = termsTextSizeSp,
                termsHeadingTextSizeSp = termsHeadingTextSizeSp,
                termsContentPaddingDp = termsContentPaddingDp,
                termsAcceptButtonText = termsAcceptButtonText,
                termsAcceptButtonColor = termsAcceptButtonColor,
                termsAcceptButtonTextColor = termsAcceptButtonTextColor,
                termsAcceptButtonStrokeColor = termsAcceptButtonStrokeColor,
                termsAcceptButtonStrokeWidthDp = termsAcceptButtonStrokeWidthDp,
                termsAcceptButtonTextSizeSp = termsAcceptButtonTextSizeSp,
                termsAcceptButtonWidthDp = termsAcceptButtonWidthDp,
                termsAcceptButtonHeightDp = termsAcceptButtonHeightDp,
                termsAcceptButtonCornerRadiusDp = termsAcceptButtonCornerRadiusDp,
                termsCheckboxText = termsCheckboxText,
                termsCheckboxTextColor = termsCheckboxTextColor,
                termsCheckboxTextSizeSp = termsCheckboxTextSizeSp,
                termsCheckboxTintColor = termsCheckboxTintColor,
                requireTerms = requireTerms,
                showOnboarding = showOnboarding,
                showSplash = showSplash,
                onboardingAdScope = onboardingAdScope,
                onboardingActivityAdPlacement = onboardingActivityAdPlacement,
                onboardingActivityAdFormat = onboardingActivityAdFormat,
                onboardingButtonColor = onboardingButtonColor,
                onboardingButtonTextColor = onboardingButtonTextColor,
                onboardingButtonStrokeColor = onboardingButtonStrokeColor,
                onboardingButtonStrokeWidthDp = onboardingButtonStrokeWidthDp,
                onboardingButtonTextSizeSp = onboardingButtonTextSizeSp,
                onboardingButtonWidthDp = onboardingButtonWidthDp,
                onboardingButtonHeightDp = onboardingButtonHeightDp,
                onboardingButtonCornerRadiusDp = onboardingButtonCornerRadiusDp,
                onboardingBackTintColor = onboardingBackTintColor,
                onboardingBackSizeDp = onboardingBackSizeDp,
                onboardingControlsMarginDp = onboardingControlsMarginDp,
                onboardingBottomBarBackgroundColor = onboardingBottomBarBackgroundColor,
                onboardingDotsActiveColor = onboardingDotsActiveColor,
                onboardingDotsInactiveColor = onboardingDotsInactiveColor,
                onboardingDotActiveWidthDp = onboardingDotActiveWidthDp,
                onboardingDotInactiveWidthDp = onboardingDotInactiveWidthDp,
                onboardingDotHeightDp = onboardingDotHeightDp,
                onboardingDotSpacingDp = onboardingDotSpacingDp,
                splashUi = splashUi,
                onboardingUi = onboardingUi,
                termsUi = termsUi,
                blockAdsWhenVpnActive = blockAdsWhenVpnActive,
            ),
            finishCurrent = finishCurrent,
            listener = listener,
            listner = listner,
        )
    }

    @JvmStatic
    fun startAppFlow(
        activity: Activity,
        apiKey: String,
        mainActivity: Class<out Activity>,
        splash_bg: ImageView? = null,
        splash_title: TextView? = null,
        splash_sub_title: TextView? = null,
        splash_lottie_anim: View? = null,
        splash_bg_color: View? = null,
        splash_logo: ImageView? = null,
        splash_onboardings: SplashOnBoardings = SplashOnBoardings(),
        endpoint: String? = null,
        autoApplyResponsiveLayout: Boolean? = null,
        sdkOptions: ITWingOptions = ITWingOptions(autoApplyResponsiveLayout = true),
        flowOptions: ITWingAppFlowOptions = ITWingAppFlowOptions(),
        finishCurrent: Boolean = true,
        listener: SDKInitListener? = null,
        listner: SDKInitListener? = null,
    ) {
        val externalListener = listener ?: listner
        ads.suppressAutomaticAppOpenFor(60_000L)
        notificationTargetActivityName = mainActivity.name
        hostAppActivityReached = false
        val effectiveSdkOptions = sdkOptions.copy(
            endpoint = endpoint ?: sdkOptions.endpoint,
            autoApplyResponsiveLayout = autoApplyResponsiveLayout ?: sdkOptions.autoApplyResponsiveLayout,
            blockAdsWhenVpnActive = sdkOptions.blockAdsWhenVpnActive || flowOptions.blockAdsWhenVpnActive,
        )
        val sessionFlowOptions = effectiveFlowOptions(flowOptions, splash_onboardings)
        val hostOwnsSplash = splash_bg != null ||
            splash_title != null ||
            splash_sub_title != null ||
            splash_lottie_anim != null ||
            splash_bg_color != null ||
            splash_logo != null
        val sessionId = ITWingAppFlowRegistry.put(
            ITWingAppFlowSession(
                apiKey = apiKey,
                sdkOptions = effectiveSdkOptions,
                flowOptions = sessionFlowOptions,
                mainActivityName = mainActivity.name,
                listener = externalListener,
                hostOwnsSplash = hostOwnsSplash,
            ),
        )
        val completed = AtomicBoolean(false)

        fun open(target: Intent) {
            if (!completed.compareAndSet(false, true)) return
            ads.clearAutomaticAppOpenSuppression()
            if (activity.isFinishing || activity.isDestroyed) {
                ITWingAppFlowRegistry.remove(sessionId)
                return
            }
            target.putExtra(ITWingFlowSplashActivity.EXTRA_SESSION_ID, sessionId)
            activity.startActivity(target)
            if (target.component?.className == mainActivity.name) {
                ITWingAppFlowRegistry.remove(sessionId)
            }
            if (finishCurrent) {
                activity.finish()
            }
        }

        fun openMain() = open(Intent(activity, mainActivity))

        fun openNext() {
            val prefs = activity.getSharedPreferences("itwing_app_flow", Activity.MODE_PRIVATE)
            val termsAccepted = prefs.getBoolean("terms_accepted", false)
            val pagesAvailable = hasStartupOnboardingPages(sessionFlowOptions)
            when {
                isFlowScreenEnabled("flow_onboarding", sessionFlowOptions.showOnboarding) && pagesAvailable && !termsAccepted ->
                    open(Intent(activity, ITWingFlowOnboardingActivity::class.java))

                isFlowScreenEnabled("flow_terms", sessionFlowOptions.requireTerms) && !termsAccepted ->
                    open(Intent(activity, ITWingFlowTermsActivity::class.java))

                else -> openMain()
            }
        }

        fun continueAfterUpdateAndDelay() {
            val delay = getSplashDelayMs(7000L)
            val continueAction = { mainHandler.postDelayed({ openNext() }, delay) }
            runCatching {
                if (::updates.isInitialized) updates.checkBeforeSplash(activity) { continueAction() } else continueAction()
            }.onFailure { continueAction() }
        }

        fun continueAfterUpdateWithoutDelay() {
            val continueAction = { mainHandler.post { openNext() } }
            runCatching {
                if (::updates.isInitialized) updates.checkBeforeSplash(activity) { continueAction() } else continueAction()
            }.onFailure { continueAction() }
        }

        fun continueWithSplashAdOrDelay() {
            if (!isFlowScreenEnabled("flow_splash", sessionFlowOptions.showSplash)) {
                continueAfterUpdateWithoutDelay()
                return
            }
            when (getSplashAdFormat("none").lowercase()) {
                "none", "no_ad", "disabled" -> continueAfterUpdateAndDelay()
                else -> showSplash(activity) { openNext() }
            }
        }

        fun renderVisibleSplashFromCache() {
            makeSplashFullscreen(activity)
            loadCachedConfigForStartup(activity)
            if (isFlowScreenEnabled("flow_splash", sessionFlowOptions.showSplash) && shouldApplyAdminSplashDesign(sessionFlowOptions)) {
                applyHostSplashBranding(
                    activity = activity,
                    splashBackground = splash_bg,
                    splashTitle = splash_title,
                    splashSubtitle = splash_sub_title,
                    splashLottie = splash_lottie_anim,
                    splashBackgroundColor = splash_bg_color,
                    splashLogo = splash_logo,
                    flowOptions = sessionFlowOptions,
                )
            }
        }

        fun prefetchOnly() {
            prefetchStartupMedia(activity)
        }

        runCatching { renderVisibleSplashFromCache() }
            .onFailure { externalListener?.onError(SensitiveDataSanitizer.sanitize(it.message ?: "Startup splash render failed.")) }
        runCatching {
            initialize(activity, apiKey, effectiveSdkOptions, object : SDKInitListener {
            override fun onConfigLoaded(config: ITWingConfig) {
                prefetchOnly()
                externalListener?.onConfigLoaded(config)
            }

            override fun onReady() {
                prefetchOnly()
                externalListener?.onReady()
                continueWithSplashAdOrDelay()
            }

            override fun onError(error: String) {
                externalListener?.onError(SensitiveDataSanitizer.sanitize(error))
                continueAfterUpdateAndDelay()
            }

            override fun onAdsReady() {
                externalListener?.onAdsReady()
            }

            override fun onNotificationsReady() {
                externalListener?.onNotificationsReady()
            }

            override fun onBillingReady() {
                externalListener?.onBillingReady()
            }

            override fun onAnalyticsReady() {
                externalListener?.onAnalyticsReady()
            }

            override fun onOfflineMode(reason: String) {
                externalListener?.onOfflineMode(SensitiveDataSanitizer.sanitize(reason))
            }

            override fun onRetry(reason: String) {
                externalListener?.onRetry(SensitiveDataSanitizer.sanitize(reason))
            }
            })
        }.onFailure {
            externalListener?.onError(SensitiveDataSanitizer.sanitize(it.message ?: "SDK startup failed."))
            continueAfterUpdateAndDelay()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun startAppFlow(
        activity: Activity,
        apiKey: String,
        endpoint: String,
        autoApplyResponsiveLayout: Boolean = true,
        blockAdsWhenVpnActive: Boolean = false,
        mainActivity: Class<out Activity>,
        splash_bg: ImageView? = null,
        splash_title: TextView? = null,
        splash_sub_title: TextView? = null,
        splash_lottie_anim: View? = null,
        splash_bg_color: View? = null,
        splash_logo: ImageView? = null,
        splash_onboardings: SplashOnBoardings = SplashOnBoardings(),
        flowOptions: ITWingAppFlowOptions = ITWingAppFlowOptions(),
        finishCurrent: Boolean = true,
        listener: SDKInitListener? = null,
        listner: SDKInitListener? = null,
    ) {
        startAppFlow(
            activity = activity,
            apiKey = apiKey,
            mainActivity = mainActivity,
            splash_bg = splash_bg,
            splash_title = splash_title,
            splash_sub_title = splash_sub_title,
            splash_lottie_anim = splash_lottie_anim,
            splash_bg_color = splash_bg_color,
            splash_logo = splash_logo,
            splash_onboardings = splash_onboardings,
            endpoint = endpoint,
            autoApplyResponsiveLayout = autoApplyResponsiveLayout,
            sdkOptions = ITWingOptions(
                endpoint = endpoint,
                autoApplyResponsiveLayout = autoApplyResponsiveLayout,
                blockAdsWhenVpnActive = blockAdsWhenVpnActive,
            ),
            flowOptions = flowOptions,
            finishCurrent = finishCurrent,
            listener = listener,
            listner = listner,
        )
    }

    private fun effectiveFlowOptions(
        flowOptions: ITWingAppFlowOptions,
        splashOnboardings: SplashOnBoardings,
    ): ITWingAppFlowOptions {
        if (splashOnboardings.layouts.isEmpty()) return flowOptions
        val hostPages = splashOnboardings.layouts.mapIndexed { index, layout ->
            ITWingOnboardingPage(
                title = "",
                description = "",
                layoutResId = layout,
                imageResId = flowOptions.onboardingImages.getOrElse(index) { 0 },
            )
        }
        return flowOptions.copy(
            splashOnboardings = splashOnboardings,
            onboardingPages = if (flowOptions.onboardingPages.isEmpty()) hostPages else flowOptions.onboardingPages,
        )
    }

    private fun hasStartupOnboardingPages(flowOptions: ITWingAppFlowOptions): Boolean {
        if (flowOptions.onboardingPages.isNotEmpty()) return true
        if (flowOptions.splashOnboardings.layouts.isNotEmpty()) return true
        val app = config.app
        val remotePages = app["onboarding_pages"] as? List<*>
        return !remotePages.isNullOrEmpty()
    }

    private fun isFlowScreenEnabled(key: String, fallback: Boolean): Boolean {
        val app = config.app
        val flow = (app["start_flow"] as? Map<*, *>) ?: (app["app_flow"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        return when (val value = flow[key] ?: app[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.trim().lowercase() !in setOf("0", "false", "off", "no", "disabled")
            else -> fallback
        }
    }

    private fun syncRemoteVpnAdBlocking() {
        val app = config.app
        val flow = (app["start_flow"] as? Map<*, *>) ?: (app["app_flow"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val value = when {
            flow.containsKey("block_ads_when_vpn_active") -> flow["block_ads_when_vpn_active"]
            flow.containsKey("vpn_ad_blocking_enabled") -> flow["vpn_ad_blocking_enabled"]
            app.containsKey("block_ads_when_vpn_active") -> app["block_ads_when_vpn_active"]
            app.containsKey("vpn_ad_blocking_enabled") -> app["vpn_ad_blocking_enabled"]
            else -> null
        }
        remoteBlockAdsWhenVpnActive = value?.asBoolean(false)
        if (isVpnAdBlockingEnabled() && NetworkState.isVpnActive(applicationContext)) {
            ads.clearCache()
            ads.onEntitlementActivated()
        }
    }

    private fun shouldApplyAdminSplashDesign(flowOptions: ITWingAppFlowOptions): Boolean {
        val app = config.app
        val flow = (app["start_flow"] as? Map<*, *>) ?: (app["app_flow"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val style = (
            flow["splash_design"]
                ?: flow["splash_style"]
                ?: app["splash_design"]
                ?: app["splash_style"]
                ?: flowOptions.splashStyle
                ?: "app_own"
            ).toString().trim().lowercase()
        return style !in setOf("app_own", "host", "host_app", "own", "none", "off", "disabled")
    }

    @JvmStatic
    fun initialize(
        activity: Activity,
        apiKey: String,
        options: ITWingOptions,
        onReady: () -> Unit
    ) {
        initialize(activity, apiKey, options, readyListener(onReady))
    }

    @JvmStatic
    fun initialize(activity: Activity, apiKey: String, listener: SDKInitListener) {
        initialize(activity, apiKey, ITWingOptions(), listener)
    }

    @JvmStatic
    @JvmOverloads
    fun initialize(
        activity: Activity,
        apiKey: String,
        options: ITWingOptions = ITWingOptions(),
        listener: SDKInitListener? = null
    ) {
        listener?.let { initListeners.add(it) }
        applicationContext = activity.applicationContext
        blockAdsWhenVpnActive = options.blockAdsWhenVpnActive
        remoteBlockAdsWhenVpnActive = null
        autoApplyResponsiveLayout = options.autoApplyResponsiveLayout
        if (autoApplyResponsiveLayout) {
            HostLayoutController.apply(
                activity = activity,
                primaryColor = appPrimaryColorInt(),
                applyContentInsets = true,
            )
        }
        repository = ConfigRepository(activity.applicationContext, apiKey, options)
        bootstrapFinished = false
        bootstrapInFlight = true
        mobileAdsInitializationFinished = mobileAdsInitialized
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
                "endpoint_configured" to options.endpoint.isNotBlank(),
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
            syncRemoteVpnAdBlocking()
            if (config.configVersion > 0) {
                if (autoApplyResponsiveLayout) {
                    HostLayoutController.apply(
                        activity = activity,
                        primaryColor = appPrimaryColorInt(),
                        applyContentInsets = true,
                    )
                }
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
                    "fcm_configured",
                    mapOf(
                        "enabled" to config.firebase.enabled,
                        "project_id" to config.firebase.projectId,
                        "sender_id_present" to !config.firebase.gcmSenderId.isNullOrBlank(),
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
                syncRemoteVpnAdBlocking()
                if (autoApplyResponsiveLayout) {
                    getActiveActivity()?.let { active ->
                        HostLayoutController.apply(
                            activity = active,
                            primaryColor = appPrimaryColorInt(),
                            applyContentInsets = true,
                        )
                    }
                }
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
                    "fcm_configured",
                    mapOf(
                        "enabled" to config.firebase.enabled,
                        "project_id" to config.firebase.projectId,
                        "sender_id_present" to !config.firebase.gcmSenderId.isNullOrBlank(),
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
                val message = SensitiveDataSanitizer.sanitize(it.toSdkErrorMessage())
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
                SDKTelemetry.track(
                    "sdk_bootstrap_failed",
                    mapOf("message" to message, "network_failure" to networkFailure)
                )
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
         * Do not blanket-preload every full-screen format on SDK startup.
         * That creates AdMob requests for ads the user may never see, which
         * lowers match/show rate. Startup preload is now limited to placements
         * explicitly marked by admin metadata with preload_on_start=true.
         */
        ads.preloadStartup(activity)
    }

    private fun applyHostSplashBranding(
        activity: Activity,
        splashBackground: ImageView?,
        splashTitle: TextView?,
        splashSubtitle: TextView?,
        splashLottie: View?,
        splashBackgroundColor: View?,
        splashLogo: ImageView?,
        flowOptions: ITWingAppFlowOptions,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val primary = appPrimaryColorInt()
        val splashBackgroundColorInt = config.app["splash_background_color"]
            .asNonBlankString()
            ?.let { runCatching { it.toColorInt() }.getOrNull() }
            ?: primary
        splashBackgroundColor?.setBackgroundColor(splashBackgroundColorInt)
        splashTitle?.text = flowOptions.splashTitle
            ?: config.app["splash_title"].asNonBlankString()
                    ?: getAppTitle(
                activity.applicationInfo.loadLabel(activity.packageManager).toString()
            )
        splashSubtitle?.text = flowOptions.splashSubtitle
            ?: config.app["splash_subtitle"].asNonBlankString()
                    ?: splashSubtitle?.text

        val style = (flowOptions.splashStyle
            ?: config.app["splash_style"].asNonBlankString()
            ?: config.app["splash_type"].asNonBlankString()
            ?: "default").lowercase()
        val fullBackgroundUrl = config.app["splash_background_url"].asNonBlankString()
        val centerImageUrl = config.app["splash_center_image_url"].asNonBlankString()
            ?: config.app["splash_image_url"].asNonBlankString()
        val logoUrl = getSplashLogoUrl()?.toString()

        splashBackground?.let { view ->
            when {
                flowOptions.splashBackground != 0 -> view.setImageResource(flowOptions.splashBackground)
                style in setOf(
                    "full_background",
                    "background",
                    "fullscreen_background"
                ) && !fullBackgroundUrl.isNullOrBlank() ->
                    loadCachedSplashImage(activity, view, fullBackgroundUrl)
            }
        }

        splashLogo?.let { view ->
            if (flowOptions.splashLogo != 0) {
                view.setImageResource(flowOptions.splashLogo)
            } else {
                val url = if (style in setOf("center_image", "image", "logo_only")) centerImageUrl
                    ?: logoUrl else logoUrl
                if (!url.isNullOrBlank()) {
                    loadCachedSplashImage(activity, view, url)
                }
            }
        }

        val lottieUrl = flowOptions.splashLottieUrl
            ?: config.app["loading_lottie_url"].asNonBlankString()
            ?: config.app["splash_lottie_url"].asNonBlankString()
        if (!lottieUrl.isNullOrBlank()) {
            runCatching {
                splashLottie?.javaClass
                    ?.methods
                    ?.firstOrNull { it.name == "setAnimationFromUrl" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
                    ?.invoke(splashLottie, lottieUrl)
                splashLottie?.javaClass
                    ?.methods
                    ?.firstOrNull { it.name == "playAnimation" && it.parameterTypes.isEmpty() }
                    ?.invoke(splashLottie)
            }
        }
    }

    private fun loadCachedConfigForStartup(activity: Activity) {
        val cached = runCatching {
            EncryptedConfigStore(activity.applicationContext).load()
        }.getOrNull() ?: return

        if (cached.configVersion > 0) {
            config = cached
            syncRemoteVpnAdBlocking()
        }
    }

    private fun makeSplashFullscreen(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun loadCachedSplashImage(activity: Activity, view: ImageView, url: String) {
        runCatching {
            Glide.with(activity.applicationContext)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .onlyRetrieveFromCache(true)
                .dontAnimate()
                .into(view)
        }
    }

    private fun prefetchStartupMedia(activity: Activity) {
        val version = config.configVersion
        if (version > 0 && startupMediaPreloadVersion == version) return
        startupMediaPreloadVersion = version
        val urls = linkedSetOf<String>()
        fun add(value: Any?) {
            val url = value.asNonBlankString() ?: return
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                urls.add(url)
            }
        }
        fun scan(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach(::scan)
                is List<*> -> value.forEach(::scan)
                is Array<*> -> value.forEach(::scan)
                else -> add(value)
            }
        }
        listOf(
            "icon_url",
            "launcher_icon_url",
            "splash_logo_url",
            "splash_background_url",
            "splash_center_image_url",
            "loading_lottie_url",
        ).forEach { add(config.app[it]) }
        scan(config.app)
        scan(config.remoteConfig)
        (config.app["onboarding_pages"] as? List<*>)?.forEach { page ->
            (page as? Map<*, *>)?.let {
                add(it["image_url"])
                add(it["image"])
            }
        }
        config.ads.customAds.forEach { ad ->
            add(ad.imageUrl)
            add(ad.videoUrl)
            add(ad.mediaUrl)
            add(ad.metadata["image_url"])
            add(ad.metadata["video_url"])
            add(ad.metadata["media_url"])
            (ad.metadata["brand"] as? Map<*, *>)?.let { add(it["logo_url"]) }
        }
        config.ads.placements.mapNotNull { it.customAd }.forEach { ad ->
            add(ad.imageUrl)
            add(ad.videoUrl)
            add(ad.mediaUrl)
            (ad.metadata["brand"] as? Map<*, *>)?.let { add(it["logo_url"]) }
        }
        urls.take(80).forEach { url ->
            runCatching {
                Glide.with(activity.applicationContext)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .preload()
            }
        }
    }


    private fun initializeMobileAds(activity: Activity, onInitialized: () -> Unit = {}) {
        if (mobileAdsInitialized) {
            mobileAdsInitializationFinished = true
            notifyInlineAdsReady(true)
            onInitialized()
            return
        }
        val appId = config.ads.admobAppId?.takeIf { it.isNotBlank() } ?: run {
            SDKTelemetry.track(
                "mobile_ads_initialize_skipped",
                mapOf("reason" to "missing_admob_app_id")
            )
            mobileAdsInitializationFinished = true
            notifyInlineAdsReady(true)
            onInitialized()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            runCatching {
                MobileAds.initialize(activity, InitializationConfig.Builder(appId).build()) {
                    mobileAdsInitialized = true
                    mobileAdsInitializationFinished = true
                    notifyInlineAdsReady(true)
                    onInitialized()
                }
            }.onFailure {
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "mobile_ads_initialize"))
                mobileAdsInitializationFinished = true
                notifyInlineAdsReady(false)
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
                    syncRemoteVpnAdBlocking()
                    if (autoApplyResponsiveLayout) {
                        getActiveActivity()?.let { active ->
                            HostLayoutController.apply(
                                activity = active,
                                primaryColor = appPrimaryColorInt(),
                                applyContentInsets = true,
                            )
                        }
                    }
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
    fun isReady(): Boolean {
        return config.configVersion > 0
    }

    internal fun areInlineAdsReady(): Boolean {
        return config.configVersion > 0 &&
            (
                mobileAdsInitialized ||
                    mobileAdsInitializationFinished ||
                    config.ads.admobAppId.isNullOrBlank()
                )
    }

    internal fun onInlineAdsReady(callback: (Boolean) -> Unit) {
        if (areInlineAdsReady()) {
            mainHandler.post { callback(true) }
            return
        }
        if (bootstrapFinished && config.configVersion <= 0) {
            mainHandler.post { callback(false) }
            return
        }
        inlineAdsReadyCallbacks.add(callback)
    }

    @JvmStatic
    fun lastError(): String? = SensitiveDataSanitizer.sanitize(lastError)

    @JvmStatic
    fun connectionState(): String = connectionState

    @JvmStatic
    fun diagnostics(): Map<String, Any?> = mapOf(
        "ready" to isReady(),
        "state" to connectionState,
        "last_error" to SensitiveDataSanitizer.sanitize(lastError),
        "config_version" to config.configVersion,
        "bootstrap_finished" to bootstrapFinished,
        "bootstrap_in_flight" to bootstrapInFlight,
        "auto_responsive_layout" to autoApplyResponsiveLayout,
        "vpn_active" to isVpnActive(),
        "vpn_ad_blocking_enabled" to isVpnAdBlockingEnabled(),
    )

    @JvmStatic
    @JvmOverloads
    fun applyResponsiveLayout(activity: Activity, applyContentInsets: Boolean = true) {
        runSdkCall(
            operation = "apply_responsive_layout",
            properties = mapOf("content_insets" to applyContentInsets),
        ) {
            HostLayoutController.apply(
                activity = activity,
                primaryColor = appPrimaryColorInt(),
                applyContentInsets = applyContentInsets,
            )
        }
    }

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
        return config.remoteConfig[key].asStringKeyMap()
    }

    @JvmStatic
    fun getRemoteModule(name: String): Map<String, Any?> {
        val modules = config.remoteConfig["modules"] as? Map<*, *> ?: return emptyMap()
        return modules[name].asStringKeyMap()
    }

    private fun Any?.asStringKeyMap(): Map<String, Any?> {
        val source = this as? Map<*, *> ?: return emptyMap()
        return source.mapNotNull { (key, value) ->
            (key as? String)?.let { it to value }
        }.toMap()
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
    fun getAppTitle(defaultValue: String = ""): String {
        return config.app["title"] as? String ?: config.app["name"] as? String ?: defaultValue
    }

    @JvmStatic
    fun getAppUrl(kind: String): String? {
        val legal = config.app["legal"] as? Map<*, *>
        fun legalUrl(type: String): String? =
            (legal?.get(type) as? Map<*, *>)?.get("url") as? String
        return when (kind) {
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
    fun isMaintenanceMode(): Boolean {
        return config.app["maintenance"] as? Boolean ?: false
    }

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
        val splash = config.app["splash"] as? Map<*, *>
        val seconds = listOf(
            splash?.get("seconds"),
            config.app["splash_seconds"],
            config.app["splashSeconds"],
        ).firstNotNullOfOrNull { it.toLongOrNullCompat() } ?: return defaultValue
        return seconds.coerceIn(0L, 15L) * 1000L
    }

    @JvmStatic
    fun getSplashAdFormat(defaultValue: String = "none"): String {
        val splash = config.app["splash"] as? Map<*, *>
        return listOf(
            splash?.get("ad_format"),
            splash?.get("adFormat"),
            config.app["splash_ad_format"],
            config.app["splashAdFormat"],
        ).firstNotNullOfOrNull { it.asNonBlankString() } ?: defaultValue
    }

    @JvmStatic
    fun getColor(name: String, defaultValue: String = ""): String {
        val colors = config.app["colors"] as? Map<*, *> ?: return defaultValue
        return colors[name] as? String ?: defaultValue
    }

    @JvmStatic
    fun setHostAdsSuppressed(suppressed: Boolean, reason: String = "host_suppressed") {
        hostAdsSuppressionReason = if (suppressed) reason.ifBlank { "host_suppressed" } else null
        if (suppressed) {
            ads.clearCache()
            ads.onEntitlementActivated()
        }
        SDKTelemetry.track(
            "host_ads_suppression_changed",
            mapOf("suppressed" to suppressed, "reason" to (hostAdsSuppressionReason ?: "none")),
        )
    }

    @JvmStatic
    fun areHostAdsSuppressed(): Boolean = hostAdsSuppressionReason != null

    @JvmStatic
    fun setBlockAdsWhenVpnActive(enabled: Boolean) {
        blockAdsWhenVpnActive = enabled
        remoteBlockAdsWhenVpnActive = null
        if (enabled && NetworkState.isVpnActive(applicationContext)) {
            ads.clearCache()
            ads.onEntitlementActivated()
        }
        SDKTelemetry.track(
            "vpn_ad_blocking_changed",
            mapOf("enabled" to enabled),
        )
    }

    @JvmStatic
    fun isVpnActive(): Boolean = NetworkState.isVpnActive(applicationContext)

    @JvmStatic
    fun isVpnAdBlockingEnabled(): Boolean = remoteBlockAdsWhenVpnActive ?: blockAdsWhenVpnActive

    @JvmStatic
    fun showInterstitial(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_interstitial", mapOf("placement" to placement)) {
            ads.showInterstitial(activity, placement, onComplete)
        }

    @JvmStatic
    fun showInterstitial(activity: Activity, placement: String, onComplete: Runnable) =
        showInterstitial(activity, placement) { onComplete.run() }

    @JvmStatic
    fun showRewarded(
        activity: Activity,
        placement: String,
        onReward: () -> Unit,
        onComplete: () -> Unit = {},
        onUnavailableOrSkipped: () -> Unit = {},
    ) =
        runSdkCall("show_rewarded", mapOf("placement" to placement)) {
            ads.showRewarded(activity, placement, onReward, onComplete, onUnavailableOrSkipped)
        }

    @JvmStatic
    fun showRewardedDirect(
        activity: Activity,
        placement: String,
        onReward: () -> Unit,
        onComplete: () -> Unit = {},
        onUnavailableOrSkipped: () -> Unit = {},
    ) =
        runSdkCall("show_rewarded_direct", mapOf("placement" to placement)) {
            ads.showRewardedDirect(activity, placement, onReward, onComplete, onUnavailableOrSkipped)
        }

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_rewarded", mapOf("placement" to placement)) {
            ads.showRewarded(activity, placement, onComplete)
        }

    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onComplete: Runnable) =
        showRewarded(activity, placement) { onComplete.run() }

    @JvmStatic
    fun showRewardedInterstitial(
        activity: Activity,
        placement: String,
        onReward: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) =
        runSdkCall("show_rewarded_interstitial", mapOf("placement" to placement)) {
            ads.showRewardedInterstitial(activity, placement, onReward, onComplete)
        }

    @JvmStatic
    fun showAppOpen(activity: Activity, placement: String, onComplete: () -> Unit = {}) =
        runSdkCall("show_app_open", mapOf("placement" to placement)) {
            ads.showAppOpen(activity, placement, onComplete)
        }

    @JvmStatic
    fun wrapRecyclerViewWithAds(
        activity: Activity,
        recyclerView: RecyclerView,
        contentAdapter: RecyclerView.Adapter<*>,
        placement: String,
        options: ITWingRecyclerAdOptions = ITWingRecyclerAdOptions(),
    ): RecyclerView.Adapter<RecyclerView.ViewHolder> {
        return ITWingRecyclerAdAdapter.wrap(
            activity = activity,
            recyclerView = recyclerView,
            contentAdapter = contentAdapter,
            placement = placement,
            options = options,
        )
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
    fun trackCustomAdEvent(
        customAdId: String,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        scope.launch {
            val payload = JSONObject()
            metadata.forEach { (key, value) -> payload.put(key, value) }
            analyticsOrNull()?.track(
                "custom_ad_$eventType",
                metadata + mapOf("custom_ad_id" to customAdId)
            )
            runCatching { repository?.submitCustomAdEvent(customAdId, eventType, payload) }
        }
    }

    @JvmStatic
    fun fetchWallpapers(
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        callback: ITWingWallpapersCallback,
    ) {
        fetchWallpapers(
            categoryId = categoryId,
            categorySlug = categorySlug,
            limit = limit,
            trendingLimit = trendingLimit,
            sort = sort,
            selectedWallpaperIds = emptyList(),
            callback = callback,
        )
    }

    @JvmStatic
    fun fetchWallpapers(
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        selectedWallpaperIds: List<String> = emptyList(),
        callback: ITWingWallpapersCallback,
    ) {
        scope.launch {
            val repo = repository
            if (repo == null) {
                callback.onError("ITWingSDK is not initialized.")
                return@launch
            }
            runCatching {
                repo.fetchWallpapers(
                    categoryId = categoryId,
                    categorySlug = categorySlug,
                    limit = limit,
                    trendingLimit = trendingLimit,
                    sort = sort,
                    selectedWallpaperIds = selectedWallpaperIds,
                ).toWallpaperResponse()
            }.onSuccess { response ->
                callback.onLoaded(response)
            }.onFailure { throwable ->
                callback.onError(throwable.toSdkErrorMessage())
            }
        }
    }

    @JvmStatic
    fun trackWallpaperView(wallpaperId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackWallpaperEvent(wallpaperId, "view", metadata)
    }

    @JvmStatic
    fun trackWallpaperClick(wallpaperId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackWallpaperEvent(wallpaperId, "click", metadata)
    }

    @JvmStatic
    fun trackWallpaperDownload(wallpaperId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackWallpaperEvent(wallpaperId, "download", metadata)
    }

    @JvmStatic
    fun trackWallpaperSet(wallpaperId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackWallpaperEvent(wallpaperId, "set", metadata)
    }

    @JvmStatic
    fun trackWallpaperEvent(
        wallpaperId: String,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (wallpaperId.isBlank()) return
        scope.launch {
            val payload = JSONObject()
            metadata.forEach { (key, value) -> payload.put(key, value) }
            analyticsOrNull()?.track(
                "wallpaper_$eventType",
                metadata + mapOf("wallpaper_id" to wallpaperId),
            )
            runCatching { repository?.submitWallpaperEvent(wallpaperId, eventType, payload) }
        }
    }

    @JvmStatic
    fun fetchRingtones(
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        selectedItemIds: List<String> = emptyList(),
        callback: ITWingMediaCallback,
    ) = fetchMediaLibrary(
        kind = "ringtones",
        categoryId = categoryId,
        categorySlug = categorySlug,
        limit = limit,
        trendingLimit = trendingLimit,
        sort = sort,
        selectedItemIds = selectedItemIds,
        callback = callback,
    )

    @JvmStatic
    fun fetchVideos(
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        selectedItemIds: List<String> = emptyList(),
        callback: ITWingMediaCallback,
    ) = fetchMediaLibrary(
        kind = "videos",
        categoryId = categoryId,
        categorySlug = categorySlug,
        limit = limit,
        trendingLimit = trendingLimit,
        sort = sort,
        selectedItemIds = selectedItemIds,
        callback = callback,
    )

    @JvmStatic
    fun fetchVpnServers(
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        selectedItemIds: List<String> = emptyList(),
        callback: ITWingMediaCallback,
    ) = fetchMediaLibrary(
        kind = "vpn_servers",
        categoryId = categoryId,
        categorySlug = categorySlug,
        limit = limit,
        trendingLimit = trendingLimit,
        sort = sort,
        selectedItemIds = selectedItemIds,
        callback = callback,
    )

    @JvmStatic
    fun fetchMediaLibrary(
        kind: String,
        categoryId: String? = null,
        categorySlug: String? = null,
        limit: Int = 60,
        trendingLimit: Int? = null,
        sort: String? = null,
        selectedItemIds: List<String> = emptyList(),
        callback: ITWingMediaCallback,
    ) {
        scope.launch {
            val repo = repository
            if (repo == null) {
                callback.onError("ITWingSDK is not initialized.")
                return@launch
            }
            runCatching {
                repo.fetchMediaLibrary(
                    kind = kind,
                    categoryId = categoryId,
                    categorySlug = categorySlug,
                    limit = limit,
                    trendingLimit = trendingLimit,
                    sort = sort,
                    selectedItemIds = selectedItemIds,
                ).toMediaResponse()
            }.onSuccess { response ->
                callback.onLoaded(response)
            }.onFailure { throwable ->
                callback.onError(throwable.toSdkErrorMessage())
            }
        }
    }

    @JvmStatic
    fun trackRingtonePlay(itemId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackMediaLibraryEvent("ringtones", itemId, "play", metadata)
    }

    @JvmStatic
    fun trackVideoPlay(itemId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackMediaLibraryEvent("videos", itemId, "play", metadata)
    }

    @JvmStatic
    fun trackVpnServerClick(itemId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackMediaLibraryEvent("vpn_servers", itemId, "click", metadata)
    }

    @JvmStatic
    fun trackVpnServerConnect(itemId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackMediaLibraryEvent("vpn_servers", itemId, "connect", metadata)
    }

    @JvmStatic
    fun trackVpnServerPing(itemId: String, metadata: Map<String, Any?> = emptyMap()) {
        trackMediaLibraryEvent("vpn_servers", itemId, "ping", metadata)
    }

    @JvmStatic
    fun trackMediaLibraryEvent(
        kind: String,
        itemId: String,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (kind.isBlank() || itemId.isBlank()) return
        scope.launch {
            val payload = JSONObject()
            metadata.forEach { (key, value) -> payload.put(key, value) }
            analyticsOrNull()?.track(
                "${kind.trimEnd('s')}_$eventType",
                metadata + mapOf("media_kind" to kind, "media_item_id" to itemId),
            )
            runCatching { repository?.submitMediaLibraryEvent(kind, itemId, eventType, payload) }
        }
    }

    @JvmStatic
    fun showSplash(activity: Activity, onComplete: () -> Unit = {}) {
        synchronized(splashPublicCallbacks) {
            splashPublicCallbacks.add { safeCallback(onComplete) }
        }
        if (!splashPublicInFlight.compareAndSet(false, true)) {
            SDKTelemetry.track("splash_joined_in_flight", emptyMap())
            return
        }

        val startedAt = System.currentTimeMillis()
        val completed = AtomicBoolean(false)
        val runtimeStarted = AtomicBoolean(false)
        fun completeOnce(reason: String) {
            if (!completed.compareAndSet(false, true)) return
            splashPublicInFlight.set(false)
            SDKTelemetry.track(
                "splash_completed",
                mapOf("reason" to reason, "elapsed_ms" to (System.currentTimeMillis() - startedAt))
            )
            val callbacks = synchronized(splashPublicCallbacks) {
                splashPublicCallbacks.toList().also { splashPublicCallbacks.clear() }
            }
            callbacks.forEach { callback ->
                runCatching { callback(reason) }
            }
        }

        fun scheduleHardTimeout(delayMs: Long = 15_000L) {
            mainHandler.postDelayed({
                if (completed.get()) return@postDelayed
                if (FullscreenAdState.isActive() || runtimeStarted.get()) {
                    scheduleHardTimeout(1_000L)
                } else {
                    completeOnce("hard_timeout")
                }
            }, delayMs)
        }
        scheduleHardTimeout()

        fun showRuntimeSplash() {
            if (completed.get()) return
            if (activity.isFinishing || activity.isDestroyed) {
                completeOnce("activity_unavailable")
                return
            }
            if (::updates.isInitialized) {
                updates.checkBeforeSplash(activity) {
                    if (completed.get()) return@checkBeforeSplash
                    runCatching {
                        runtimeStarted.set(true)
                        runtime.showSplash(activity) {
                            completeOnce("runtime_complete")
                        }
                    }.onFailure {
                        SDKTelemetry.recordNonFatal(it, mapOf("operation" to "show_splash_runtime"))
                        completeOnce("runtime_error")
                    }
                }
            } else {
                runCatching {
                    runtimeStarted.set(true)
                    runtime.showSplash(activity) {
                        completeOnce("runtime_complete")
                    }
                }.onFailure {
                    SDKTelemetry.recordNonFatal(it, mapOf("operation" to "show_splash_runtime"))
                    completeOnce("runtime_error")
                }
            }
        }

        fun runWhenReady() {
            if (completed.get()) return
            val waitedMs = System.currentTimeMillis() - startedAt
            if (activity.isFinishing || activity.isDestroyed) {
                completeOnce("activity_unavailable_before_ready")
                return
            }
            if ((bootstrapFinished && config.configVersion > 0) || (!bootstrapInFlight && config.configVersion > 0) || waitedMs >= 8000L) {
                showRuntimeSplash()
                return
            }
            mainHandler.postDelayed({ runWhenReady() }, 100L)
        }
        runWhenReady()
    }

    @JvmStatic
    fun showSplash(activity: Activity, onComplete: Runnable) {
        showSplash(activity) { onComplete.run() }
    }

    @JvmStatic
    fun launchSubscriptionPurchase(
        activity: Activity,
        productId: String
    ): com.android.billingclient.api.BillingResult {
        if (::subscriptions.isInitialized) {
            subscriptions.launchPurchaseWhenReady(activity, productId) { }
            return com.android.billingclient.api.BillingResult.newBuilder()
                .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.OK)
                .setDebugMessage("Purchase flow requested. Google Play Billing will open when ready.")
                .build()
        }

        return BillingResult.newBuilder()
            .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
            .setDebugMessage("Billing is not initialized yet.")
            .build()
    }

    @JvmStatic
    fun launchSubscriptionPurchase(
        activity: Activity,
        productId: String,
        onResult: ((BillingResult) -> Unit)?,
    ) {
        if (::subscriptions.isInitialized) {
            subscriptions.launchPurchaseWhenReady(activity, productId) { result ->
                onResult?.invoke(
                    result
                )
            }
        } else {
            onResult?.invoke(
                BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                    .setDebugMessage("Billing is not initialized yet.")
                    .build()
            )
        }
    }

    @JvmStatic
    fun showPurchaseDialog(
        activity: Activity,
        onResult: ((BillingResult) -> Unit)? = null
    ) {
        if (::subscriptions.isInitialized) {
            subscriptions.showPurchaseDialog(activity) { result -> onResult?.invoke(result) }
        } else {
            onResult?.invoke(
                BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
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
            defaultsProvider = { hostDialogDefaults() },
            primaryColorProvider = { appPrimaryColorInt() },
        )
    }

    @JvmSynthetic
    fun showActionDialog(activity: Activity, onPositive: () -> Unit): ITWingActionDialog {
        return showActionDialog(activity = activity, onPositive = Runnable(onPositive))
    }

    @JvmSynthetic
    fun showActionDialog(
        activity: Activity,
        onPositive: () -> Unit,
        onNegative: () -> Unit,
        onCancel: () -> Unit = {},
    ): ITWingActionDialog {
        return showActionDialog(
            activity = activity,
            onPositive = Runnable(onPositive),
            onNegative = Runnable(onNegative),
            onCancel = Runnable(onCancel),
        )
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
        return createActionDialog(activity).setReviewEnabled(true).also {
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
                    SensitiveDataSanitizer.sanitize(message),
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
                mainHandler.post(updateUi)
                return@setOnClickListener
            }

            subscribeButton.isEnabled = false
            showPurchaseDialog(activity) { result ->
                mainHandler.post {
                    updateUi()
//                    showToast(messageFor(result), long = true)
                }
            }
        }

        restoreButton?.setOnClickListener {
            restoreButton.isEnabled = false
            restorePurchases { restored ->
                mainHandler.post {
                    updateUi()
//                    showToast(if (restored) "Purchase restored" else "No active purchase found")
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

    @Deprecated("ITWingSDK no longer includes Firebase Auth. The Firebase dependency is now limited to FCM push notifications.")
    @JvmStatic
    fun firebaseAuth(): Any? = null

    private fun notifyReady(success: Boolean) {
        val callbacks = readyCallbacks.toList()
        readyCallbacks.clear()
        callbacks.forEach { callback -> mainHandler.post { callback(success) } }
        if (success) {
            notifyListeners { it.onReady() }
        }
    }

    private fun notifyInlineAdsReady(success: Boolean) {
        val callbacks = inlineAdsReadyCallbacks.toList()
        inlineAdsReadyCallbacks.clear()
        callbacks.forEach { callback -> mainHandler.post { callback(success) } }
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
                    if (autoApplyResponsiveLayout) {
                        HostLayoutController.apply(
                            activity = activity,
                            primaryColor = appPrimaryColorInt(),
                            applyContentInsets = true,
                        )
                    }
                    ads.updateForegroundActivity(activity)
                    activity.intent?.getStringExtra("itwing_notification_id")?.takeIf { it.isNotBlank() }?.let { notificationId ->
                        NotificationRuntimeManager.reportOpened(notificationId)
                        activity.intent?.removeExtra("itwing_notification_id")
                    }
                    //NotificationRuntimeManager.onForegroundActivityAvailable()
                    if (!isSdkInternalFlowActivity(activity)) {
                        val target = notificationTargetActivityName
                        if (target.isNullOrBlank() || activity.javaClass.name == target) {
                            hostAppActivityReached = true
                            NotificationRuntimeManager.onForegroundActivityAvailable()
                        }
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
                    if (autoApplyResponsiveLayout) {
                        HostLayoutController.apply(
                            activity = activity,
                            primaryColor = appPrimaryColorInt(),
                            applyContentInsets = true,
                        )
                    }
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

    private fun analyticsOrNull(): AnalyticsClient? =
        if (::analytics.isInitialized) analytics else null

    private fun runSdkCall(
        operation: String,
        properties: Map<String, Any?> = emptyMap(),
        block: () -> Unit
    ) {
        SDKTelemetry.track("sdk_call_requested", mapOf("operation" to operation) + properties)
        runCatching { block() }.onFailure {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to operation) + properties)
            SDKTelemetry.track(
                "sdk_call_failed",
                mapOf("operation" to operation, "message" to (it.message ?: "unknown")) + properties
            )
        }
    }

    private fun readyListener(onReady: () -> Unit): SDKInitListener {
        val delivered = AtomicBoolean(false)
        fun deliverOnce() {
            if (delivered.compareAndSet(false, true)) {
                onReady()
            }
        }
        return object : SDKInitListener {
            override fun onReady() = deliverOnce()
            override fun onError(error: String) = deliverOnce()
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

    private fun hostDialogDefaults(): Map<*, *> {
        val candidates = listOf(
            config.app["host_dialog"],
            config.app["hostDialog"],
            config.remoteConfig["host_dialog"],
            config.remoteConfig["hostDialog"],
            config.features["host_dialog"],
            config.features["hostDialog"],
        )
        candidates.firstNotNullOfOrNull { it as? Map<*, *> }?.let { return it }

        val flat = linkedMapOf<String, Any?>(
            "enabled" to (
                    config.app["host_dialog_enabled"]
                        ?: config.remoteConfig["host_dialog_enabled"]
                        ?: config.features["host_dialog_enabled"]
                    ),
            "title" to (
                    config.app["host_dialog_title"]
                        ?: config.remoteConfig["host_dialog_title"]
                        ?: config.features["host_dialog_title"]
                    ),
            "description" to (
                    config.app["host_dialog_description"]
                        ?: config.remoteConfig["host_dialog_description"]
                        ?: config.features["host_dialog_description"]
                    ),
            "positive_text" to (
                    config.app["host_dialog_positive_text"]
                        ?: config.remoteConfig["host_dialog_positive_text"]
                        ?: config.features["host_dialog_positive_text"]
                    ),
            "negative_text" to (
                    config.app["host_dialog_negative_text"]
                        ?: config.remoteConfig["host_dialog_negative_text"]
                        ?: config.features["host_dialog_negative_text"]
                    ),
            "native_placement" to (
                    config.app["host_dialog_native_placement"]
                        ?: config.remoteConfig["host_dialog_native_placement"]
                        ?: config.features["host_dialog_native_placement"]
                    ),
            "native_type" to (
                    config.app["host_dialog_native_type"]
                        ?: config.remoteConfig["host_dialog_native_type"]
                        ?: config.features["host_dialog_native_type"]
                    ),
            "review_enabled" to (
                    config.app["host_dialog_review_enabled"]
                        ?: config.remoteConfig["host_dialog_review_enabled"]
                        ?: config.features["host_dialog_review_enabled"]
                    ),
            "feedback_email" to (
                    config.app["host_dialog_feedback_email"]
                        ?: config.remoteConfig["host_dialog_feedback_email"]
                        ?: config.features["host_dialog_feedback_email"]
                    ),
        )
        return flat.filterValues { it != null }
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
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith(
                "https://",
                ignoreCase = true
            )
        ) {
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

    private fun String.toUriOrNull(): Uri? =
        takeIf { it.isNotBlank() }?.let { runCatching { it.toUri() }.getOrNull() }

    private fun Any?.asNonBlankString(): String? {
        return when (this) {
            null -> null
            is String -> trim()
            else -> toString().trim()
        }?.takeUnless {
            it.isBlank() ||
                    it.equals("null", ignoreCase = true) ||
                    it.equals("undefined", ignoreCase = true)
        }
    }

    private fun Any?.toLongOrNullCompat(): Long? {
        return when (this) {
            is Number -> toLong()
            is String -> trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun Any?.asBoolean(defaultValue: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> toInt() != 0
            is String -> equals("true", ignoreCase = true) || this == "1" || equals(
                "yes",
                ignoreCase = true
            )

            else -> defaultValue
        }
    }

    private fun Context.findActivityForSdkDialog(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity && !current.isFinishing && !current.isDestroyed) {
                return current
            }
            current = current.baseContext
        }
        return null
    }
}
