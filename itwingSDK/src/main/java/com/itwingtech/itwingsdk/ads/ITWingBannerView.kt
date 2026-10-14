package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import androidx.core.content.withStyledAttributes
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK

class ITWingBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(
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

    private var bannerLayoutWaiter: OnLayoutChangeListener? = null

    private var refreshRunnable: Runnable? = null

    private var lastAutoLoadAtMs =
        0L

    private val minAutoReloadMs =
        60_000L

    private val bannerContainer =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

    init { runCatching {
            setCardBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            cardElevation = 0f
            maxCardElevation = 0f
            useCompatPadding = false
            preventCornerOverlap = false
            setContentPadding(0, 0, 0, 0)

            addView(bannerContainer)

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
                            when (getInt(R.styleable.ITWingBannerView_ITWingBannerType, 0)) {
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

                    radius =
                        cornerRadius

                    foreground = if (strokeWidth > 0f && strokeColor != Color.TRANSPARENT) {
                        GradientDrawable().apply {
                            this.cornerRadius = cornerRadius
                            setColor(Color.TRANSPARENT)
                            setStroke(strokeWidth.toInt(), strokeColor)
                        }
                    } else {
                        null
                    }
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
                    waitForBannerContainerLayout(force)
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
                    scheduleConfiguredRefresh()
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

        cancelConfiguredRefresh()

        bannerLayoutWaiter?.let { listener ->
            bannerContainer.removeOnLayoutChangeListener(listener)
            bannerLayoutWaiter = null
        }

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
        } else if (visibility != VISIBLE) {
            cancelConfiguredRefresh()
        }
    }

    private fun scheduleConfiguredRefresh() {
        cancelConfiguredRefresh()

        if (isDestroyed || !isAttachedToWindow || !isVisibleForAdRequest()) {
            return
        }

        val seconds = ITWingSDK.ads.configuredBannerRefreshSeconds(placementName, bannerType) ?: return
        val delayMs = seconds.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS) * 1_000L
        val runnable = Runnable {
            refreshRunnable = null
            if (
                isDestroyed ||
                !isAttachedToWindow ||
                !isVisibleForAdRequest() ||
                context.findActivitySafe()?.isUsable() != true
            ) {
                return@Runnable
            }
            runCatching {
                ITWingSDK.ads.destroyBanner(bannerContainer)
                postLoadBanner(force = true)
            }
        }
        refreshRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelConfiguredRefresh() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
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
        return bannerContainer.width > 0
    }

    private fun waitForBannerContainerLayout(force: Boolean) {
        if (bannerLayoutWaiter != null || isDestroyed || !isAttachedToWindow) {
            return
        }

        val listener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (bannerContainer.width > 0) {
                bannerLayoutWaiter?.let { bannerContainer.removeOnLayoutChangeListener(it) }
                bannerLayoutWaiter = null
                postLoadBanner(force)
            }
        }
        bannerLayoutWaiter = listener
        bannerContainer.addOnLayoutChangeListener(listener)

        // A layout may already be pending without another size transition.
        bannerContainer.post {
            if (bannerContainer.width > 0) {
                bannerContainer.removeOnLayoutChangeListener(listener)
                if (bannerLayoutWaiter === listener) {
                    bannerLayoutWaiter = null
                }
                postLoadBanner(force)
            }
        }
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

    private companion object {
        const val MIN_REFRESH_SECONDS = 30L
        const val MAX_REFRESH_SECONDS = 86_400L
    }

}
