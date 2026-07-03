package com.itwingtech.itwingsdk.example

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.ITWingStartAppFlowConfig
import com.itwingtech.itwingsdk.core.SplashOnBoardings
import com.itwingtech.itwingsdk.example.databinding.ActivitySpalashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private val binding by lazy { ActivitySpalashBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)


        ITWingSDK.startAppFlow(
            activity = this,
            apiKey = getString(R.string.itwing_sdk_app_id),
            mainActivity = MainActivity::class.java,
            config = ITWingStartAppFlowConfig(
                autoApplyResponsiveLayout = true,
                analyticsEnabled = true,
                showSplash = true,
                showOnboarding = true,
                requireTerms = true,
                splashBackground = binding.splashBackground,
                splashTitle = binding.splashTitle,
                splashSubtitle = binding.splashSubtitle,
                splashLottie = binding.splashAnim,
                splashBackgroundColor = binding.splashBgColor,
                splashLogo = binding.splashLogo,
                splashTitleTextColor = null,
                splashSubtitleTextColor = null,
                splashTitleTextSizeSp = null,
                splashSubtitleTextSizeSp = null,
                splashLogoWidthDp = null,
                splashLogoHeightDp = null,
                splashContentMarginDp = null,
                splashLottieWidthDp = null,
                splashLottieHeightDp = null,
                splashLottieBottomMarginDp = null,
                onboardingButtonColor = Color.YELLOW,
                onboardingButtonTextColor = Color.WHITE,
                onboardingButtonStrokeColor = null,
                onboardingButtonStrokeWidthDp = 0,
                onboardingButtonTextSizeSp = null,
                onboardingButtonWidthDp = null,
                onboardingButtonHeightDp = null,
                onboardingButtonCornerRadiusDp = null,
//                onboardingBackTintColor = Color.WHITE,
                onboardingBackSizeDp = null,
                onboardingControlsMarginDp = null,
                onboardingBottomBarBackgroundColor = null,
                onboardingDotsActiveColor = Color.GREEN,
                onboardingDotsInactiveColor = Color.WHITE,
                onboardingDotActiveWidthDp = null,
                onboardingDotInactiveWidthDp = null,
                onboardingDotHeightDp = null,
                onboardingDotSpacingDp = null,
                termsBackgroundColor = Color.BLACK,
                termsTextColor = Color.WHITE,
                termsHeadingTextColor = Color.WHITE,
                termsTextSizeSp = null,
                termsHeadingTextSizeSp = null,
                termsContentPaddingDp = null,
                termsAcceptButtonText = null,
                termsAcceptButtonColor = Color.WHITE,
                termsAcceptButtonTextColor = Color.BLACK,
                termsAcceptButtonStrokeColor = null,
                termsAcceptButtonStrokeWidthDp = 0,
                termsAcceptButtonTextSizeSp = null,
                termsAcceptButtonWidthDp = null,
                termsAcceptButtonHeightDp = null,
                termsAcceptButtonCornerRadiusDp = null,
                termsCheckboxText = null,
                termsCheckboxTextColor = Color.WHITE,
                termsCheckboxTextSizeSp = null,
                termsCheckboxTintColor = null,
                splashOnboardings = SplashOnBoardings(screen1 = R.layout.activity_spalash, screen2 = R.layout.activity_spalash)
            ),
        )

        /*
         * Smallest valid integration:
         *
         * ITWingSDK.startAppFlow(
         *     activity = this,
         *     apiKey = getString(R.string.itwing_sdk_app_id),
         *     mainActivity = MainActivity::class.java,
         * )
         */
    }
}
