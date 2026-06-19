package com.itwingtech.itwingsdk.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.example.databinding.ActivityMainBinding
import kotlin.getValue

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        binding.notificationText.text = "Notification permission: ${if (granted) "granted" else "denied"}"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        requestNotificationPermissionIfNeeded()
        bindSdkExamples()
        renderSdkState()

        ITWingSDK.onReady {
            renderSdkState()
            Toast.makeText(this, "SDK ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSdkExamples() {
        binding.refreshConfig.setOnClickListener {
            ITWingSDK.refreshConfig { updated ->
                renderSdkState()
                toast("Config refreshed: $updated")
            }
        }

        binding.trackEvent.setOnClickListener {
            ITWingSDK.analytics.track(
                "example_manual_event",
                mapOf("screen" to "main", "source" to "host_example")
            )
            toast("Event tracked")
        }

        ITWingSDK.bindSubscriptionControls(
            activity = this,
            statusView = binding.notificationText,
            subscribeButton = binding.showPurchaseDialog,
            restoreButton = binding.restorePurchases,
            activeText = "Premium active",
            inactiveText = "Premium inactive",
        )

        binding.checkForUpdates.setOnClickListener {
            ITWingSDK.checkForUpdates(this, force = true)
            toast("Update check requested")
        }

        binding.directSubscription.setOnClickListener {
            ITWingSDK.launchSubscriptionPurchase(this, "premium_monthly")
        }

        binding.preloadAds.setOnClickListener {
            ITWingSDK.ads.preloadAll(this)
            toast("Fullscreen ads preload requested")
        }

        binding.realInterstitial.setOnClickListener {
            showInterstitialAndNavigate("interstitial")
        }

        binding.splashInterstitial.setOnClickListener {
            showInterstitialAndNavigate("splash_interstitial")
        }

        binding.customInterstitial.setOnClickListener {
            showInterstitialAndNavigate("custom_interstitial")
        }

        binding.rewardedVideo.setOnClickListener {
            showRewardedAndNavigate("rewarded")
        }

        binding.rewardedImage.setOnClickListener {
            showRewardedAndNavigate("rewarded")
        }

        binding.customRewarded.setOnClickListener {
            showRewardedAndNavigate("custom_rewarded")
        }

        binding.realRewardedInterstitial.setOnClickListener {
            showRewardedInterstitialAndNavigate("rewarded_interstitial")
        }

        binding.customRewardedInterstitial.setOnClickListener {
            showRewardedAndNavigate("custom_rewarded")
        }

        binding.appOpenResume.setOnClickListener {
            showAppOpenAndNavigate("app_open_auto")
        }

        binding.appOpenSplash.setOnClickListener {
            showAppOpenAndNavigate("app_open_auto")
        }

        binding.appOpenButton.setOnClickListener {
            showAppOpenAndNavigate("app_open_auto")
        }

        binding.customAppOpen.setOnClickListener {
            showInterstitialAndNavigate("custom_interstitial")
        }

        binding.syncNotification.setOnClickListener {
            ITWingSDK.syncNotificationToken("example-local-token", "itwing")
            toast("Fallback inbox token synced; FCM token sync is automatic")
        }
    }

    private fun renderSdkState() {
        binding.titleText.text = ITWingSDK.getAppTitle("ITWing SDK Example")
        binding.diagnosticsText.text = "Diagnostics: ${ITWingSDK.diagnostics()}"
        binding.apiText.text = buildString {
            appendLine("Logo: ${ITWingSDK.getLogoUri() ?: "not configured"}")
            appendLine("Primary color: ${ITWingSDK.getColor("primary", "#2563eb")}")
            appendLine("API proxy endpoint: ${ITWingSDK.getApiProxyEndpoint("exchange_rates", "not configured")}")
            appendLine("API proxy base URL: ${ITWingSDK.getApiProxyBaseUrl("exchange_rates", "not configured")}")
            append("Direct API base URL: ${ITWingSDK.getApiBaseUrl("exchange_rates", "not configured")}")
        }
        binding.firebaseText.text = "Firebase Auth available: ${ITWingSDK.firebaseAuth() != null}"
        binding.notificationText.text = "Billing: ${ITWingSDK.billingDiagnostics()} | Ad free: ${ITWingSDK.isAdFree()}"
    }

    private fun showInterstitialAndNavigate(placement: String) {
        ITWingSDK.analytics.track("example_interstitial_requested", mapOf("placement" to placement))
        ITWingSDK.showInterstitial(this, placement) {
            ITWingSDK.analytics.track("example_interstitial_callback", mapOf("placement" to placement))
            openResult("Interstitial: $placement")
        }
    }

    private fun showRewardedAndNavigate(placement: String) {
        var rewarded = false
        ITWingSDK.analytics.track("example_rewarded_requested", mapOf("placement" to placement))
        ITWingSDK.showRewarded(
            activity = this,
            placement = placement,
            onReward = {
                rewarded = true
                toast("Reward earned")
                ITWingSDK.analytics.track("example_reward_earned", mapOf("placement" to placement))
            },
            onComplete = {
                ITWingSDK.analytics.track("example_rewarded_callback", mapOf("placement" to placement, "rewarded" to rewarded))
                openResult("Rewarded: $placement", rewarded)
            },
        )
    }

    private fun showRewardedInterstitialAndNavigate(placement: String) {
        var rewarded = false
        ITWingSDK.analytics.track("example_rewarded_interstitial_requested", mapOf("placement" to placement))
        ITWingSDK.showRewardedInterstitial(
            activity = this,
            placement = placement,
            onReward = {
                rewarded = true
                toast("Reward earned")
                ITWingSDK.analytics.track("example_rewarded_interstitial_reward", mapOf("placement" to placement))
            },
            onComplete = {
                ITWingSDK.analytics.track("example_rewarded_interstitial_callback", mapOf("placement" to placement, "rewarded" to rewarded))
                openResult("Rewarded interstitial: $placement", rewarded)
            },
        )
    }

    private fun showAppOpenAndNavigate(placement: String) {
        ITWingSDK.analytics.track("example_app_open_requested", mapOf("placement" to placement))
        ITWingSDK.showAppOpen(this, placement) {
            ITWingSDK.analytics.track("example_app_open_callback", mapOf("placement" to placement))
            openResult("App open: $placement")
        }
    }

    private fun openResult(source: String, rewarded: Boolean = false) {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, ResultActivity::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
