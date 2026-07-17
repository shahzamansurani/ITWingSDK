package com.itwingtech.itwingsdk.ui

import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable

internal object GlassDialogWindow {
    private const val BLUR_RADIUS_PX = 82
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            runCatching {
                Window::class.java
                    .getMethod("setBackgroundBlurRadius", Int::class.javaPrimitiveType)
                    .invoke(window, BLUR_RADIUS_PX)
            }
            runCatching {
                val attributes = window.attributes
                attributes.javaClass
                    .getField("blurBehindRadius")
                    .setInt(attributes, BLUR_RADIUS_PX)
                window.attributes = attributes
            }
        }
    }
}
