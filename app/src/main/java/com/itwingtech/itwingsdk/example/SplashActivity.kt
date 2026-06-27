package com.itwingtech.itwingsdk.example

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.ITWingOptions
import com.itwingtech.itwingsdk.example.databinding.ActivitySpalashBinding
import java.net.URL

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
            sdkOptions = ITWingOptions(autoApplyResponsiveLayout = true),
            splash_bg = binding.splashBackground,
            splash_title = binding.splashTitle,
            splash_sub_title = binding.splashSubtitle,
            splash_lottie_anim = binding.splashAnim,
            splash_bg_color = binding.splashBgColor,
            splash_logo = binding.splashLogo,
        )

//        /*
//         * Minimal integration is also supported:
//         *
//         * ITWingSDK.initialize(this, BuildConfig.ITWING_SDK_KEY) {
//         *     ITWingSDK.showSplash(this) { openMain() }
//         * }
//         */
//        ITWingSDK.initialize(
//            activity = this,
//            apiKey = "itw_test_example_android_sdk_key_change_me_1234567890",
//            options = ITWingOptions(endpoint = BuildConfig.ITWING_SDK_ENDPOINT, autoApplyResponsiveLayout = true),
//            listener = object : SDKInitListener {
//                override fun onReady() {
//                    Log.d("ITWingExample", "SDK ready")
//                    renderSplashBranding()
//                    ITWingSDK.showSplash(this@SpalashActivity) {
//                        openMain()
//                    }
//                }
//
//                override fun onConfigLoaded(config: ITWingConfig) {
//                    Log.d("ITWingExample", "Config loaded: ${config.configVersion}")
//                    renderSplashBranding()
//                }
//
//                override fun onAdsReady() {
//                    Log.d("ITWingExample", "Ads ready")
//                }
//
//                override fun onBillingReady() {
//                    Log.d("ITWingExample", "Billing ready")
//                }
//
//                override fun onNotificationsReady() {
//                    Log.d("ITWingExample", "Notifications ready")
//                }
//
//                override fun onAnalyticsReady() {
//                    Log.d("ITWingExample", "Analytics ready")
//                }
//
//                override fun onOfflineMode(reason: String) {
//                    Log.w("ITWingExample", reason)
//                }
//
//                override fun onRetry(reason: String) {
//                    Log.w("ITWingExample", "Retrying SDK bootstrap: $reason")
//                }
//
//                override fun onError(error: String) {
//                    Log.e("ITWingExample", error)
//                    openMain()
//                }
//            }
//        )
    }
}
