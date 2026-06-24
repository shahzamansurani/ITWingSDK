package com.itwingtech.itwingsdk.example

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.example.BuildConfig
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingOptions
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.SDKInitListener
import com.itwingtech.itwingsdk.example.databinding.ActivitySpalashBinding
import java.net.URL

class SpalashActivity : AppCompatActivity() {
    private val binding by lazy { ActivitySpalashBinding.inflate(layoutInflater) }
    @Volatile
    private var mainOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        /*
         * Minimal integration is also supported:
         *
         * ITWingSDK.initialize(this, BuildConfig.ITWING_SDK_KEY) {
         *     ITWingSDK.showSplash(this) { openMain() }
         * }
         */
        Log.d("ITWingExample", "Initializing SDK endpoint=${BuildConfig.ITWING_SDK_ENDPOINT} key=${BuildConfig.ITWING_SDK_KEY.take(12)}...")
        ITWingSDK.initialize(
            activity = this,
            apiKey = "itw_test_example_android_sdk_key_change_me_1234567890",
            options = ITWingOptions(endpoint = BuildConfig.ITWING_SDK_ENDPOINT, autoApplyResponsiveLayout = true),
            listener = object : SDKInitListener {
                override fun onReady() {
                    Log.d("ITWingExample", "SDK ready")
                    renderSplashBranding()
                    ITWingSDK.showSplash(this@SpalashActivity) {
                        openMain()
                    }
                }

                override fun onConfigLoaded(config: ITWingConfig) {
                    Log.d("ITWingExample", "Config loaded: ${config.configVersion}")
                    renderSplashBranding()
                }

                override fun onAdsReady() {
                    Log.d("ITWingExample", "Ads ready")
                }

                override fun onBillingReady() {
                    Log.d("ITWingExample", "Billing ready")
                }

                override fun onNotificationsReady() {
                    Log.d("ITWingExample", "Notifications ready")
                }

                override fun onAnalyticsReady() {
                    Log.d("ITWingExample", "Analytics ready")
                }

                override fun onOfflineMode(reason: String) {
                    Log.w("ITWingExample", reason)
                }

                override fun onRetry(reason: String) {
                    Log.w("ITWingExample", "Retrying SDK bootstrap: $reason")
                }

                override fun onError(error: String) {
                    Log.e("ITWingExample", error)
                    openMain()
                }
            }
        )
    }

    private fun renderSplashBranding() {
        binding.splashTitle.text = ITWingSDK.getAppTitle("ITWing SDK")
        binding.splashStatus.text = "Loading app configuration"
        ITWingSDK.getLogoUri()?.let { loadLogo(it) }
    }

    private fun loadLogo(uri: Uri) {
        if (uri.scheme == "content" || uri.scheme == "file" || uri.scheme == "android.resource") {
            binding.splashicon.setImageURI(uri)
            return
        }

        Thread {
            val bitmap = runCatching {
                URL(uri.toString()).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) {
                runOnUiThread { binding.splashicon.setImageBitmap(bitmap) }
            }
        }.start()
    }

    private fun openMain() {
        if (mainOpened) return
        mainOpened = true
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
