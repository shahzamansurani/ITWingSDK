package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

data class ITWingStartAppFlowConfig @JvmOverloads constructor(
    val endpoint: String? = null,
    val autoApplyResponsiveLayout: Boolean = true,
    val analyticsEnabled: Boolean = true,
    val bootstrapTimeoutMs: Long = 4_000,
    val strictSslPinning: Boolean = false,
    val finishCurrent: Boolean = true,
    val showSplash: Boolean = true,
    val showOnboarding: Boolean = true,
    val requireTerms: Boolean = true,
    val splashStyle: String? = "app_own",
    val splashBackground: ImageView? = null,
    val splashTitle: TextView? = null,
    val splashSubtitle: TextView? = null,
    val splashLottie: View? = null,
    val splashBackgroundColor: View? = null,
    val splashLogo: ImageView? = null,
    val splashTitleTextColor: Int? = null,
    val splashSubtitleTextColor: Int? = null,
    val splashTitleTextSizeSp: Float? = null,
    val splashSubtitleTextSizeSp: Float? = null,
    val splashLogoWidthDp: Int? = null,
    val splashLogoHeightDp: Int? = null,
    val splashContentMarginDp: Int? = null,
    val splashLottieWidthDp: Int? = null,
    val splashLottieHeightDp: Int? = null,
    val splashLottieBottomMarginDp: Int? = null,
    val splashOnboardings: SplashOnBoardings = SplashOnBoardings(),
    val onboardingPages: List<ITWingOnboardingPage> = emptyList(),
    val onboardingImages: List<Int> = emptyList(),
    val onboardingBannerPlacement: String? = "banner_adaptive",
    val onboardingAdScope: String? = null,
    val onboardingActivityAdPlacement: String? = null,
    val onboardingActivityAdFormat: String? = null,
    val termsBannerPlacement: String? = "banner_adaptive",
    val termsInterstitialPlacement: String? = "interstitial",
    val termsBackgroundColor: Int? = null,
    val termsTextColor: Int? = null,
    val termsHeadingTextColor: Int? = null,
    val termsTextSizeSp: Float? = null,
    val termsHeadingTextSizeSp: Float? = null,
    val termsContentPaddingDp: Int? = null,
    val termsAcceptButtonText: String? = null,
    val termsAcceptButtonColor: Int? = null,
    val termsAcceptButtonTextColor: Int? = null,
    val termsAcceptButtonStrokeColor: Int? = null,
    val termsAcceptButtonStrokeWidthDp: Int = 0,
    val termsAcceptButtonTextSizeSp: Float? = null,
    val termsAcceptButtonWidthDp: Int? = null,
    val termsAcceptButtonHeightDp: Int? = null,
    val termsAcceptButtonCornerRadiusDp: Int? = null,
    val termsCheckboxText: String? = null,
    val termsCheckboxTextColor: Int? = null,
    val termsCheckboxTextSizeSp: Float? = null,
    val termsCheckboxTintColor: Int? = null,
    val onboardingButtonColor: Int? = null,
    val onboardingButtonTextColor: Int? = null,
    val onboardingButtonStrokeColor: Int? = null,
    val onboardingButtonStrokeWidthDp: Int = 0,
    val onboardingButtonTextSizeSp: Float? = null,
    val onboardingButtonWidthDp: Int? = null,
    val onboardingButtonHeightDp: Int? = null,
    val onboardingButtonCornerRadiusDp: Int? = null,
    val onboardingBackTintColor: Int? = null,
    val onboardingBackSizeDp: Int? = null,
    val onboardingControlsMarginDp: Int? = null,
    val onboardingBottomBarBackgroundColor: Int? = null,
    val onboardingDotsActiveColor: Int? = null,
    val onboardingDotsInactiveColor: Int? = null,
    val onboardingDotActiveWidthDp: Int? = null,
    val onboardingDotInactiveWidthDp: Int? = null,
    val onboardingDotHeightDp: Int? = null,
    val onboardingDotSpacingDp: Int? = null,
    val listener: SDKInitListener? = null,
)

data class ITWingAppFlowOptions @JvmOverloads constructor(
    val splashLogo: Int = 0,
    val splashBackground: Int = 0,
    val splashStyle: String? = "app_own",
    val splashTitle: String? = null,
    val splashSubtitle: String? = null,
    val splashLottieUrl: String? = null,
    val splashTitleTextColor: Int? = null,
    val splashSubtitleTextColor: Int? = null,
    val splashTitleTextSizeSp: Float? = null,
    val splashSubtitleTextSizeSp: Float? = null,
    val splashLogoWidthDp: Int? = null,
    val splashLogoHeightDp: Int? = null,
    val splashContentMarginDp: Int? = null,
    val splashLottieWidthDp: Int? = null,
    val splashLottieHeightDp: Int? = null,
    val splashLottieBottomMarginDp: Int? = null,
    val onboardingImages: List<Int> = emptyList(),
    val onboardingPages: List<ITWingOnboardingPage> = emptyList(),
    val splashOnboardings: SplashOnBoardings = SplashOnBoardings(),
    val onboardingBannerPlacement: String? = "banner_adaptive",
    val termsBannerPlacement: String? = "banner_adaptive",
    val termsInterstitialPlacement: String? = "interstitial",
    val termsBackgroundColor: Int? = null,
    val termsTextColor: Int? = null,
    val termsHeadingTextColor: Int? = null,
    val termsTextSizeSp: Float? = null,
    val termsHeadingTextSizeSp: Float? = null,
    val termsContentPaddingDp: Int? = null,
    val termsAcceptButtonText: String? = null,
    val termsAcceptButtonColor: Int? = null,
    val termsAcceptButtonTextColor: Int? = null,
    val termsAcceptButtonStrokeColor: Int? = null,
    val termsAcceptButtonStrokeWidthDp: Int = 0,
    val termsAcceptButtonTextSizeSp: Float? = null,
    val termsAcceptButtonWidthDp: Int? = null,
    val termsAcceptButtonHeightDp: Int? = null,
    val termsAcceptButtonCornerRadiusDp: Int? = null,
    val termsCheckboxText: String? = null,
    val termsCheckboxTextColor: Int? = null,
    val termsCheckboxTextSizeSp: Float? = null,
    val termsCheckboxTintColor: Int? = null,
    val requireTerms: Boolean = true,
    val showOnboarding: Boolean = true,
    val showSplash: Boolean = true,
    val onboardingAdScope: String? = null,
    val onboardingActivityAdPlacement: String? = null,
    val onboardingActivityAdFormat: String? = null,
    val onboardingButtonColor: Int? = null,
    val onboardingButtonTextColor: Int? = null,
    val onboardingButtonStrokeColor: Int? = null,
    val onboardingButtonStrokeWidthDp: Int = 0,
    val onboardingButtonTextSizeSp: Float? = null,
    val onboardingButtonWidthDp: Int? = null,
    val onboardingButtonHeightDp: Int? = null,
    val onboardingButtonCornerRadiusDp: Int? = null,
    val onboardingBackTintColor: Int? = null,
    val onboardingBackSizeDp: Int? = null,
    val onboardingControlsMarginDp: Int? = null,
    val onboardingBottomBarBackgroundColor: Int? = null,
    val onboardingDotsActiveColor: Int? = null,
    val onboardingDotsInactiveColor: Int? = null,
    val onboardingDotActiveWidthDp: Int? = null,
    val onboardingDotInactiveWidthDp: Int? = null,
    val onboardingDotHeightDp: Int? = null,
    val onboardingDotSpacingDp: Int? = null,
)

data class ITWingOnboardingPage @JvmOverloads constructor(
    val title: String,
    val description: String,
    val imageResId: Int = 0,
    val imageUrl: String? = null,
    val nativePlacement: String? = null,
    val layoutResId: Int = 0,
)

data class SplashOnBoardings @JvmOverloads constructor(
    val screen1: Int = 0,
    val screen2: Int = 0,
    val screen3: Int = 0,
    val screen4: Int = 0,
    val screen5: Int = 0,
    val screen6: Int = 0,
    val screen7: Int = 0,
    val screen8: Int = 0,
    val screen9: Int = 0,
    val screen10: Int = 0,
) {
    val layouts: List<Int>
        get() = listOf(screen1, screen2, screen3, screen4, screen5, screen6, screen7, screen8, screen9, screen10)
            .filter { it != 0 }

    companion object {
        @JvmStatic
        fun of(vararg layouts: Int): SplashOnBoardings {
            val values = layouts.filter { it != 0 }
            return SplashOnBoardings(
                screen1 = values.getOrElse(0) { 0 },
                screen2 = values.getOrElse(1) { 0 },
                screen3 = values.getOrElse(2) { 0 },
                screen4 = values.getOrElse(3) { 0 },
                screen5 = values.getOrElse(4) { 0 },
                screen6 = values.getOrElse(5) { 0 },
                screen7 = values.getOrElse(6) { 0 },
                screen8 = values.getOrElse(7) { 0 },
                screen9 = values.getOrElse(8) { 0 },
                screen10 = values.getOrElse(9) { 0 },
            )
        }
    }
}

internal data class ITWingAppFlowSession(
    val apiKey: String,
    val sdkOptions: ITWingOptions,
    val flowOptions: ITWingAppFlowOptions,
    val mainActivityName: String,
    val listener: SDKInitListener? = null,
)

internal object ITWingAppFlowRegistry {
    private val sessions = ConcurrentHashMap<String, ITWingAppFlowSession>()

    fun put(session: ITWingAppFlowSession): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = session
        return id
    }

    fun get(id: String?): ITWingAppFlowSession? {
        if (id.isNullOrBlank()) return null
        return sessions[id]
    }

    fun remove(id: String?) {
        if (!id.isNullOrBlank()) sessions.remove(id)
    }
}

fun <T : Activity> KClass<T>.asJavaActivity(): Class<T> = java
