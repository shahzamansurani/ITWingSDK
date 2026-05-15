package com.itwingtech.itwingsdk.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.itwingtech.itwingsdk.core.ITWingConfig
import com.itwingtech.itwingsdk.core.ITWingOptions
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.SDKInitListener
import com.itwingtech.itwingsdk.example.databinding.ActivitySpalashBinding

class SpalashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val binding by lazy { ActivitySpalashBinding.inflate(layoutInflater) }
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        ITWingSDK.initialize(this, "itw_test_example_android_sdk_key_change_me_1234567890") {
            Log.d("ITWingExample", "SDK ready")
            Log.d("ITWingExample", ITWingSDK.diagnostics().toString())

            val icon = ITWingSDK.getLogoUri()
            if (icon != null) {
                binding.splashicon.setImageURI(icon)
            } else {
                Toast.makeText(this, "Icon not found", Toast.LENGTH_SHORT).show()
            }

            ITWingSDK.showSplash(this) {
                startActivity(Intent(this@SpalashActivity, MainActivity::class.java))
                finish()
            }
        }

        ITWingSDK.initialize(
            this, "itw_test_example_android_sdk_key_change_me_1234567890",
            object : SDKInitListener {
                override fun onReady() {
                    Log.d("ITWingExample", "SDK ready")
                    Log.d("ITWingExample", ITWingSDK.getLogoUri().toString())


                }

                override fun onError(error: String) {
                    Log.e("ITWingExample", error)
                }

                override fun onConfigLoaded(config: ITWingConfig) {
                    Log.d("ITWingExample", "config=${config.configVersion}")
                }

                override fun onAdsReady() {
                    Log.d("ITWingExample", "ads ready")
                }

                override fun onOfflineMode(reason: String) {
                    Log.w("ITWingExample", reason)
                }
            },
        )
    }
}