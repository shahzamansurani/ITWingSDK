package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.ITWingSDK

data class ITWingRecyclerAdOptions(
    val enabled: Boolean = true,
    val interval: Int = 6,
    val startAfter: Int = 4,
    val maxAds: Int = 6,
    val adHeightDp: Int? = null,
)

class ITWingRecyclerAdAdapter private constructor(
    private val activity: Activity,
    private val recyclerView: RecyclerView,
    private val contentAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
    private val placementName: String,
    private val options: ITWingRecyclerAdOptions,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = notifyDataSetChanged()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = notifyDataSetChanged()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = notifyDataSetChanged()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = notifyDataSetChanged()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = notifyDataSetChanged()
    }

    private val placement: AdPlacementConfig?
        get() = ITWingSDK.currentConfig().ads.placements.firstOrNull { it.name == placementName && it.enabled }

    private val effectiveOptions: ITWingRecyclerAdOptions
        get() {
            val metadata = placement?.metadata.orEmpty()
            return ITWingRecyclerAdOptions(
                enabled = metadata.bool("recycler_enabled", options.enabled),
                interval = metadata.int("recycler_interval", options.interval).coerceAtLeast(1),
                startAfter = metadata.int("recycler_start_after", options.startAfter).coerceAtLeast(0),
                maxAds = metadata.int("recycler_max_ads", options.maxAds).coerceAtLeast(0),
                adHeightDp = metadata.intOrNull("recycler_ad_height_dp") ?: options.adHeightDp,
            )
        }

    init {
        setHasStableIds(false)
        contentAdapter.registerAdapterDataObserver(observer)
        installSpanLookup()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        runCatching { contentAdapter.unregisterAdapterDataObserver(observer) }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int {
        val contentCount = contentAdapter.itemCount
        return contentCount + adCountFor(contentCount)
    }

    override fun getItemViewType(position: Int): Int {
        if (isAdPosition(position)) return VIEW_TYPE_RECYCLER_AD
        return contentAdapter.getItemViewType(contentPositionFor(position))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_RECYCLER_AD) {
            return AdHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    effectiveOptions.adHeightDp?.dp(parent.context) ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        }
        return contentAdapter.onCreateViewHolder(parent, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AdHolder) {
            holder.bind(activity, placementName, placement?.format)
            return
        }
        contentAdapter.onBindViewHolder(holder, contentPositionFor(position))
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AdHolder) {
            holder.clear()
        } else {
            contentAdapter.onViewRecycled(holder)
        }
        super.onViewRecycled(holder)
    }

    private fun isAdPosition(position: Int): Boolean {
        val opts = effectiveOptions
        if (!opts.enabled || opts.maxAds <= 0 || placement == null) return false
        val firstAdPosition = opts.startAfter
        if (position < firstAdPosition) return false
        val offset = position - firstAdPosition
        if (offset % (opts.interval + 1) != 0) return false
        val adIndex = offset / (opts.interval + 1)
        return adIndex < opts.maxAds && contentAdapter.itemCount > opts.startAfter
    }

    private fun contentPositionFor(adapterPosition: Int): Int {
        var adsBefore = 0
        for (position in 0 until adapterPosition) {
            if (isAdPosition(position)) adsBefore++
        }
        return (adapterPosition - adsBefore).coerceIn(0, (contentAdapter.itemCount - 1).coerceAtLeast(0))
    }

    private fun adCountFor(contentCount: Int): Int {
        val opts = effectiveOptions
        if (!opts.enabled || opts.maxAds <= 0 || placement == null || contentCount <= opts.startAfter) return 0
        val availableAfterStart = contentCount - opts.startAfter
        val possible = ((availableAfterStart - 1) / opts.interval) + 1
        return possible.coerceAtMost(opts.maxAds)
    }

    private fun installSpanLookup() {
        val grid = recyclerView.layoutManager as? GridLayoutManager ?: return
        val previousLookup = grid.spanSizeLookup
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (isAdPosition(position)) grid.spanCount else previousLookup.getSpanSize(contentPositionFor(position))
            }
        }
    }

    private class AdHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        fun bind(activity: Activity, placementName: String, format: String?) {
            container.removeAllViews()
            when (format) {
                "banner" -> container.addView(ITWingBannerView(container.context).apply {
                    this.placementName = placementName
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                else -> container.addView(ITWingNativeAdView(container.context).apply {
                    this.placementName = placementName
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            if (activity.isFinishing || activity.isDestroyed) clear()
        }

        fun clear() {
            container.removeAllViews()
        }
    }

    companion object {
        private const val VIEW_TYPE_RECYCLER_AD = Int.MIN_VALUE + 520

        @JvmStatic
        fun wrap(
            activity: Activity,
            recyclerView: RecyclerView,
            contentAdapter: RecyclerView.Adapter<*>,
            placement: String,
            options: ITWingRecyclerAdOptions = ITWingRecyclerAdOptions(),
        ): RecyclerView.Adapter<RecyclerView.ViewHolder> {
            @Suppress("UNCHECKED_CAST")
            return ITWingRecyclerAdAdapter(
                activity = activity,
                recyclerView = recyclerView,
                contentAdapter = contentAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>,
                placementName = placement,
                options = options,
            )
        }
    }
}

private fun Map<String, Any?>.bool(key: String, defaultValue: Boolean): Boolean {
    return when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> defaultValue
    }
}

private fun Map<String, Any?>.int(key: String, defaultValue: Int): Int =
    intOrNull(key) ?: defaultValue

private fun Map<String, Any?>.intOrNull(key: String): Int? {
    return when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun Int.dp(context: android.content.Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
