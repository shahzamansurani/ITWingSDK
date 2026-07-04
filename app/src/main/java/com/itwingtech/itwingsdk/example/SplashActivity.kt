package com.itwingtech.itwingsdk.example

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingFlowButtonStyle
import com.itwingtech.itwingsdk.core.ITWingFlowDotsStyle
import com.itwingtech.itwingsdk.core.ITWingFlowIconStyle
import com.itwingtech.itwingsdk.core.ITWingFlowTextStyle
import com.itwingtech.itwingsdk.core.ITWingDimen
import com.itwingtech.itwingsdk.core.ITWingOnboardingUiStyle
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.ITWingSplashUiStyle
import com.itwingtech.itwingsdk.core.ITWingStartAppFlowConfig
import com.itwingtech.itwingsdk.core.ITWingTermsUiStyle
import com.itwingtech.itwingsdk.core.SplashOnBoardings
import com.itwingtech.itwingsdk.core.dp
import com.itwingtech.itwingsdk.core.sp
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
                splashUi = ITWingSplashUiStyle(
                    title = ITWingFlowTextStyle(color = Color.WHITE, textSize = 22.sp),
                    subtitle = ITWingFlowTextStyle(
                        color = Color.rgb(229, 231, 235),
                        textSize = 14.sp
                    ),
                    logoWidth = ITWingDimen.res(R.dimen.example_flow_logo_size),
                    logoHeight = ITWingDimen.res(R.dimen.example_flow_logo_size),
                    contentMargin = 28.dp,
                    lottieWidth = 96.dp,
                    lottieHeight = 96.dp,
                    lottieBottomMargin = 28.dp,
                ),
                onboardingUi = ITWingOnboardingUiStyle(
                    nextButton = ITWingFlowButtonStyle(
                        backgroundDrawableRes = R.drawable.itwing_example_flow_button,
                        textColor = Color.WHITE,
                        textSize = 14.sp,
                    ),
                    backButton = ITWingFlowIconStyle(
                        tintColor = Color.WHITE,
                        size = 28.dp,
                    ),
                    dots = ITWingFlowDotsStyle(
                        activeColor = Color.rgb(139, 92, 246),
                        inactiveColor = Color.WHITE,
                        activeWidth = 30.dp,
                        inactiveWidth = 10.dp,
                        height = 10.dp,
                        spacing = 3.dp,
                    ),
                    controlsMargin = 20.dp,
                ),
                termsUi = ITWingTermsUiStyle(
                    body = ITWingFlowTextStyle(color = Color.WHITE, textSize = 14.sp),
                    heading = ITWingFlowTextStyle(color = Color.WHITE, textSize = 20.sp),
                    acceptButton = ITWingFlowButtonStyle(
                        backgroundDrawableRes = R.drawable.itwing_example_terms_button,
                        textColor = Color.BLACK,
                        textSize = 14.sp,
                    ),
                    checkbox = ITWingFlowTextStyle(color = Color.WHITE, textSize = 13.sp),
                    checkboxTintColor = Color.WHITE,
                    contentPadding = 16.dp,
                ),
                termsBackgroundColor = Color.BLACK,
                splashOnboardings = SplashOnBoardings(
                    screen1 = R.layout.activity_spalash,
                    screen2 = R.layout.activity_spalash,
                ),
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
