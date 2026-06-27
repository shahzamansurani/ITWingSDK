package com.itwingtech.itwingsdk.flow

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.itwingtech.itwingsdk.ads.ITWingBannerView
import com.itwingtech.itwingsdk.ads.ITWingNativeAdView
import com.itwingtech.itwingsdk.core.ITWingAppFlowOptions
import com.itwingtech.itwingsdk.core.ITWingAppFlowRegistry
import com.itwingtech.itwingsdk.core.ITWingAppFlowSession
import com.itwingtech.itwingsdk.core.ITWingConfig
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
                if (shouldShowStartupScreens() || splashAdFormat() in setOf("none", "no_ad", "disabled")) {
                    continueAfterUpdateCheckWithDelay()
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

        when (style) {
            "center_image", "image", "logo_only" -> {
                background.visibility = View.GONE
                content.visibility = View.VISIBLE
                title.visibility = View.GONE
                subtitle.visibility = View.GONE
                lottie.visibility = View.VISIBLE
                setSplashLogoSize(logo, ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
                (content.layoutParams as? RelativeLayout.LayoutParams)?.let {
                    it.setMargins(dp(20), dp(20), dp(20), dp(20))
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
                setSplashLogoSize(logo, dp(40), dp(40))
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
        val shouldShowOnboarding = current.flowOptions.showOnboarding &&
            pages.isNotEmpty() &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        val shouldShowTerms = current.flowOptions.requireTerms &&
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
        val shouldShowOnboarding = current.flowOptions.showOnboarding &&
            pages.isNotEmpty() &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        val shouldShowTerms = current.flowOptions.requireTerms &&
            !prefs.getBoolean(KEY_TERMS_ACCEPTED, false)

        val opensMain = !shouldShowOnboarding && !shouldShowTerms
        val target = when {
            shouldShowOnboarding -> Intent(this, ITWingFlowOnboardingActivity::class.java)
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
    private lateinit var bottomNativeAd: ITWingNativeAdView
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
        setContentView(R.layout.activity_itwing_flow_onboarding)
        applyInsets(findViewById(R.id.itwing_flow_root))
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
        bottomNativeAd = findViewById(R.id.itwing_flow_onboarding_native)

        val primary = primaryColor()
        nextButton.backgroundTintList = ColorStateList.valueOf(primary)
        nextButton.setTextColor(onPrimary(primary))
        backButton.imageTintList = null

        pager.adapter = OnboardingAdapter(pages)
        dots.post { renderDots(0) }
        backButton.visibility = View.INVISIBLE
        updateBottomNativeAd(0)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderDots(position)
                nextButton.text = getString(if (position == pages.lastIndex) R.string.itwing_flow_finish else R.string.itwing_flow_next)
                backButton.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
                updateBottomNativeAd(position)
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
        attachBanner(flowPlacement("onboarding_banner_placement", current.flowOptions.onboardingBannerPlacement))
    }

    private fun updateBottomNativeAd(position: Int) {
        val placement = pages.getOrNull(position)?.nativePlacement?.trim()
        if (placement.isNullOrBlank()) {
            bottomNativeAd.visibility = View.GONE
            return
        }
        bottomNativeAd.visibility = View.VISIBLE
        if (bottomNativeAd.placementName != placement) {
            bottomNativeAd.placementName = placement
        }
        bottomNativeAd.loadAd()
    }

    private fun renderDots(position: Int) {
        dots.removeAllViews()
        val primary = primaryColor()
        for (index in pages.indices) {
            val dot = View(this)
            val width = if (index == position) dp(30) else dp(10)
            val params = LinearLayout.LayoutParams(width, dp(10))
            params.setMargins(dp(3), 0, dp(3), 0)
            dot.layoutParams = params
            dot.setBackgroundResource(if (index == position) R.drawable.itwing_flow_dot_active else R.drawable.itwing_flow_dot_inactive)
            dot.backgroundTintList = ColorStateList.valueOf(if (index == position) primary else Color.rgb(220, 227, 234))
            dots.addView(dot)
        }
    }

    private fun finishOnboarding() {
        val current = session ?: return finish()
        val shouldShowTerms = current.flowOptions.requireTerms &&
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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_itwing_flow_terms)
        applyInsets(findViewById(R.id.itwing_flow_root))
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        val current = session ?: return
        val primary = primaryColor()
        val check = findViewById<CheckBox>(R.id.itwing_flow_terms_check)
        val accept = findViewById<Button>(R.id.itwing_flow_terms_accept)
        check.buttonTintList = ColorStateList.valueOf(primary)
        accept.backgroundTintList = ColorStateList.valueOf(primary)
        accept.setTextColor(onPrimary(primary))

        val web = findViewById<WebView>(R.id.itwing_flow_terms_web)
        web.settings.javaScriptEnabled = false
        web.loadDataWithBaseURL(
            null,
            legalHtml(),
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
        attachBanner(flowPlacement("terms_banner_placement", current.flowOptions.termsBannerPlacement))
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

    private fun legalHtml(): String {
        ITWingSDK.getAppUrl("terms")?.takeIf(String::isNotBlank)?.let {
            return """<html><body style="margin:0"><iframe src="$it" style="border:0;width:100%;height:100%"></iframe></body></html>"""
        }
        val terms = ITWingSDK.getLegalContent("terms")
            ?: "Terms of Use\n\nPlease review and accept the terms to continue."
        val privacy = ITWingSDK.getLegalContent("privacy")
        val disclaimer = ITWingSDK.getLegalContent("disclaimer")
        return buildString {
            append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
            append("<style>body{font-family:sans-serif;color:#111827;line-height:1.55;padding:4px 2px}h1,h2{font-size:20px}pre{white-space:pre-wrap}</style>")
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
) : RecyclerView.Adapter<OnboardingAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_itwing_flow_onboarding, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.itwing_flow_page_image)
        private val title: TextView = view.findViewById(R.id.itwing_flow_page_title)
        private val description: TextView = view.findViewById(R.id.itwing_flow_page_description)

        fun bind(page: ITWingOnboardingPage) {
            title.text = page.title
            description.text = page.description
            when {
                page.imageResId != 0 -> image.setImageResource(page.imageResId)
                !page.imageUrl.isNullOrBlank() -> Glide.with(image)
                    .load(page.imageUrl)
                    .placeholder(R.drawable.itwing_flow_intro_default_1)
                    .error(R.drawable.itwing_flow_intro_default_1)
                    .dontAnimate()
                    .into(image)
                else -> image.setImageResource(R.drawable.itwing_flow_intro_default_1)
            }
        }
    }
}

private fun Activity.attachBanner(placement: String?) {
    if (placement.isNullOrBlank()) return
    val container = findViewById<FrameLayout?>(R.id.itwing_flow_banner_container) ?: return
    container.removeAllViews()
    val banner = ITWingBannerView(this)
    banner.layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    )
    container.addView(banner)
    banner.placementName = placement
    banner.loadBanner()
}

private fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

private fun resolvePages(options: ITWingAppFlowOptions): List<ITWingOnboardingPage> {
    if (options.onboardingPages.isNotEmpty()) return options.onboardingPages
    val remotePages = ITWingSDK.currentConfig().app["onboarding_pages"].asListOfMaps()
        .ifEmpty { ITWingSDK.currentConfig().app["onboarding"].asListOfMaps() }
        .mapNotNull { item ->
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
    val localImages = options.onboardingImages
    val defaultTitles = listOf("Powered by IT Wing Technologies", "Remote App Control", "Ads, Analytics & Growth")
    val defaultDescriptions = listOf(
        "Launch apps with a reliable SDK platform built by IT Wing Technologies.",
        "Sync app settings, legal content, startup flow, and media from itwingtech.com.",
        "Manage ads, subscriptions, notifications, and analytics from one secure dashboard.",
    )
    val fallbackImages = listOf(
        R.drawable.itwing_flow_intro_default_1,
        R.drawable.itwing_flow_intro_default_2,
        R.drawable.itwing_flow_intro_default_3,
    )
    return (0 until maxOf(3, localImages.size)).map { index ->
        ITWingOnboardingPage(
            title = defaultTitles.getOrElse(index) { "Welcome" },
            description = defaultDescriptions.getOrElse(index) { "" },
            imageResId = localImages.getOrElse(index) { fallbackImages.getOrElse(index) { 0 } },
        )
    }
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

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun Activity.enterFullscreen() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private const val KEY_ONBOARDING_DONE = "onboarding_done"
private const val KEY_TERMS_ACCEPTED = "terms_accepted"
