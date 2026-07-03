package com.itwingtech.itwingsdk.example

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.ITWingStartAppFlowConfig
import com.itwingtech.itwingsdk.core.SDKInitListener
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
