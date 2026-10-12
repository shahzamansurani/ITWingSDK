package com.itwingtech.itwingsdk.ads

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
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
    private val bannerRenderer: ITWingBannerView
    private val nativeRenderer: ITWingNativeAdView
    private var initialized = false
    private var activeRenderer: android.view.View? = null
    private var activeKey: String? = null

    var placementName: String = ""
        set(value) {
            field = value.trim()
            if (initialized && isAttachedToWindow) post { refresh() }
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_itwing_ad, this, true)
        bannerRenderer = findViewById(R.id.itwing_universal_banner)
        nativeRenderer = findViewById(R.id.itwing_universal_native)
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
        activeRenderer = null
        activeKey = null
        // The XML contains both renderer children initially. Detach both
        // before re-adding the selected renderer, including the first bind.
        removeRenderers()
        super.onDetachedFromWindow()
    }

    /** Refreshes the renderer after an app/config change. */
    fun refresh() {
        if (!initialized || !isAttachedToWindow) return
        val placement = ITWingSDK.currentConfig().ads.placements.firstOrNull {
            it.name == placementName && it.enabled
        }
        val format = PlacementFormatResolver.inlineFormat(placement?.format)
        val key = placement?.let {
            // A config version change alone does not change this slot. Keep
            // the renderer stable until an actual placement property changes.
            "$format|$it"
        }
        if (placement == null || !ITWingSDK.currentConfig().ads.globalEnabled) {
            if (activeRenderer != null) {
                removeRenderers()
                activeRenderer = null
            }
            activeKey = key
            return
        }
        if (key == activeKey && activeRenderer != null) return

        removeRenderers()
        val renderer = if (format == "banner") {
            bannerRenderer.apply {
                placementName = placement.name
                bannerType = BannerType.fromMetadata(placement.metadata)
            }
        } else {
            nativeRenderer.apply {
                placementName = placement.name
                nativeType = null
            }
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

    private fun removeRenderers() {
        if (bannerRenderer.parent === this) removeView(bannerRenderer)
        if (nativeRenderer.parent === this) removeView(nativeRenderer)
    }

    companion object {
        private val instances = CopyOnWriteArrayList<WeakReference<ITWingAdView>>()

        internal fun notifyConfigChanged() {
            instances.removeAll { reference ->
                val view = reference.get()
                if (view == null) true else {
                    view.post { view.refresh() }
                    false
                }
            }
        }
    }
}
