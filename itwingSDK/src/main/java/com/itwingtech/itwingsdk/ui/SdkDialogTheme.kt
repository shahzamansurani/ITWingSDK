package com.itwingtech.itwingsdk.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.core.ITWingSDK

/** Shared semantic surface treatment for SDK-managed action-style dialogs. */
internal object SdkDialogTheme {
    fun apply(content: View, context: Context) {
        val surface = surfaceColor(context)
        val stroke = color(
            context,
            "stroke_color",
            ContextCompat.getColor(context, com.itwingtech.itwingsdk.R.color.itwing_sdk_stroke),
        )
        (content as? MaterialCardView)?.apply {
            setCardBackgroundColor(surface)
            setStrokeColor(stroke)
        }
    }

    fun primaryColor(context: Context): Int = color(
        context,
        "primary",
        ContextCompat.getColor(context, com.itwingtech.itwingsdk.R.color.colorPrimary),
    )

    fun color(context: Context, role: String, fallback: Int): Int {
        val resolved = runCatching {
            Color.parseColor(
                ITWingSDK.getSemanticColor(
                    name = role,
                    defaultValue = "#%08X".format(fallback),
                    context = context,
                ),
            )
        }.getOrDefault(fallback)

        // Dialog surfaces are intentionally opaque. A legacy/admin value such
        // as #80000000 must not turn the SDK card into the old glass dialog.
        if (role == "surface_color") {
            return if (Color.alpha(resolved) == 0xFF) resolved else fallback
        }

        // Keep host/admin theming, but protect the approved light-card layout
        // from white/transparent text colors that become invisible on it.
        if (isDialogForegroundRole(role)) {
            val surface = surfaceColor(context, emitTrace = false)
            val contrast = runCatching {
                ColorUtils.calculateContrast(resolved, surface)
            }.getOrDefault(0.0)
            if (Color.alpha(resolved) < 0xFF || contrast < 3.0) return fallback
        }
        return resolved
    }

    private fun surfaceColor(context: Context, emitTrace: Boolean = true): Int {
        val fallback = ContextCompat.getColor(context, com.itwingtech.itwingsdk.R.color.itwing_sdk_surface)
        val configured = runCatching {
            Color.parseColor(
                ITWingSDK.getSemanticColor(
                    name = "surface_color",
                    defaultValue = "#%08X".format(fallback),
                    context = if (emitTrace) context else null,
                ),
            )
        }.getOrDefault(fallback)
        return if (Color.alpha(configured) == 0xFF) configured else fallback
    }

    private fun isDialogForegroundRole(role: String): Boolean {
        val normalized = role.trim().lowercase()
        return normalized.contains("title") ||
            normalized.contains("description") ||
            normalized.contains("text") ||
            normalized.contains("icon") ||
            normalized.contains("close")
    }
}
