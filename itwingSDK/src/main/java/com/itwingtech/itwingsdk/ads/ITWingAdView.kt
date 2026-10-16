package com.itwingtech.itwingsdk.ads

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.withStyledAttributes
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Universal XML ad slot. Hosts provide a placement name; the current SDK
 * configuration selects the safe inline renderer and can change it remotely.
 */
class ITWingAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val bannerRenderer: ITWingBannerView?
    private val nativeRenderer: ITWingNativeAdView?
    private var initialized = false
    private var activeRenderer: View? = null
    private var activeKey: String? = null
    private var refreshPosted = false

    var placementName: String = ""
        set(value) {
            field = value.trim()
            scheduleRefresh()
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_itwing_ad, this, true)
        // A collapsible banner may grow beyond its compact request height.
        // Keep this scoped to the universal SDK host; it does not alter the
        // approved card surface or the selected renderer's own sizing.
        clipChildren = false
        clipToPadding = false
        bannerRenderer = findViewById<View>(R.id.itwing_universal_banner) as? ITWingBannerView
        nativeRenderer = findViewById<View>(R.id.itwing_universal_native) as? ITWingNativeAdView
        context.withStyledAttributes(attrs, R.styleable.ITWingAdView, defStyleAttr, 0) {
            placementName = getString(R.styleable.ITWingAdView_ITWingAdViewPlacement).orEmpty()
        }
        initialized = true
        instances += WeakReference(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        refreshPosted = false
        activeRenderer = null
        activeKey = null
        // The XML contains both renderer children initially. Detach both
        // before re-adding the selected renderer, including the first bind.
        runCatching { removeRenderers() }
        super.onDetachedFromWindow()
    }

    /** Refreshes the renderer after an app/config change. */
    fun refresh() {
        if (!initialized || !isAttachedToWindow) return
        runCatching { refreshInternal() }
            .onFailure { failClosed() }
    }

    private fun refreshInternal() {
        val config = ITWingSDK.currentConfig()
        val placement = ITWingAdPlacementSelector.select(config.ads.placements, placementName)
        val format = PlacementFormatResolver.inlineFormat(placement?.format)
        val key = placement?.let {
            // A config version change alone does not change this slot. Keep
            // the renderer stable until an actual placement property changes.
            "$format|$it"
        }
        if (placement == null || !config.ads.globalEnabled) {
            failClosed()
            activeKey = key
            return
        }
        if (key == activeKey && activeRenderer != null) return

        removeRenderers()
        val renderer: View = if (format == "banner") {
            val banner = bannerRenderer ?: return failClosed()
            banner.placementName = placement.name
            banner.bannerType = BannerType.fromMetadata(placement.metadata)
            banner
        } else {
            val native = nativeRenderer ?: return failClosed()
            native.placementName = placement.name
            native.nativeType = null
            native
        }
        renderer.layoutParams = renderer.layoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        renderer.visibility = VISIBLE
        addView(renderer)
        activeRenderer = renderer
        activeKey = key
    }

    private fun failClosed() {
        activeRenderer = null
        activeKey = null
        runCatching { removeRenderers() }
    }

    private fun scheduleRefresh() {
        if (!initialized || !isAttachedToWindow || refreshPosted) return
        refreshPosted = true
        runCatching {
            post {
                refreshPosted = false
                if (initialized && isAttachedToWindow) refresh()
            }
        }.onFailure {
            refreshPosted = false
            failClosed()
        }
    }

    private fun removeRenderers() {
        if (bannerRenderer?.parent === this) {
            removeView(bannerRenderer)
        }
        if (nativeRenderer?.parent === this) {
            removeView(nativeRenderer)
        }
    }

    companion object {
        private val instances = CopyOnWriteArrayList<WeakReference<ITWingAdView>>()

        internal fun notifyConfigChanged() {
            instances.removeAll { reference ->
                val view = reference.get()
                if (view == null) true else {
                    view.scheduleRefresh()
                    false
                }
            }
        }
    }
}
