package com.itwingtech.itwingsdk.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.example.databinding.ActivityMainBinding
import kotlin.getValue

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        bindSdkExamples()
        renderSdkState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                ITWingSDK.showActionDialog(
                    activity = this@MainActivity,
                    title = getString(R.string.example_exit_dialog_title),
                    description = getString(R.string.example_exit_dialog_description),
                    positiveText = getString(R.string.example_exit_dialog_positive),
                    negativeText = getString(R.string.example_cancel),
                    onPositive = { finishAffinity() },
                )
            }
        })
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

        binding.openWallpaperDemo.setOnClickListener {
            startActivity(Intent(this, WallpaperActivity::class.java))
        }

        binding.openMediaDemo.setOnClickListener {
            startActivity(Intent(this, MediaLibraryActivity::class.java))
        }

        binding.openTopCollapsibleDemo.setOnClickListener {
            openEdgeDemo(EdgeBannerActivity.EDGE_TOP)
        }

        binding.openBottomCollapsibleDemo.setOnClickListener {
            openEdgeDemo(EdgeBannerActivity.EDGE_BOTTOM)
        }

        binding.preloadAds.setOnClickListener {
            ITWingSDK.ads.preloadAll(this)
            toast("Fullscreen ads preload requested")
        }

        binding.realInterstitial.setOnClickListener {
            showInterstitialAndNavigate("interstitial")
        }

        binding.nativeRecovery.setOnClickListener {
            showInterstitialAndNavigate("interstitial", "Interstitial → Native recovery", ResultActivity.RECOVERY_NATIVE)
        }

        binding.bannerRecovery.setOnClickListener {
            showInterstitialAndNavigate("interstitial", "Interstitial → Banner recovery", ResultActivity.RECOVERY_BANNER)
        }

        binding.showExitDialog.setOnClickListener {
            ITWingSDK.showActionDialog(
                activity = this,
                title = getString(R.string.example_exit_dialog_title),
                description = getString(R.string.example_exit_dialog_description),
                positiveText = getString(R.string.example_exit_dialog_positive),
                negativeText = getString(R.string.example_cancel),
                onPositive = { finishAffinity() },
            )
        }

        binding.showFeedbackDialog.setOnClickListener {
            ITWingSDK.showActionDialog(
                activity = this,
                title = getString(R.string.example_feedback_dialog_title),
                description = getString(R.string.example_feedback_dialog_description),
                positiveText = getString(R.string.example_feedback_dialog_positive),
                negativeText = getString(R.string.example_cancel),
                onPositive = { toast(getString(R.string.example_feedback_selected)) },
            )
        }

        binding.showLoadingDialog.setOnClickListener {
            val loadingDialog = ITWingSDK.showLoadingDialog(this)
            binding.root.postDelayed({ loadingDialog.dismiss() }, 1500L)
        }

        // Example-only reset so the persistent first-time rating flow can be
        // exercised repeatedly without exposing a production SDK reset API.
        binding.resetExitRatingState.setOnClickListener {
            getSharedPreferences("itwing_action_dialog", MODE_PRIVATE)
                .edit()
                .remove("review_feedback_submitted_${packageName}")
                .apply()
            toast(getString(R.string.example_exit_rating_state_reset))
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
            showAppOpenAndNavigate("app_open_auto")
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
            appendLine(
                "API proxy endpoint: ${
                    ITWingSDK.getApiProxyEndpoint(
                        "exchange_rates",
                        "not configured"
                    )
                }"
            )
            appendLine(
                "API proxy base URL: ${
                    ITWingSDK.getApiProxyBaseUrl(
                        "exchange_rates",
                        "not configured"
                    )
                }"
            )
            append(
                "Direct API base URL: ${
                    ITWingSDK.getApiBaseUrl(
                        "exchange_rates",
                        "not configured"
                    )
                }"
            )
        }
        binding.firebaseText.text =
            "FCM push bridge: configured from admin; no host google-services plugin required"
        binding.notificationText.text =
            "Billing: ${ITWingSDK.billingDiagnostics()} | Ad free: ${ITWingSDK.isAdFree()}"
    }

    private fun showInterstitialAndNavigate(
        placement: String,
        title: String = "Interstitial: $placement",
        recovery: String? = null,
    ) {
        ITWingSDK.showInterstitial(this, placement) {
            openResult(title, recovery = recovery)
        }
    }

    private fun showRewardedAndNavigate(placement: String) {
        var rewarded = false
        ITWingSDK.showRewarded(
            activity = this,
            placement = placement,
            onReward = {
                rewarded = true
                toast("Reward earned")
            },
            onComplete = {
                openResult("Rewarded: $placement", rewarded)
            },
        )
    }

    private fun showRewardedInterstitialAndNavigate(placement: String) {
        var rewarded = false
        ITWingSDK.showRewardedInterstitial(
            activity = this,
            placement = placement,
            onReward = {
                rewarded = true
                toast("Reward earned")
            },
            onComplete = {
                openResult("Rewarded interstitial: $placement", rewarded)
            },
        )
    }

    private fun showAppOpenAndNavigate(placement: String) {
        ITWingSDK.showAppOpen(this, placement) {
            openResult("App open: $placement")
        }
    }

    private fun openResult(source: String, rewarded: Boolean = false, recovery: String? = null) {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_TITLE, source)
            putExtra(ResultActivity.EXTRA_REWARD_EARNED, rewarded)
            recovery?.let { putExtra(ResultActivity.EXTRA_RECOVERY, it) }
        })
    }

    private fun openEdgeDemo(edge: String) {
        startActivity(Intent(this, EdgeBannerActivity::class.java).apply {
            putExtra(EdgeBannerActivity.EXTRA_EDGE, edge)
        })
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
