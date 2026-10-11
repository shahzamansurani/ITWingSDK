package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity.CENTER
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.content.withStyledAttributes
import com.google.android.material.card.MaterialCardView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK

class ITWingBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(
    context,
    attrs,
    defStyleAttr
) {
    var placementName: String =
        "default_banner"

    var bannerType: BannerType? =
        null

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var isDestroyed =
        false

    private var isLoadPosted =
        false

    private var readyRetryRegistered =
        false

    private var lastAutoLoadAtMs =
        0L

    private val minAutoReloadMs =
        60_000L

    private val bannerContainer =
        RelativeLayout(context).apply {

            gravity =
                CENTER

            setBackgroundColor(
                Color.TRANSPARENT
            )

            layoutParams =
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
        }

    init {
        runCatching {

            shapeAppearanceModel =
                shapeAppearanceModel
                    .toBuilder()
                    .setAllCornerSizes(0f)
                    .build()

            setCardBackgroundColor(
                Color.TRANSPARENT
            )

            cardElevation = 0f
            maxCardElevation = 0f
            useCompatPadding = false
            preventCornerOverlap = false
            setContentPadding(0, 0, 0, 0)

            addView(
                bannerContainer
            )

            attrs?.let {

                context.withStyledAttributes(
                    it,
                    R.styleable.ITWingBannerView
                ) {

                    placementName =
                        getString(
                            R.styleable.ITWingBannerView_ITWingBannerPlacement
                        ) ?: placementName

                    if (
                        hasValue(
                            R.styleable.ITWingBannerView_ITWingBannerType
                        )
                    ) {

                        bannerType =
                            when (
                                getInt(
                                    R.styleable.ITWingBannerView_ITWingBannerType,
                                    0
                                )
                            ) {

                                1 ->
                                    BannerType.COLLAPSIBLE_TOP

                                2 ->
                                    BannerType.COLLAPSIBLE_BOTTOM

                                else ->
                                    BannerType.ADAPTIVE
                            }
                    }

                    val backgroundColor =
                        getColor(
                            R.styleable.ITWingBannerView_ITWingBannerBackgroundColor,
                            Color.TRANSPARENT
                        )

                    val strokeColor =
                        getColor(
                            R.styleable.ITWingBannerView_ITWingBannerStrokeColor,
                            Color.TRANSPARENT
                        )

                    val strokeWidth =
                        getDimension(
                            R.styleable.ITWingBannerView_ITWingBannerStrokeWidth,
                            0f
                        )

                    val cornerRadius =
                        getDimension(
                            R.styleable.ITWingBannerView_ITWingBannerCornerRadius,
                            0f
                        )

                    setCardBackgroundColor(
                        backgroundColor
                    )

                    setStrokeColor(
                        strokeColor
                    )

                    radius =
                        cornerRadius

                    setStrokeWidth(
                        strokeWidth.toInt()
                    )
                }
            }
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        runCatching {
            setPadding(0, 0, 0, 0)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        isDestroyed =
            false

        postLoadBanner(force = false)
    }

    fun loadBanner() {
        postLoadBanner(force = true)
    }

    private fun postLoadBanner(force: Boolean = false) {
        runOnMain {

            if (
                isDestroyed ||
                !isAttachedToWindow ||
                isLoadPosted
            ) {
                return@runOnMain
            }

            isLoadPosted =
                true

            post {

                isLoadPosted =
                    false

                if (
                    isDestroyed ||
                    !isAttachedToWindow
                ) {
                    return@post
                }

                if (!isVisibleForAdRequest()) {
                    return@post
                }

                if (!isMeasuredForAdRequest()) {
                    postDelayed({ postLoadBanner(force) }, 150)
                    return@post
                }

                if (!ITWingSDK.areInlineAdsReady()) {
                    registerReadyRetry(force)
                    return@post
                }

                val activity =
                    context.findActivitySafe()
                        ?: return@post

                if (!activity.isUsable()) {
                    return@post
                }

                if (!force && !canAutoLoad()) {
                    return@post
                }

                runCatching {

                    ITWingSDK.ads.loadBanner(
                        activity = activity,
                        container = bannerContainer,
                        placement = placementName,
                        bannerType = bannerType
                    )
                }
            }
        }
    }

    private fun registerReadyRetry(force: Boolean) {
        if (readyRetryRegistered) {
            return
        }

        readyRetryRegistered = true
        ITWingSDK.onInlineAdsReady { ready ->
            readyRetryRegistered = false
            if (ready && !isDestroyed && isAttachedToWindow) {
                postDelayed({ postLoadBanner(force = force) }, 100)
            }
        }
    }

    override fun onDetachedFromWindow() {
        isDestroyed =
            true

        runCatching {

            ITWingSDK.ads.destroyBanner(
                bannerContainer
            )
        }

        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(
        visibility: Int
    ) {
        super.onWindowVisibilityChanged(
            visibility
        )

        if (
            visibility == VISIBLE &&
            isAttachedToWindow &&
            !isDestroyed
        ) {
            postLoadBanner(force = false)
        }
    }

    private fun canAutoLoad(): Boolean {
        if (!ITWingSDK.isReady()) {
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoLoadAtMs < minAutoReloadMs) {
            return false
        }
        lastAutoLoadAtMs = now
        return true
    }

    private fun isVisibleForAdRequest(): Boolean {
        return isShown &&
            windowVisibility == VISIBLE
    }

    private fun isMeasuredForAdRequest(): Boolean {
        return width > 0
    }

    private fun runOnMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {

            block()

        } else {

            mainHandler.post {

                block()
            }
        }
    }

    private fun Context.findActivitySafe(): Activity? {
        var currentContext =
            this

        while (
            currentContext is ContextWrapper
        ) {

            if (
                currentContext is Activity
            ) {
                return currentContext
            }

            currentContext =
                currentContext.baseContext
        }

        return null
    }

    private fun Activity.isUsable(): Boolean {
        return !isFinishing &&
                !isDestroyed
    }
}

//package com.itwingtech.itwingsdk.ads
//
//import android.content.Context
//import android.graphics.Color
//import android.util.AttributeSet
//import android.view.Gravity.CENTER
//import android.widget.RelativeLayout
//import androidx.core.content.withStyledAttributes
//import com.google.android.material.card.MaterialCardView
//import com.itwingtech.itwingsdk.R
//import com.itwingtech.itwingsdk.core.ITWingSDK
//
//class ITWingBannerView @JvmOverloads constructor(
//    context: Context,
//    attrs: AttributeSet? = null,
//    defStyleAttr: Int = 0,
//) : MaterialCardView(context, attrs, defStyleAttr) {
//    var placementName: String = "default_banner"
//    var bannerType: BannerType? = null
//
//    init {
//        shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(0f).build()
//        setCardBackgroundColor(Color.TRANSPARENT)
//        val layout = RelativeLayout(context).apply {
//            gravity = CENTER
//            setBackgroundColor(Color.TRANSPARENT)
//            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
//        }
//
//        addView(layout)
//        attrs?.let {
//            context.withStyledAttributes(it, R.styleable.ITWingBannerView) {
//                placementName =
//                    getString(R.styleable.ITWingBannerView_ITWingBannerPlacement) ?: placementName
//                if (hasValue(R.styleable.ITWingBannerView_ITWingBannerType)) {
//                    bannerType = when (getInt(R.styleable.ITWingBannerView_ITWingBannerType, 0)) {
//                        1 -> BannerType.COLLAPSIBLE_TOP
//                        2 -> BannerType.COLLAPSIBLE_BOTTOM
//                        else -> BannerType.ADAPTIVE
//                    }
//                }
//
//                val backgroundColor = getColor(
//                    R.styleable.ITWingBannerView_ITWingBannerBackgroundColor,
//                    Color.TRANSPARENT
//                )
//                val strokeColor =
//                    getColor(R.styleable.ITWingBannerView_ITWingBannerStrokeColor, Color.GRAY)
//                val strokeWidth =
//                    getDimension(R.styleable.ITWingBannerView_ITWingBannerStrokeWidth, 1f)
//                val cornerRadius =
//                    getDimension(R.styleable.ITWingBannerView_ITWingBannerCornerRadius, 0f)
//                setCardBackgroundColor(backgroundColor)
//                setStrokeColor(strokeColor)
//                radius = cornerRadius
//                setStrokeWidth(strokeWidth.toInt())
//            }
//        }
//    }
//
//    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
//        val defaultHeightPx = resources.getDimension(com.intuit.sdp.R.dimen._60sdp).toInt()
//        val padding = resources.getDimension(com.intuit.sdp.R.dimen._3sdp).toInt()
//        val desiredHeight = defaultHeightPx + padding
//        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
//        val customHeightMeasureSpec =
//            MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.AT_MOST)
//        val customWidthMeasureSpec = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY)
//        super.onMeasure(customWidthMeasureSpec, customHeightMeasureSpec)
//        setPadding(padding, padding, padding, padding)
//    }
//
//    override fun onAttachedToWindow() {
//        super.onAttachedToWindow()
//        loadBanner()
//    }
//
//    fun loadBanner() {
//        val activity = context.findActivity() ?: return
//        runCatching {
//            ITWingSDK.ads.loadBanner(
//                activity = activity,
//                container = this,
//                placement = placementName,
//                bannerType = bannerType,
//            )
//        }
//    }
//
//    override fun onDetachedFromWindow() {
//        super.onDetachedFromWindow()
//        runCatching { ITWingSDK.ads.destroyBanner(this) }
//    }
//}
