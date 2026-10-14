package com.itwingtech.itwingsdk.ui

import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable

/** Opaque SDK-card dialog window with ordinary dimming and no glass/blur layer. */
internal object SdkDialogWindow {
    private const val DIM_AMOUNT = 0.28f

    fun apply(
        window: Window?,
        width: Int,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    ) {
        if (window == null) return
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setLayout(width, height)
        window.setDimAmount(DIM_AMOUNT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}
