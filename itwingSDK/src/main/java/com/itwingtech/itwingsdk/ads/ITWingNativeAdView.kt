package com.itwingtech.itwingsdk.ads

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity.CENTER
import android.widget.RelativeLayout
import androidx.core.content.withStyledAttributes
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK

class ITWingNativeAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {

    var placementName = "default_native"

    /*
     * NULL = use admin panel config
     * NON-NULL = force override
     */
    var nativeType: NativeType? = null

    init {
        shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(0f).build()
        setCardBackgroundColor(Color.TRANSPARENT)
        val layout = RelativeLayout(context).apply {
            gravity = CENTER
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        addView(layout)
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.ITWingNativeAdView) {
                placementName =
                    getString(R.styleable.ITWingNativeAdView_ITWingNativeAdViewPlacement)
                        ?: placementName
                if (hasValue(R.styleable.ITWingNativeAdView_ITWingNativeAdViewType)) {
                    nativeType =
                        when (getInt(R.styleable.ITWingNativeAdView_ITWingNativeAdViewType, 0)) {
                            1 -> NativeType.LARGE
                            else -> NativeType.SMALL
                        }
                }

                val backgroundColor = getColor(R.styleable.ITWingNativeAdView_ITWingNativeAdViewBackgroundColor, Color.TRANSPARENT)
                val strokeColor = getColor(R.styleable.ITWingNativeAdView_ITWingNativeAdViewStrokeColor, Color.GRAY)
                val strokeWidth = getDimension(R.styleable.ITWingNativeAdView_ITWingNativeAdViewStrokeWidth, 1f)
                val cornerRadius = getDimension(R.styleable.ITWingNativeAdView_ITWingNativeAdViewCornerRadius, 0f)
                setCardBackgroundColor(backgroundColor)
                setStrokeColor(strokeColor)
                radius = cornerRadius
                setStrokeWidth(strokeWidth.toInt())
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeightLarge = resources.getDimension(com.intuit.sdp.R.dimen._240sdp).toInt()
        val defaultHeightSmall = resources.getDimension(com.intuit.sdp.R.dimen._150sdp).toInt()
        val padding = resources.getDimension(com.intuit.sdp.R.dimen._2sdp).toInt()
        val resolvedType = nativeType ?: NativeType.LARGE
        val desiredHeight = when (resolvedType) {
            NativeType.LARGE -> (defaultHeightLarge + padding)
            NativeType.SMALL -> (defaultHeightSmall + padding)
        }
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val customHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.AT_MOST)
        val customWidthMeasureSpec = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY)
        super.onMeasure(customWidthMeasureSpec, customHeightMeasureSpec)
        setPadding(padding, padding, padding, padding)


    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadAd()
    }

    fun loadAd() {
        val activity = context.findActivity() ?: return
        runCatching {
            ITWingSDK.ads.loadNative(
                activity = activity,
                container = this,
                placement = placementName,
                nativeType = nativeType,
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { ITWingSDK.ads.destroyNative(this) }
    }
}
