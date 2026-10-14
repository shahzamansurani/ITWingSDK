package com.itwingtech.itwingsdk.example

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.itwingtech.itwingsdk.example.databinding.ActivityCollapsibleBottomBinding
import com.itwingtech.itwingsdk.example.databinding.ActivityCollapsibleTopBinding

class EdgeBannerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(EXTRA_EDGE) == EDGE_BOTTOM) {
            val binding = ActivityCollapsibleBottomBinding.inflate(layoutInflater)
            setContentView(binding.root)
            bind(binding.edgeTitle, binding.edgeBack, "Bottom-edge collapsible demo")
        } else {
            val binding = ActivityCollapsibleTopBinding.inflate(layoutInflater)
            setContentView(binding.root)
            bind(binding.edgeTitle, binding.edgeBack, "Top-edge collapsible demo")
        }
    }

    private fun bind(title: TextView, back: TextView, value: String) {
        title.text = value
        back.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_EDGE = "edge"
        const val EDGE_TOP = "top"
        const val EDGE_BOTTOM = "bottom"
    }
}
