package com.itwingtech.itwingsdk.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.example.databinding.ActivityResultBinding

class ResultActivity : AppCompatActivity() {
    private val binding by lazy { ActivityResultBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.resultTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Callback navigation"
        binding.resultMessage.text = buildString {
            appendLine("Ad free: ${ITWingSDK.isAdFree()}")
            if (intent.getStringExtra(EXTRA_RECOVERY) != null) {
                appendLine("Touch anywhere once to release the post-interstitial inline ad recovery.")
            }
            if (intent.getBooleanExtra(EXTRA_REWARD_EARNED, false)) {
                appendLine("Reward earned")
            }
        }
        binding.resultDiagnostics.text = ITWingSDK.diagnostics().toString()
        when (intent.getStringExtra(EXTRA_RECOVERY)) {
            RECOVERY_NATIVE -> layoutInflater.inflate(
                R.layout.view_recovery_native,
                binding.recoveryContainer,
                true,
            )
            RECOVERY_BANNER -> layoutInflater.inflate(
                R.layout.view_recovery_banner,
                binding.recoveryContainer,
                true,
            )
        }
        binding.backButton.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_TITLE = "result_title"
        const val EXTRA_REWARD_EARNED = "reward_earned"
        const val EXTRA_RECOVERY = "recovery"
        const val RECOVERY_NATIVE = "native"
        const val RECOVERY_BANNER = "banner"
    }
}
