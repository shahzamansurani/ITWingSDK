package com.itwingtech.itwingsdk.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.core.ITWingSDK

/** Shared semantic surface treatment for SDK-managed action-style dialogs. */
internal object SdkDialogTheme {
    fun apply(content: View, context: Context) {
        val surface = color(
            context,
            "surface_color",
            ContextCompat.getColor(context, com.itwingtech.itwingsdk.R.color.itwing_sdk_surface),
        )
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
