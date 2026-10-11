package com.itwingtech.itwingsdk.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.core.ITWingSDK

/** Shared semantic surface treatment for SDK-managed action-style dialogs. */
internal object SdkDialogTheme {
    fun apply(content: View, context: Context) {
        val surface = color(context, "dialog_background_color", Color.parseColor("#D0111827"))
        val stroke = color(context, "stroke_color", Color.parseColor("#55FFFFFF"))
        (content as? MaterialCardView)?.apply {
            setCardBackgroundColor(surface)
            setStrokeColor(stroke)
        }
    }

    fun color(context: Context, role: String, fallback: Int): Int =
        runCatching {
            Color.parseColor(
                ITWingSDK.getSemanticColor(
                    name = role,
                    defaultValue = "#%08X".format(fallback),
                    context = context,
                ),
            )
        }.getOrDefault(fallback)
}
