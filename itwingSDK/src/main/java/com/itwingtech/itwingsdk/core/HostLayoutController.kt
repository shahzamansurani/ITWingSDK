package com.itwingtech.itwingsdk.core

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

internal object HostLayoutController {
    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val initialPaddings = WeakHashMap<View, InitialPadding>()

    fun apply(
        activity: Activity,
        primaryColor: Int,
        applyContentInsets: Boolean,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val window = activity.window ?: return
        val lightStatusIcons = ColorUtils.calculateLuminance(primaryColor) > 0.58
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusIcons
            isAppearanceLightNavigationBars = true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = primaryColor
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, !applyContentInsets)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.setBackgroundColor(primaryColor)

        if (!applyContentInsets) return

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val initial = synchronized(initialPaddings) {
            initialPaddings.getOrPut(content) {
                InitialPadding(
                    left = content.paddingLeft,
                    top = content.paddingTop,
                    right = content.paddingRight,
                    bottom = content.paddingBottom,
                )
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initial.left + systemBars.left,
                initial.top + systemBars.top,
                initial.right + systemBars.right,
                initial.bottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
