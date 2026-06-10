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

        binding.resultTitle.text = "Callback navigation"
        binding.resultMessage.text = buildString {
            appendLine("Ad free: ${ITWingSDK.isAdFree()}")
        }
        binding.resultDiagnostics.text = ITWingSDK.diagnostics().toString()
        binding.backButton.setOnClickListener { finish() }
    }
}
