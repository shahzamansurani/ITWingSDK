package com.itwingtech.itwingsdk.utils

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK

internal object SDKUi {
    fun string(context: Context, @StringRes resId: Int): String = context.getString(resId)

    fun color(context: Context, @ColorRes resId: Int): Int = ContextCompat.getColor(context, resId)

    fun primaryColor(): Int = ITWingSDK.sdkPrimaryColorInt()

    fun shimmerBaseColor(context: Context): Int = primaryColor().withAlpha(36)

    fun shimmerHighlightColor(context: Context): Int {
        val base = color(context, R.color.itwing_sdk_shimmer_highlight)
        return blend(base, primaryColor().withAlpha(28))
    }

    fun mutedTextColor(context: Context): Int = color(context, R.color.itwing_sdk_text_muted)

    fun primaryTextColor(context: Context): Int = color(context, R.color.itwing_sdk_text_primary)

    fun surfaceColor(context: Context): Int = color(context, R.color.itwing_sdk_surface)

    fun strokeColor(context: Context): Int = color(context, R.color.itwing_sdk_stroke)
}

internal fun Int.withAlpha(alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (this and 0x00FFFFFF)

private fun blend(first: Int, second: Int): Int {
    val alpha = second ushr 24
    if (alpha <= 0) return first
    val inverse = 255 - alpha
    val red = (((first shr 16) and 0xFF) * inverse + ((second shr 16) and 0xFF) * alpha) / 255
    val green = (((first shr 8) and 0xFF) * inverse + ((second shr 8) and 0xFF) * alpha) / 255
    val blue = ((first and 0xFF) * inverse + (second and 0xFF) * alpha) / 255
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
