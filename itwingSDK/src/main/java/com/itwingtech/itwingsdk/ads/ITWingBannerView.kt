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

class ITWingBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    var placementName: String = "default_banner"
    var bannerType: BannerType? = null

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
            context.withStyledAttributes(it, R.styleable.ITWingBannerView) {
                placementName =
                    getString(R.styleable.ITWingBannerView_ITWingBannerPlacement) ?: placementName
                if (hasValue(R.styleable.ITWingBannerView_ITWingBannerType)) {
                    bannerType = when (getInt(R.styleable.ITWingBannerView_ITWingBannerType, 0)) {
                        1 -> BannerType.COLLAPSIBLE_TOP
                        2 -> BannerType.COLLAPSIBLE_BOTTOM
                        else -> BannerType.ADAPTIVE
                    }
                }

                val backgroundColor = getColor(
                    R.styleable.ITWingBannerView_ITWingBannerBackgroundColor,
                    Color.TRANSPARENT
                )
                val strokeColor =
                    getColor(R.styleable.ITWingBannerView_ITWingBannerStrokeColor, Color.GRAY)
                val strokeWidth =
                    getDimension(R.styleable.ITWingBannerView_ITWingBannerStrokeWidth, 1f)
                val cornerRadius =
                    getDimension(R.styleable.ITWingBannerView_ITWingBannerCornerRadius, 0f)
                setCardBackgroundColor(backgroundColor)
                setStrokeColor(strokeColor)
                radius = cornerRadius
                setStrokeWidth(strokeWidth.toInt())
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeightPx = resources.getDimension(com.intuit.sdp.R.dimen._60sdp).toInt()
        val padding = resources.getDimension(com.intuit.sdp.R.dimen._3sdp).toInt()
        val desiredHeight = defaultHeightPx + padding
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val customHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.AT_MOST)
        val customWidthMeasureSpec = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY)
        super.onMeasure(customWidthMeasureSpec, customHeightMeasureSpec)
        setPadding(padding, padding, padding, padding)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadBanner()
    }

    fun loadBanner() {
        val activity = context.findActivity() ?: return
        runCatching {
            ITWingSDK.ads.loadBanner(
                activity = activity,
                container = this,
                placement = placementName,
                bannerType = bannerType,
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { ITWingSDK.ads.destroyBanner(this) }
    }
}
