package com.itwingtech.itwingsdk.flow

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.ads.InlineAdSafetyGate
import com.itwingtech.itwingsdk.ads.ITWingBannerView
import com.itwingtech.itwingsdk.ads.ITWingNativeAdView
import com.itwingtech.itwingsdk.core.ITWingAppFlowOptions
import com.itwingtech.itwingsdk.core.ITWingAppFlowRegistry
import com.itwingtech.itwingsdk.core.ITWingAppFlowSession
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingDimen
import com.itwingtech.itwingsdk.core.ITWingOnboardingPage
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.SDKInitListener
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.edit

class ITWingFlowSplashActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var session: ITWingAppFlowSession? = null
    private val navigated = AtomicBoolean(false)
    private var renderedConfigVersion: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        session = ITWingAppFlowRegistry.get(sessionId)
        if (session == null) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterFullscreen()
        setContentView(R.layout.activity_itwing_flow_splash)
        renderSplash()

        val current = session ?: return
        ITWingSDK.initialize(this, current.apiKey, current.sdkOptions, object : SDKInitListener {
            override fun onReady() {
                if (renderedConfigVersion == null) {
                    renderSplash()
                }
                current.listener?.onReady()
                if (!flowEnabled("flow_splash", current.flowOptions.showSplash)) {
                    openNextScreen()
                } else if (shouldShowStartupScreens() || splashAdFormat() in setOf("none", "no_ad", "disabled")) {
                    continueAfterUpdateCheckWithDelay()
                } else if (current.hostOwnsSplash) {
                    openNextScreen()
                } else {
                    ITWingSDK.showSplash(this@ITWingFlowSplashActivity) {
                        openNextScreen()
                    }
                }
            }

            override fun onConfigLoaded(config: ITWingConfig) {
                if (renderedConfigVersion == null && (config.configVersion > 0 || config.app.isNotEmpty())) {
                    renderedConfigVersion = config.configVersion
                    renderSplash()
                }
                current.listener?.onConfigLoaded(config)
            }

            override fun onError(error: String) {
                current.listener?.onError(error)
                openNextScreen()
            }

            override fun onAdsReady() {
                current.listener?.onAdsReady()
            }

            override fun onNotificationsReady() {
                current.listener?.onNotificationsReady()
            }

            override fun onBillingReady() {
                current.listener?.onBillingReady()
            }

            override fun onAnalyticsReady() {
                current.listener?.onAnalyticsReady()
            }

            override fun onOfflineMode(reason: String) {
                current.listener?.onOfflineMode(reason)
            }

            override fun onRetry(reason: String) {
                current.listener?.onRetry(reason)
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun renderSplash() {
        val current = session ?: return
        val primary = splashBackgroundColor()
        window.statusBarColor = primary
        window.navigationBarColor = primary
        val root = findViewById<View>(R.id.itwing_flow_root)
        val background = findViewById<ImageView>(R.id.itwing_flow_splash_background)
        val content = findViewById<LinearLayout>(R.id.itwing_flow_splash_content)
        val logo = findViewById<ImageView>(R.id.itwing_flow_splash_logo)
        val title = findViewById<TextView>(R.id.itwing_flow_splash_title)
        val subtitle = findViewById<TextView>(R.id.itwing_flow_splash_subtitle)
        val lottie = findViewById<LottieAnimationView>(R.id.itwing_flow_splash_lottie)
        val style = splashStyle(current.flowOptions)
        val isFullBackground = style in setOf("full_background", "background", "fullscreen_background")
        root.setBackgroundColor(primary)

        title.text =
            current.flowOptions.splashTitle
                ?: appString("splash_title")
                ?: ITWingSDK.getAppTitle(applicationInfo.loadLabel(packageManager).toString())
        subtitle.text =
            current.flowOptions.splashSubtitle ?: appString("splash_subtitle")
            ?: getString(R.string.itwing_flow_loading)
        (current.flowOptions.splashUi.title.color ?: current.flowOptions.splashTitleTextColor)?.let(title::setTextColor)
        (current.flowOptions.splashUi.subtitle.color ?: current.flowOptions.splashSubtitleTextColor)?.let(subtitle::setTextColor)
        title.applySdkTextSize(current.flowOptions.splashUi.title.textSize, current.flowOptions.splashUi.title.textSizeSp ?: current.flowOptions.splashTitleTextSizeSp)
        subtitle.applySdkTextSize(current.flowOptions.splashUi.subtitle.textSize, current.flowOptions.splashUi.subtitle.textSizeSp ?: current.flowOptions.splashSubtitleTextSizeSp)
        (dimensionPx(current.flowOptions.splashUi.contentMargin) ?: current.flowOptions.splashContentMarginDp?.let(::dp))?.let { value ->
            (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.setMargins(value, value, value, value)
                content.layoutParams = params
            }
        }

        background.visibility = View.GONE
        var backgroundConfigured = false
        if (isFullBackground) {
            background.setImageResource(R.drawable.itwing_flow_splash_bg_ref)
            background.visibility = View.VISIBLE
            current.flowOptions.splashBackground.takeIf { it != 0 }?.let {
                background.setImageResource(it)
                backgroundConfigured = true
            }
            splashBackgroundUrl()?.let {
                Glide.with(background)
                    .load(it)
                    .placeholder(R.drawable.itwing_flow_splash_bg_ref)
                    .error(R.drawable.itwing_flow_splash_bg_ref)
                    .dontAnimate()
                    .into(background)
                backgroundConfigured = true
            }
        }

        val shouldUseCenterImage = style in setOf("center_image", "image", "logo_only")
        val configuredSplashLogoUrl = ITWingSDK.getSplashLogoUrl()
        val configuredCenterImageUrl = splashCenterImageUrl()
        logo.setImageDrawable(applicationInfo.loadIcon(packageManager))
        when {
            current.flowOptions.splashLogo != 0 -> logo.setImageResource(current.flowOptions.splashLogo)
            shouldUseCenterImage && configuredCenterImageUrl != null -> Glide.with(logo)
                .load(configuredCenterImageUrl)
                .dontAnimate()
                .into(logo)
            configuredSplashLogoUrl != null -> Glide.with(logo)
                .load(configuredSplashLogoUrl)
                .dontAnimate()
                .into(logo)
        }

        val lottieUrl = current.flowOptions.splashLottieUrl
            ?: appString("loading_lottie_url")
            ?: appString("splash_lottie_url")
        if (!lottieUrl.isNullOrBlank()) {
            lottie.setAnimationFromUrl(lottieUrl)
            lottie.playAnimation()
        }
        lottie.applyViewSize(
            widthPx = dimensionPx(current.flowOptions.splashUi.lottieWidth),
            heightPx = dimensionPx(current.flowOptions.splashUi.lottieHeight),
            widthDp = current.flowOptions.splashUi.lottieWidthDp ?: current.flowOptions.splashLottieWidthDp,
            heightDp = current.flowOptions.splashUi.lottieHeightDp ?: current.flowOptions.splashLottieHeightDp,
        )
        (dimensionPx(current.flowOptions.splashUi.lottieBottomMargin)
            ?: (current.flowOptions.splashUi.lottieBottomMarginDp ?: current.flowOptions.splashLottieBottomMarginDp)?.let(::dp))?.let { margin ->
            (lottie.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = margin
                lottie.layoutParams = params
            }
        }

        when (style) {
            "center_image", "image", "logo_only" -> {
                background.visibility = View.GONE
                content.visibility = View.VISIBLE
                title.visibility = View.GONE
                subtitle.visibility = View.GONE
                lottie.visibility = View.VISIBLE
                setSplashLogoSize(
                    logo,
                    dimensionPx(current.flowOptions.splashUi.logoWidth)
                        ?: (current.flowOptions.splashUi.logoWidthDp ?: current.flowOptions.splashLogoWidthDp)?.let(::dp)
                        ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    dimensionPx(current.flowOptions.splashUi.logoHeight)
                        ?: (current.flowOptions.splashUi.logoHeightDp ?: current.flowOptions.splashLogoHeightDp)?.let(::dp)
                        ?: dp(240),
                )
                (content.layoutParams as? RelativeLayout.LayoutParams)?.let {
                    val margin = dimensionPx(current.flowOptions.splashUi.contentMargin)
                        ?: dp(current.flowOptions.splashUi.contentMarginDp ?: current.flowOptions.splashContentMarginDp ?: 20)
                    it.setMargins(margin, margin, margin, margin)
                    content.layoutParams = it
                }
            }

            "full_background", "background", "fullscreen_background" -> {
                if (!backgroundConfigured) {
                    background.setImageResource(R.drawable.itwing_flow_splash_bg_ref)
                }
                background.visibility = View.VISIBLE
                content.visibility = View.GONE
                lottie.visibility = View.VISIBLE
            }

            else -> {
                content.visibility = View.VISIBLE
                title.visibility = View.VISIBLE
                subtitle.visibility = View.VISIBLE
                lottie.visibility = View.VISIBLE
                setSplashLogoSize(
                    logo,
                    dimensionPx(current.flowOptions.splashUi.logoWidth)
                        ?: (current.flowOptions.splashUi.logoWidthDp ?: current.flowOptions.splashLogoWidthDp)?.let(::dp)
                        ?: dp(40),
                    dimensionPx(current.flowOptions.splashUi.logoHeight)
                        ?: (current.flowOptions.splashUi.logoHeightDp ?: current.flowOptions.splashLogoHeightDp)?.let(::dp)
                        ?: dp(40),
                )
            }
        }
    }

    private fun continueAfterUpdateCheckWithDelay() {
        runCatching {
            ITWingSDK.updates.checkBeforeSplash(this) {
                mainHandler.postDelayed({ openNextScreen() }, splashDelayMillis())
            }
        }.getOrElse {
            mainHandler.postDelayed({ openNextScreen() }, splashDelayMillis())
        }
    }

    private fun shouldShowStartupScreens(): Boolean {
        val current = session ?: return false
        val prefs = flowPrefs()
        val pages = resolvePages(current.flowOptions)
        val shouldShowOnboarding = flowEnabled("flow_onboarding", current.flowOptions.showOnboarding) &&
            pages.isNotEmpty() &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        val shouldShowTerms = flowEnabled("flow_terms", current.flowOptions.requireTerms) &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        return shouldShowOnboarding || shouldShowTerms
    }

    private fun setSplashLogoSize(view: ImageView, width: Int, height: Int) {
        view.layoutParams = view.layoutParams.apply {
            this.width = width
            this.height = height
        }
    }

    private fun openNextScreen() {
        if (!navigated.compareAndSet(false, true)) return
        val current = session ?: return
        val prefs = flowPrefs()
        val pages = resolvePages(current.flowOptions)
        val shouldShowOnboarding = flowEnabled("flow_onboarding", current.flowOptions.showOnboarding) &&
            pages.isNotEmpty() &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        val shouldShowTerms = flowEnabled("flow_terms", current.flowOptions.requireTerms) &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)

        val opensMain = !shouldShowOnboarding && !shouldShowTerms
        val target = when {
            shouldShowOnboarding -> {
                InlineAdSafetyGate.bypassNextActivity(ITWingFlowOnboardingActivity::class.java.name)
                Intent(this, ITWingFlowOnboardingActivity::class.java)
            }
            shouldShowTerms -> Intent(this, ITWingFlowTermsActivity::class.java)
            else -> mainIntent(current)
        }
        target.putExtra(EXTRA_SESSION_ID, sessionId)
        startActivity(target)
        if (opensMain) {
            ITWingAppFlowRegistry.remove(sessionId)
        }
        finish()
    }

    private fun mainIntent(session: ITWingAppFlowSession): Intent {
        val clazz = Class.forName(session.mainActivityName)
        return Intent(this, clazz)
    }

    companion object {
        const val EXTRA_SESSION_ID = "itwing_flow_session_id"
    }
}

class ITWingFlowOnboardingActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var session: ITWingAppFlowSession? = null
    private lateinit var pager: ViewPager2
    private lateinit var nextButton: Button
    private lateinit var backButton: ImageView
    private lateinit var dots: LinearLayout
    private lateinit var bottomAdContainer: FrameLayout
    private var pages: List<ITWingOnboardingPage> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(ITWingFlowSplashActivity.EXTRA_SESSION_ID)
        session = ITWingAppFlowRegistry.get(sessionId)
        if (session == null) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterFullscreen()
        setContentView(R.layout.activity_itwing_flow_onboarding)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val current = session ?: return
        pages = resolvePages(current.flowOptions)
        if (pages.isEmpty()) {
            finishOnboarding()
            return
        }

        pager = findViewById(R.id.itwing_flow_view_pager)
        nextButton = findViewById(R.id.itwing_flow_next)
        backButton = findViewById(R.id.itwing_flow_back)
        dots = findViewById(R.id.itwing_flow_dots)
        bottomAdContainer = findViewById(R.id.itwing_flow_onboarding_ad_container)
        applyOnboardingInsets(
            root = findViewById(R.id.itwing_flow_root),
            back = backButton,
            bottomBar = findViewById(R.id.itwing_flow_bottom_bar),
            bottomAd = bottomAdContainer,
            margin = current.flowOptions.onboardingUi.controlsMargin,
            marginDp = current.flowOptions.onboardingControlsMarginDp,
        )

        styleOnboardingControls(current.flowOptions)

        val pageAdsEnabled = onboardingAdScope(current.flowOptions) == "page"
        pager.adapter = OnboardingAdapter(pages, pageAdsEnabled)
        dots.post { renderDots(0) }
        backButton.visibility = View.INVISIBLE
        updateBottomAd(0)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderDots(position)
                nextButton.text = getString(if (position == pages.lastIndex) R.string.itwing_flow_finish else R.string.itwing_flow_next)
                backButton.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
                updateBottomAd(position)
            }
        })
        backButton.setOnClickListener {
            val position = pager.currentItem
            if (position > 0) pager.currentItem = position - 1
        }
        nextButton.setOnClickListener {
            val position = pager.currentItem
            if (position < pages.lastIndex) {
                pager.currentItem = position + 1
            } else {
                finishOnboarding()
            }
        }
        if (onboardingAdScope(current.flowOptions) == "off") {
            bottomAdContainer.visibility = View.GONE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun updateBottomAd(position: Int) {
        val current = session ?: return
        if (onboardingAdScope(current.flowOptions) != "activity") {
            bottomAdContainer.visibility = View.GONE
            return
        }
        val placement = onboardingActivityAdPlacement(current.flowOptions, pages.getOrNull(position))
        if (placement.isNullOrBlank()) {
            bottomAdContainer.visibility = View.GONE
            return
        }
        bottomAdContainer.visibility = View.VISIBLE
        if (bottomAdContainer.childCount > 0 && bottomAdContainer.getTag(R.id.itwing_flow_onboarding_ad_container) == placement) return
        bottomAdContainer.removeAllViews()
        bottomAdContainer.setTag(R.id.itwing_flow_onboarding_ad_container, placement)
        if (onboardingAdFormat(current.flowOptions, placement) == "banner") {
            val banner = ITWingBannerView(this)
            banner.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            banner.placementName = placement
            bottomAdContainer.addView(banner)
            banner.loadBanner()
        } else {
            val nativeAd = ITWingNativeAdView(this)
            nativeAd.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            nativeAd.placementName = placement
            bottomAdContainer.addView(nativeAd)
            nativeAd.loadAd()
        }
    }

    private fun renderDots(position: Int) {
        dots.removeAllViews()
        val current = session
        val primary = current?.flowOptions?.onboardingUi?.dots?.activeColor
            ?: current?.flowOptions?.onboardingDotsActiveColor
            ?: primaryColor()
        val inactive = current?.flowOptions?.onboardingUi?.dots?.inactiveColor
            ?: current?.flowOptions?.onboardingDotsInactiveColor
            ?: Color.rgb(220, 227, 234)
        for (index in pages.indices) {
            val dot = View(this)
            val width = if (index == position) {
                dimensionPx(current?.flowOptions?.onboardingUi?.dots?.activeWidth)
                    ?: current?.flowOptions?.onboardingUi?.dots?.activeWidthDp?.let(::dp)
                    ?: current?.flowOptions?.onboardingDotActiveWidthDp
                        ?.let(::dp)
                    ?: dp(30)
            } else {
                dimensionPx(current?.flowOptions?.onboardingUi?.dots?.inactiveWidth)
                    ?: current?.flowOptions?.onboardingUi?.dots?.inactiveWidthDp?.let(::dp)
                    ?: current?.flowOptions?.onboardingDotInactiveWidthDp
                        ?.let(::dp)
                    ?: dp(10)
            }
            val height = dimensionPx(current?.flowOptions?.onboardingUi?.dots?.height)
                ?: current?.flowOptions?.onboardingUi?.dots?.heightDp?.let(::dp)
                ?: current?.flowOptions?.onboardingDotHeightDp?.let(::dp)
                ?: dp(10)
            val spacing = dimensionPx(current?.flowOptions?.onboardingUi?.dots?.spacing)
                ?: current?.flowOptions?.onboardingUi?.dots?.spacingDp?.let(::dp)
                ?: current?.flowOptions?.onboardingDotSpacingDp?.let(::dp)
                ?: dp(3)
            val params = LinearLayout.LayoutParams(width, height)
            params.setMargins(spacing, 0, spacing, 0)
            dot.layoutParams = params
            dot.setBackgroundResource(if (index == position) R.drawable.itwing_flow_dot_active else R.drawable.itwing_flow_dot_inactive)
            dot.backgroundTintList = ColorStateList.valueOf(if (index == position) primary else inactive)
            dots.addView(dot)
        }
    }

    private fun styleOnboardingControls(options: ITWingAppFlowOptions) {
        options.onboardingUi.nextButton.backgroundDrawableRes?.let {
            nextButton.setBackgroundResource(it)
            nextButton.backgroundTintList = null
        }
        val buttonColor = options.onboardingButtonColor ?: primaryColor()
        val buttonTextColor = options.onboardingButtonTextColor ?: onPrimary(buttonColor)
        if (options.onboardingUi.nextButton.backgroundDrawableRes == null) {
            val background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(options.onboardingButtonCornerRadiusDp ?: 8).toFloat()
                setColor(buttonColor)
                if (options.onboardingButtonStrokeWidthDp > 0) {
                    setStroke(
                        dp(options.onboardingButtonStrokeWidthDp),
                        options.onboardingButtonStrokeColor ?: buttonColor,
                    )
                }
            }
            nextButton.background = background
            nextButton.backgroundTintList = null
        }
        nextButton.setTextColor(options.onboardingUi.nextButton.textColor ?: buttonTextColor)
        nextButton.applyViewSize(
            widthPx = null,
            heightPx = null,
            widthDp = options.onboardingButtonWidthDp,
            heightDp = options.onboardingButtonHeightDp,
        )
        nextButton.applySdkTextSize(options.onboardingUi.nextButton.textSize, options.onboardingUi.nextButton.textSizeSp ?: options.onboardingButtonTextSizeSp)
        options.onboardingUi.backButton.drawableRes?.let(backButton::setImageResource)
        backButton.imageTintList = (options.onboardingUi.backButton.tintColor ?: options.onboardingBackTintColor)?.let(ColorStateList::valueOf)
        val backSizePx = dimensionPx(options.onboardingUi.backButton.size)
        val backSizeDp = options.onboardingUi.backButton.sizeDp ?: options.onboardingBackSizeDp
        backButton.applyViewSize(widthPx = backSizePx, heightPx = backSizePx, widthDp = backSizeDp, heightDp = backSizeDp)
        findViewById<View>(R.id.itwing_flow_bottom_bar)?.background =
            (options.onboardingUi.bottomBarBackgroundColor ?: options.onboardingBottomBarBackgroundColor)?.let { color ->
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color)
            }
        }
    }

    private fun finishOnboarding() {
        val current = session ?: return finish()
        val shouldShowTerms = flowEnabled("flow_terms", current.flowOptions.requireTerms) &&
            !flowPrefs().getBoolean(KEY_TERMS_ACCEPTED, false)
        val opensMain = !shouldShowTerms
        val target = if (shouldShowTerms) {
            Intent(this, ITWingFlowTermsActivity::class.java)
        } else {
            mainIntent(current)
        }.putExtra(ITWingFlowSplashActivity.EXTRA_SESSION_ID, sessionId)
        startActivity(target)
        if (opensMain) {
            flowPrefs().edit {
                putBoolean(KEY_ONBOARDING_DONE, true)
                putBoolean(KEY_TERMS_ACCEPTED, true)
            }
            ITWingAppFlowRegistry.remove(sessionId)
        }
        finish()
    }

    private fun mainIntent(session: ITWingAppFlowSession): Intent {
        val clazz = Class.forName(session.mainActivityName)
        return Intent(this, clazz)
    }
}

class ITWingFlowTermsActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var session: ITWingAppFlowSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(ITWingFlowSplashActivity.EXTRA_SESSION_ID)
        session = ITWingAppFlowRegistry.get(sessionId)
        if (session == null) {
            finish()
            return
        }

        enterFullscreen()
        setContentView(R.layout.activity_itwing_flow_terms)

        val current = session ?: return
        val primary = primaryColor()
        val options = current.flowOptions
        val root = findViewById<View>(R.id.itwing_flow_root)
        val content = findViewById<View>(R.id.itwing_flow_terms_content)
        val controls = findViewById<View>(R.id.itwing_flow_terms_controls)
        val check = findViewById<CheckBox>(R.id.itwing_flow_terms_check)
        val accept = findViewById<Button>(R.id.itwing_flow_terms_accept)
        val backgroundColor = options.termsBackgroundColor ?: Color.WHITE
        root.setBackgroundColor(backgroundColor)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        applyTermsInsets(root, content, controls, options.termsUi.contentPadding, options.termsUi.contentPaddingDp ?: options.termsContentPaddingDp)
        styleTermsControls(check, accept, options, primary)

        val web = findViewById<WebView>(R.id.itwing_flow_terms_web)
        web.settings.javaScriptEnabled = false
        web.setBackgroundColor(backgroundColor)
        web.loadDataWithBaseURL(
            null,
            legalHtml(options),
            "text/html",
            "UTF-8",
            null,
        )

        accept.setOnClickListener {
            if (!check.isChecked) {
                Toast.makeText(this, R.string.itwing_flow_accept_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            flowPrefs().edit {
                putBoolean(KEY_ONBOARDING_DONE, true)
                putBoolean(KEY_TERMS_ACCEPTED, true)
            }
            val placement = flowPlacement("terms_interstitial_placement", current.flowOptions.termsInterstitialPlacement)
            if (placement.isNullOrBlank()) {
                openMain()
            } else {
                ITWingSDK.showInterstitial(this, placement) {
                    openMain()
                }
            }
        }
        attachBottomAd(
            placement = flowPlacement("terms_banner_placement", current.flowOptions.termsBannerPlacement),
            preferredFormat = termsAdFormat(current.flowOptions),
        )
    }

    private fun openMain() {
        val current = session ?: return finish()
        startActivity(mainIntent(current))
        ITWingAppFlowRegistry.remove(sessionId)
        finish()
    }

    private fun mainIntent(session: ITWingAppFlowSession): Intent {
        val clazz = Class.forName(session.mainActivityName)
        return Intent(this, clazz)
    }

    private fun styleTermsControls(check: CheckBox, accept: Button, options: ITWingAppFlowOptions, primary: Int) {
        val buttonColor = options.termsAcceptButtonColor ?: primary
        val buttonTextColor = options.termsUi.acceptButton.textColor ?: options.termsAcceptButtonTextColor ?: onPrimary(buttonColor)
        options.termsUi.acceptButton.backgroundDrawableRes?.let {
            accept.setBackgroundResource(it)
            accept.backgroundTintList = null
        }
        if (options.termsUi.acceptButton.backgroundDrawableRes == null) {
            val background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(options.termsAcceptButtonCornerRadiusDp ?: 20).toFloat()
                setColor(buttonColor)
                if (options.termsAcceptButtonStrokeWidthDp > 0) {
                    setStroke(
                        dp(options.termsAcceptButtonStrokeWidthDp),
                        options.termsAcceptButtonStrokeColor ?: buttonColor,
                    )
                }
            }
            accept.background = background
            accept.backgroundTintList = null
        }
        accept.text = options.termsAcceptButtonText ?: getString(R.string.itwing_flow_accept)
        accept.setTextColor(buttonTextColor)
        accept.applySdkTextSize(options.termsUi.acceptButton.textSize, options.termsUi.acceptButton.textSizeSp ?: options.termsAcceptButtonTextSizeSp)
        accept.applyViewSize(
            widthDp = options.termsAcceptButtonWidthDp,
            heightDp = options.termsAcceptButtonHeightDp,
        )

        check.text = options.termsCheckboxText ?: getString(R.string.itwing_flow_accept_terms)
        check.setTextColor(options.termsUi.checkbox.color ?: options.termsCheckboxTextColor ?: Color.rgb(51, 51, 51))
        check.applySdkTextSize(options.termsUi.checkbox.textSize, options.termsUi.checkbox.textSizeSp ?: options.termsCheckboxTextSizeSp)
        check.buttonTintList = ColorStateList.valueOf(options.termsUi.checkboxTintColor ?: options.termsCheckboxTintColor ?: primary)
    }

    private fun legalHtml(options: ITWingAppFlowOptions): String {
        ITWingSDK.getAppUrl("terms")?.takeIf(String::isNotBlank)?.let {
            return """<html><body style="margin:0"><iframe src="$it" style="border:0;width:100%;height:100%"></iframe></body></html>"""
        }
        val terms = ITWingSDK.getLegalContent("terms")
            ?: "Terms of Use\n\nPlease review and accept the terms to continue."
        val privacy = ITWingSDK.getLegalContent("privacy")
        val disclaimer = ITWingSDK.getLegalContent("disclaimer")
        return buildString {
            append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
            val background = (options.termsBackgroundColor ?: Color.WHITE).toCssColor()
            val text = (options.termsUi.body.color ?: options.termsTextColor ?: Color.rgb(17, 24, 39)).toCssColor()
            val heading = (options.termsUi.heading.color ?: options.termsHeadingTextColor ?: options.termsTextColor ?: Color.rgb(17, 24, 39)).toCssColor()
            val textSize = options.termsUi.body.textSize?.cssTextSize(this@ITWingFlowTermsActivity)
                ?: options.termsUi.body.textSizeSp
                ?: options.termsTextSizeSp
                ?: 14f
            val headingSize = options.termsUi.heading.textSize?.cssTextSize(this@ITWingFlowTermsActivity)
                ?: options.termsUi.heading.textSizeSp
                ?: options.termsHeadingTextSizeSp
                ?: 20f
            append("<style>")
            append("body{font-family:sans-serif;background:$background;color:$text;line-height:1.55;padding:4px 2px;font-size:${textSize}px}")
            append("h1,h2{color:$heading;font-size:${headingSize}px;margin:12px 0 8px}")
            append("pre{white-space:pre-wrap;font-family:sans-serif;margin:0 0 14px}")
            append("</style>")
            append("</head><body>")
            append("<h1>Terms of Use</h1><pre>").append(terms.escapeHtml()).append("</pre>")
            if (!privacy.isNullOrBlank()) append("<h2>Privacy Policy</h2><pre>").append(privacy.escapeHtml()).append("</pre>")
            if (!disclaimer.isNullOrBlank()) append("<h2>Disclaimer</h2><pre>").append(disclaimer.escapeHtml()).append("</pre>")
            append("</body></html>")
        }
    }
}

private class OnboardingAdapter(
    private val pages: List<ITWingOnboardingPage>,
    private val pageAdsEnabled: Boolean,
) : RecyclerView.Adapter<OnboardingAdapter.Holder>() {
    override fun getItemViewType(position: Int): Int = pages[position].layoutResId.takeIf { it != 0 } ?: R.layout.item_itwing_flow_onboarding

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val content = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        val view = if (pageAdsEnabled && content.findViewById<FrameLayout?>(R.id.itwing_flow_page_ad_container) == null) {
            FrameLayout(parent.context).apply {
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                addView(FrameLayout(parent.context).apply {
                    id = R.id.itwing_flow_page_ad_container
                    visibility = View.GONE
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ).apply {
                    bottomMargin = parent.context.dp(78)
                })
            }
        } else {
            content
        }
        view.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.MATCH_PARENT,
        )
        return Holder(view, pageAdsEnabled)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class Holder(view: View, private val pageAdsEnabled: Boolean) : RecyclerView.ViewHolder(view) {
        private val image: ImageView? = view.findViewById(R.id.itwing_flow_page_image)
        private val title: TextView? = view.findViewById(R.id.itwing_flow_page_title)
        private val description: TextView? = view.findViewById(R.id.itwing_flow_page_description)
        private val pageAdContainer: FrameLayout? = view.findViewById(R.id.itwing_flow_page_ad_container)

        fun bind(page: ITWingOnboardingPage) {
            title?.text = page.title
            description?.text = page.description
            when {
                image == null -> Unit
                page.imageResId != 0 -> image.setImageResource(page.imageResId)
                !page.imageUrl.isNullOrBlank() -> Glide.with(image)
                    .load(page.imageUrl)
                    .placeholder(R.drawable.itwing_flow_intro_default_1)
                    .error(R.drawable.itwing_flow_intro_default_1)
                    .dontAnimate()
                    .into(image)
                else -> image.setImageResource(R.drawable.itwing_flow_intro_default_1)
            }
            if (pageAdsEnabled && pageAdContainer != null && !page.nativePlacement.isNullOrBlank()) {
                pageAdContainer.visibility = View.VISIBLE
                pageAdContainer.removeAllViews()
                if (placementFormat(page.nativePlacement) == "banner") {
                    val banner = ITWingBannerView(pageAdContainer.context)
                    banner.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                    banner.placementName = page.nativePlacement
                    pageAdContainer.addView(banner)
                    banner.loadBanner()
                } else {
                    val nativeAd = ITWingNativeAdView(pageAdContainer.context)
                    nativeAd.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                    nativeAd.placementName = page.nativePlacement
                    pageAdContainer.addView(nativeAd)
                    nativeAd.loadAd()
                }
            } else {
                pageAdContainer?.visibility = View.GONE
                pageAdContainer?.removeAllViews()
            }
        }
    }
}

private fun Activity.attachBottomAd(placement: String?, preferredFormat: String? = null) {
    if (placement.isNullOrBlank()) return
    val container = findViewById<FrameLayout?>(R.id.itwing_flow_banner_container) ?: return
    container.removeAllViews()
    if (preferredFormat == "native" || placementFormat(placement) == "native") {
        val native = ITWingNativeAdView(this)
        native.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        container.addView(native)
        native.placementName = placement
        native.loadAd()
    } else {
        val banner = ITWingBannerView(this)
        banner.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        container.addView(banner)
        banner.placementName = placement
        banner.loadBanner()
    }
}

private fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

private fun applyOnboardingInsets(root: View, back: View, bottomBar: View, bottomAd: View, margin: ITWingDimen?, marginDp: Int?) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val marginPx = root.context.dimensionPx(margin) ?: root.context.dp(marginDp ?: 20)
        (back.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.leftMargin = bars.left + marginPx
            params.topMargin = bars.top + marginPx
            back.layoutParams = params
        }
        (bottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.leftMargin = bars.left + marginPx
            params.rightMargin = bars.right + marginPx
            params.bottomMargin = bars.bottom + marginPx
            bottomBar.layoutParams = params
        }
        (bottomAd.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val bottomBarHeight = bottomBar.measuredHeight.takeIf { it > 0 } ?: root.context.dp(35)
            params.leftMargin = bars.left
            params.rightMargin = bars.right
            params.bottomMargin = bars.bottom + marginPx + bottomBarHeight + root.context.dp(8)
            bottomAd.layoutParams = params
        }
        insets
    }
}

private fun applyTermsInsets(root: View, content: View, controls: View, padding: ITWingDimen?, paddingDp: Int?) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val paddingPx = root.context.dimensionPx(padding) ?: root.context.dp(paddingDp ?: 10)
        content.setPadding(
            bars.left + paddingPx,
            bars.top + paddingPx,
            bars.right + paddingPx,
            paddingPx,
        )
        (controls.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = bars.bottom + paddingPx
            controls.layoutParams = params
        }
        insets
    }
}

private fun resolvePages(options: ITWingAppFlowOptions): List<ITWingOnboardingPage> {
    val remotePageMaps = ITWingSDK.currentConfig().app["onboarding_pages"].asListOfMaps()
        .ifEmpty { ITWingSDK.currentConfig().app["onboarding"].asListOfMaps() }
    fun hostXmlPages(): List<ITWingOnboardingPage> = options.splashOnboardings.layouts.mapIndexed { index, layout ->
            val remote = remotePageMaps.getOrNull(index)
            ITWingOnboardingPage(
                title = remote?.value("title") ?: "",
                description = remote?.value("description") ?: remote?.value("body") ?: "",
                imageResId = options.onboardingImages.getOrElse(index) { 0 },
                layoutResId = layout,
                nativePlacement = remote?.value("native_placement")
                    ?: remote?.value("native_ad_placement")
                    ?: remote?.value("ad_placement")
                    ?: options.onboardingActivityAdPlacement,
            )
        }

    val source = onboardingDesignSource()
    val hostPages = hostXmlPages()
    if (source == "host_xml" && hostPages.isNotEmpty()) return hostPages
    if (options.onboardingPages.isNotEmpty()) return options.onboardingPages

    val remotePages = remotePageMaps.mapNotNull { item ->
            val title = item.value("title") ?: ""
            val description = item.value("description") ?: item.value("body") ?: ""
            val imageUrl = item.value("image_url") ?: item.value("image")
            val nativePlacement = item.value("native_placement") ?: item.value("native_ad_placement") ?: item.value("ad_placement")
            if (title.isBlank() && description.isBlank() && imageUrl.isNullOrBlank() && nativePlacement.isNullOrBlank()) {
                return@mapNotNull null
            }
            ITWingOnboardingPage(
                title = title,
                description = description,
                imageUrl = imageUrl,
                nativePlacement = nativePlacement,
            )
        }
    if (remotePages.isNotEmpty()) return remotePages
    if ((source == "auto" || source == "host_xml") && hostPages.isNotEmpty()) return hostPages
    return options.onboardingImages.mapIndexed { index, image ->
        ITWingOnboardingPage(
            title = "",
            description = "",
            imageResId = image,
        )
    }
}

private fun onboardingDesignSource(): String {
    val app = ITWingSDK.currentConfig().app
    val flow = app["start_flow"].safeMap()
        .ifEmpty { app["app_flow"].safeMap() }
    return (flow.safeString("onboarding_design_source")
        ?: appString("onboarding_design_source")
        ?: "admin_pages")
        .trim()
        .lowercase()
        .takeIf { it in setOf("admin_pages", "host_xml", "auto") }
        ?: "admin_pages"
}

private fun Activity.primaryColor(): Int {
    val configured = listOf("primary", "primary_color", "button", "accent")
        .firstNotNullOfOrNull { ITWingSDK.getColor(it).takeIf(String::isNotBlank) }
    return runCatching { Color.parseColor(configured) }.getOrNull() ?: Color.rgb(37, 99, 235)
}

private fun Activity.splashBackgroundColor(): Int {
    val configured = appString("splash_background_color")
    return runCatching { Color.parseColor(configured) }.getOrNull() ?: primaryColor()
}

private fun onPrimary(color: Int): Int =
    if (ColorUtils.calculateLuminance(color) > 0.58) Color.BLACK else Color.WHITE

private fun appString(key: String): String? =
    ITWingSDK.currentConfig().app[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

private fun flowPlacement(key: String, fallback: String?): String? {
    val app = ITWingSDK.currentConfig().app
    val flow = app["start_flow"].safeMap()
        .ifEmpty { app["app_flow"].safeMap() }
    val shortKey = key.removeSuffix("_placement")
    if (flow.containsKey(key)) return flow.safeString(key)
    if (flow.containsKey(shortKey)) return flow.safeString(shortKey)
    if (app.containsKey(key)) return appString(key)
    return fallback
}

private fun flowEnabled(key: String, fallback: Boolean): Boolean {
    val app = ITWingSDK.currentConfig().app
    val flow = app["start_flow"].safeMap()
        .ifEmpty { app["app_flow"].safeMap() }
    return (flow[key] ?: app[key]).asBooleanOrNull() ?: fallback
}

private fun onboardingAdScope(options: ITWingAppFlowOptions): String {
    val app = ITWingSDK.currentConfig().app
    val flow = app["start_flow"].safeMap()
        .ifEmpty { app["app_flow"].safeMap() }
    val configured = flow.safeString("onboarding_ad_scope")
        ?: appString("onboarding_ad_scope")
        ?: options.onboardingAdScope
    return configured?.trim()?.lowercase()?.takeIf { it in setOf("activity", "page", "off", "none") }
        ?.let { if (it == "none") "off" else it }
        ?: "activity"
}

private fun onboardingActivityAdPlacement(options: ITWingAppFlowOptions, page: ITWingOnboardingPage?): String? {
    val configured = flowPlacement("onboarding_activity_ad_placement", options.onboardingActivityAdPlacement)
        ?: flowPlacement("onboarding_banner_placement", options.onboardingBannerPlacement)
    return configured?.takeIf(String::isNotBlank) ?: page?.nativePlacement
}

private fun onboardingAdFormat(options: ITWingAppFlowOptions, placement: String): String {
    val configured = flowPlacement("onboarding_activity_ad_format", options.onboardingActivityAdFormat)
        ?: appString("onboarding_ad_format")
    if (!configured.isNullOrBlank()) return configured.trim().lowercase()
    return placementFormat(placement)
}

private fun termsAdFormat(options: ITWingAppFlowOptions): String? {
    val app = ITWingSDK.currentConfig().app
    val flow = app["start_flow"].safeMap()
        .ifEmpty { app["app_flow"].safeMap() }
    val configured = flow.safeString("terms_ad_format")
        ?: appString("terms_ad_format")
        ?: placementFormat(options.termsBannerPlacement.orEmpty())
    return configured.trim().lowercase().takeIf { it in setOf("native", "banner") }
}

private fun placementFormat(placement: String): String =
    ITWingSDK.currentConfig().ads.placements
        .firstOrNull { it.name == placement }
        ?.format
        ?.lowercase()
        ?.takeIf { it in setOf("banner", "native") }
        ?: "native"

private fun splashStyle(options: ITWingAppFlowOptions): String =
    (options.splashStyle
        ?: appString("splash_style")
        ?: appString("splash_type")
        ?: ITWingSDK.currentConfig().app["splash"].safeMap().safeString("style")
        ?: ITWingSDK.currentConfig().app["splash"].safeMap().safeString("type")
        ?: "default")
        .trim()
        .lowercase()

private fun splashBackgroundUrl(): String? {
    val splash = ITWingSDK.currentConfig().app["splash"].safeMap()
    return appString("splash_background_url")
        ?: appString("splash_bg_url")
        ?: splash.safeString("background_url")
        ?: splash.safeString("background")
        ?: splash.safeString("full_background_url")
}

private fun splashCenterImageUrl(): String? {
    val splash = ITWingSDK.currentConfig().app["splash"].safeMap()
    return appString("splash_center_image_url")
        ?: appString("splash_image_url")
        ?: splash.safeString("center_image_url")
        ?: splash.safeString("image_url")
        ?: splash.safeString("logo_url")
}

private fun splashDelayMillis(): Long {
    val app = ITWingSDK.currentConfig().app
    val splash = app["splash"].safeMap()
    val seconds = splash["seconds"].safeLong()
        ?: app["splash_seconds"].safeLong()
        ?: app["splashSeconds"].safeLong()
        ?: 7L
    return seconds.coerceIn(0L, 15L) * 1000L
}

private fun splashAdFormat(): String {
    val app = ITWingSDK.currentConfig().app
    val splash = app["splash"].safeMap()
    return listOf(
        splash.safeString("ad_format"),
        splash.safeString("adFormat"),
        appString("splash_ad_format"),
        appString("splashAdFormat"),
    ).firstOrNull()?.lowercase() ?: "none"
}

private fun Activity.flowPrefs(): SharedPreferences =
    getSharedPreferences("itwing_app_flow", Activity.MODE_PRIVATE)

private fun Any?.asListOfMaps(): List<Map<*, *>> = when (this) {
    is List<*> -> filterIsInstance<Map<*, *>>()
    else -> emptyList()
}

private fun Any?.safeMap(): Map<*, *> = this as? Map<*, *> ?: emptyMap<Any?, Any?>()

private fun Map<*, *>.safeString(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

private fun Map<*, *>.value(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

private fun Any?.safeLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
    else -> null
}

private fun Any?.asBooleanOrNull(): Boolean? = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    is String -> when (trim().lowercase()) {
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
    }
    else -> null
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun Context.dimensionPx(value: ITWingDimen?): Int? = when {
    value == null -> null
    value.resId != null -> runCatching { resources.getDimensionPixelSize(value.resId) }.getOrNull()
    value.unit == ITWingDimen.Unit.SP -> (value.value.orZero() * resources.displayMetrics.scaledDensity).toInt()
    else -> (value.value.orZero() * resources.displayMetrics.density).toInt()
}

private fun Activity.dimensionPx(value: ITWingDimen?): Int? = (this as Context).dimensionPx(value)

private fun ITWingDimen.cssTextSize(context: Context): Float? = when {
    resId != null -> resId?.let { id ->
        runCatching { context.resources.getDimension(id) / context.resources.displayMetrics.scaledDensity }.getOrNull()
    }
    unit == ITWingDimen.Unit.SP -> value
    else -> value
}

private fun TextView.applySdkTextSize(value: ITWingDimen?, fallbackSp: Float?) {
    when {
        value?.resId != null -> value.resId?.let { resId ->
            runCatching {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(resId))
            }
        }
        value?.unit == ITWingDimen.Unit.SP -> setTextSize(TypedValue.COMPLEX_UNIT_SP, value.value.orZero())
        value?.unit == ITWingDimen.Unit.DP -> setTextSize(TypedValue.COMPLEX_UNIT_PX, context.dimensionPx(value)?.toFloat() ?: return)
        fallbackSp != null -> setTextSize(TypedValue.COMPLEX_UNIT_SP, fallbackSp)
    }
}

private fun Float?.orZero(): Float = this ?: 0f

private fun Int.toCssColor(): String = String.format("#%06X", 0xFFFFFF and this)

private fun View.applyViewSize(
    widthPx: Int? = null,
    heightPx: Int? = null,
    widthDp: Int? = null,
    heightDp: Int? = null,
) {
    if (widthPx == null && heightPx == null && widthDp == null && heightDp == null) return
    layoutParams = layoutParams.apply {
        widthPx?.let { width = it } ?: widthDp?.let { width = context.dp(it) }
        heightPx?.let { height = it } ?: heightDp?.let { height = context.dp(it) }
    }
}

private fun Activity.enterFullscreen() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private const val KEY_ONBOARDING_DONE = "onboarding_done"
private const val KEY_TERMS_ACCEPTED = "terms_accepted"
