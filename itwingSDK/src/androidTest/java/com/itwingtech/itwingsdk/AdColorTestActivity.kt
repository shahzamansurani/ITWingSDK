package com.itwingtech.itwingsdk

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

class AdColorTestActivity : Activity() {
    lateinit var adContainer: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adContainer = FrameLayout(this)
        setContentView(adContainer)
    }
}
