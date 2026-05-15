package com.itwingtech.itwingsdk.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)


        binding.interstitial.setOnClickListener {
            ITWingSDK.analytics.track("example_interstitial_clicked")
            ITWingSDK.showInterstitial(this, "interstitial") {
                ITWingSDK.analytics.track("example_interstitial_closed")
            }
        }
        binding.rewarded.setOnClickListener {
            ITWingSDK.analytics.track("example_rewarded_clicked")
            ITWingSDK.showRewarded(this, "custom_rewarded") {
                ITWingSDK.analytics.track("example_rewarded_closed")
            }
        }
        binding.rewardedInterstitial.setOnClickListener {
            ITWingSDK.analytics.track("example_rewarded_interstitial_clicked")
            ITWingSDK.showRewardedInterstitial(this, "rewarded_interstitial") {
                ITWingSDK.analytics.track("example_rewarded_interstitial_closed")
            }
        }
        binding.appOpen.setOnClickListener {
            ITWingSDK.analytics.track("example_app_open_clicked")
            ITWingSDK.showAppOpen(this, "app_open_manual") {
                ITWingSDK.analytics.track("example_app_open_closed")
            }
        }

    }
}
