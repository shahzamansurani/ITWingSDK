package com.itwingtech.itwingsdk.media

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.facebook.shimmer.ShimmerFrameLayout
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.MediaLibraryConfig
import com.itwingtech.itwingsdk.core.MediaPlacementConfig
import com.itwingtech.itwingsdk.ads.ITWingRecyclerAdAdapter
import com.itwingtech.itwingsdk.ads.ITWingRecyclerAdOptions
import com.itwingtech.itwingsdk.utils.NetworkState
import com.itwingtech.itwingsdk.utils.SDKUi
import java.util.concurrent.CopyOnWriteArraySet
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.toColorInt

fun interface ITWingMediaItemBinder {
    fun bind(view: View, item: ITWingMediaItem, position: Int)
}

fun interface ITWingMediaCategoryBinder {
    fun bind(view: View, category: ITWingMediaCategory, selected: Boolean, position: Int)
}

private object ITWingMediaCache {
    private val responses = mutableMapOf<String, ITWingMediaResponse>()
    private val trackedViews = CopyOnWriteArraySet<String>()

    fun response(kind: String): ITWingMediaResponse? = synchronized(responses) { responses[kind] }

    fun load(kind: String, callback: (Result<ITWingMediaResponse>) -> Unit) {
        if (!ITWingSDK.isReady()) {
            callback(Result.failure(IllegalStateException("ITWingSDK is not ready yet.")))
            return
        }
        ITWingSDK.fetchMediaLibrary(kind = kind, callback = object : ITWingMediaCallback() {
            override fun onLoaded(response: ITWingMediaResponse) {
                synchronized(responses) { responses[kind] = response }
                callback(Result.success(response))
            }

            override fun onError(error: String) {
                callback(Result.failure(IllegalStateException(error)))
            }
        })
    }

    fun markViewTracked(kind: String, id: String): Boolean = trackedViews.add("$kind:$id")
}

private data class MediaItemStyle(
    var widthPx: Int = 0,
    var heightPx: Int = 0,
    var spacingPx: Int = 0,
    var cornerPx: Int = 0,
    var titleColor: Int = 0,
    var backgroundColor: Int = 0,
    var strokeColor: Int = 0,
    var premiumMode: Int = 0,
    var premiumIcon: Int = 0,
    var premiumIconTint: Int? = null,
)

private data class MediaCategoryStyle(
    var widthPx: Int = 0,
    var heightPx: Int = 0,
    var selectedDrawable: Int = 0,
    var unselectedDrawable: Int = 0,
    var selectedColor: Int = 0,
    var textColor: Int = 0,
    var showTitle: Boolean = true,
)

open class ITWingMediaItemsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val mediaKind: String = "ringtones",
    private val forceTrending: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val recyclerView = RecyclerView(context)
    private val shimmer = ShimmerFrameLayout(context)
    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(SDKUi.mutedTextColor(context))
        textSize = 14f
        text = context.getString(R.string.itwing_media_empty)
        visibility = GONE
    }
    private val adapter = MediaItemAdapter(mediaKind, ::handleItemClick)
    private var placementName: String? = null
    private var columns = 2
    private var horizontal = false
    private var limit = 60
    private var showTrending = forceTrending
    private var showTitle = true
    private var customLayoutRes = 0
    private var premiumUnlockPlacement = "rewarded"
    private var categoryId: String? = null
    private var categorySlug: String? = null
    private var clickListener: ((ITWingMediaItem) -> Unit)? = null
    private var customBinder: ITWingMediaItemBinder? = null
    private var itemFilter: ((ITWingMediaItem) -> Boolean)? = null
    private var inlineAdEnabled = false
    private var inlineAdPlacement: String? = null
    private var inlineAdInterval = 0
    private var inlineAdStartAfter = 0
    private var inlineAdMaxAds = 0
    private var cachedOfflineNoticeShown = false
    private val itemStyle = MediaItemStyle()

    init {
        itemStyle.titleColor = SDKUi.primaryTextColor(context)
        itemStyle.backgroundColor = SDKUi.surfaceColor(context)
        itemStyle.strokeColor = SDKUi.strokeColor(context)
        readAttrs(attrs)
        addView(shimmer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shimmer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            repeat(4) { addView(shimmerRow()) }
        })
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyLayoutManager()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (adapter.itemCount == 0) reload()
    }

    fun reload() {
        showLoading()
        val offlineAtStart = !NetworkState.isOnline(context)
        waitForReady(20) {
            val placement = resolvePlacement()
            if (!applyPlacement(placement)) return@waitForReady
            val selected = placement?.selectedItemIds.orEmpty()
            ITWingSDK.fetchMediaLibrary(
                kind = mediaKind,
                categoryId = categoryId ?: placement?.categoryId,
                categorySlug = categorySlug,
                limit = placement?.limit ?: limit,
                trendingLimit = placement?.limit ?: limit,
                sort = placement?.sort,
                selectedItemIds = selected,
                callback = object : ITWingMediaCallback() {
                    override fun onLoaded(response: ITWingMediaResponse) {
                        val source = if (showTrending || placement?.type == "top_trends") response.trending else response.items
                        val items = source.filter { shouldDisplayItem(it) && itemFilter?.invoke(it) != false }.take(placement?.limit ?: limit)
                        submit(items)
                        if (offlineAtStart && items.isNotEmpty()) showCachedContentNotice()
                    }

                    override fun onError(error: String) {
                        hideLoading()
                        emptyView.text = error
                        emptyView.visibility = VISIBLE
                        ITWingSDK.showSdkFeatureError(context, "Media unavailable", error)
                    }
                },
            )
        }
    }

    private fun showCachedContentNotice() {
        if (cachedOfflineNoticeShown) return
        cachedOfflineNoticeShown = true
        ITWingSDK.showSdkFeatureError(
            context,
            context.getString(R.string.itwing_cached_content_title),
            context.getString(R.string.itwing_cached_content_message),
        ) { reload() }
    }

    fun filterCategory(category: ITWingMediaCategory?) {
        categoryId = category?.id?.takeIf(String::isNotBlank)
        categorySlug = category?.slug?.takeIf(String::isNotBlank)
        reload()
    }

    fun setOnMediaClickListener(listener: ((ITWingMediaItem) -> Unit)?) {
        clickListener = listener
    }

    fun setCustomItemLayout(layoutRes: Int, binder: ITWingMediaItemBinder? = null) {
        customLayoutRes = layoutRes
        customBinder = binder
        adapter.customLayoutRes = layoutRes
        adapter.customBinder = binder
    }

    fun setItemFilter(filter: ((ITWingMediaItem) -> Boolean)?) {
        itemFilter = filter
        reload()
    }

    protected open fun shouldDisplayItem(item: ITWingMediaItem): Boolean = true

    private fun submit(items: List<ITWingMediaItem>) {
        adapter.showTitle = showTitle
        adapter.customLayoutRes = customLayoutRes
        adapter.customBinder = customBinder
        adapter.style = itemStyle
        adapter.submit(items)
        items.forEach {
            if (ITWingMediaCache.markViewTracked(mediaKind, it.id)) {
                ITWingSDK.trackMediaLibraryEvent(mediaKind, it.id, "view")
            }
        }
        hideLoading()
        emptyView.visibility = if (items.isEmpty()) VISIBLE else GONE
    }

    private fun handleItemClick(view: View, item: ITWingMediaItem) {
        ITWingSDK.trackMediaLibraryEvent(mediaKind, item.id, "click")
        if (item.isPremium && !ITWingSDK.isAdFree() && !isUnlocked(item.id)) {
            val activity = context.findActivity()
            if (activity == null) {
                ITWingSDK.showSdkFeatureError(context, "Premium media locked", "Rewarded ad requires an active Activity.")
                return
            }
            ITWingSDK.showRewarded(
                activity = activity,
                placement = premiumUnlockPlacement,
                onReward = { markUnlocked(item.id) },
                onComplete = { if (isUnlocked(item.id)) openItem(item) },
                onUnavailableOrSkipped = {
                    ITWingSDK.showSdkFeatureError(context, "Premium media locked", "Watch the full rewarded ad to unlock this item.")
                },
            )
            return
        }
        openItem(item)
    }

    private fun openItem(item: ITWingMediaItem) {
        ITWingSDK.trackMediaLibraryEvent(mediaKind, item.id, if (mediaKind == "vpn_servers") "connect" else "play")
        clickListener?.invoke(item)
    }

    private fun isUnlocked(id: String): Boolean =
        context.getSharedPreferences("itwing_media_unlocks", Context.MODE_PRIVATE).getBoolean("$mediaKind:$id", false)

    private fun markUnlocked(id: String) {
        context.getSharedPreferences("itwing_media_unlocks", Context.MODE_PRIVATE).edit().putBoolean("$mediaKind:$id", true).apply()
    }

    private fun showLoading() {
        emptyView.visibility = GONE
        shimmer.visibility = VISIBLE
        shimmer.startShimmer()
        recyclerView.visibility = INVISIBLE
    }

    private fun hideLoading() {
        shimmer.stopShimmer()
        shimmer.visibility = GONE
        recyclerView.visibility = VISIBLE
    }

    private fun readAttrs(attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, R.styleable.ITWingMediaItemsView) {
            placementName = getString(R.styleable.ITWingMediaItemsView_ITWingMediaPlacement)
            columns = getInt(
                R.styleable.ITWingMediaItemsView_ITWingMediaColumns,
                columns
            ).coerceAtLeast(1)
            horizontal =
                getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaHorizontal, horizontal)
            limit =
                getInt(R.styleable.ITWingMediaItemsView_ITWingMediaLimit, limit).coerceIn(1, 500)
            showTrending =
                getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaTopTrending, showTrending)
            showTitle = getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaShowTitle, showTitle)
            customLayoutRes =
                getResourceId(R.styleable.ITWingMediaItemsView_ITWingMediaItemLayout, 0)
            premiumUnlockPlacement =
                getString(R.styleable.ITWingMediaItemsView_ITWingMediaPremiumUnlockPlacement)
                    ?: premiumUnlockPlacement
            itemStyle.widthPx = getDimensionPixelSize(
                R.styleable.ITWingMediaItemsView_ITWingMediaItemWidth,
                itemStyle.widthPx
            )
            itemStyle.heightPx = getDimensionPixelSize(
                R.styleable.ITWingMediaItemsView_ITWingMediaItemHeight,
                itemStyle.heightPx
            )
            itemStyle.spacingPx = getDimensionPixelSize(
                R.styleable.ITWingMediaItemsView_ITWingMediaItemSpacing,
                itemStyle.spacingPx
            )
            itemStyle.cornerPx = getDimensionPixelSize(
                R.styleable.ITWingMediaItemsView_ITWingMediaCornerRadius,
                itemStyle.cornerPx
            )
            itemStyle.titleColor = getColor(
                R.styleable.ITWingMediaItemsView_ITWingMediaTitleTextColor,
                itemStyle.titleColor
            )
            itemStyle.backgroundColor = getColor(
                R.styleable.ITWingMediaItemsView_ITWingMediaItemBackgroundColor,
                itemStyle.backgroundColor
            )
            itemStyle.strokeColor = getColor(
                R.styleable.ITWingMediaItemsView_ITWingMediaItemStrokeColor,
                itemStyle.strokeColor
            )
            itemStyle.premiumMode = getInt(
                R.styleable.ITWingMediaItemsView_ITWingMediaPremiumMode,
                itemStyle.premiumMode
            )
            itemStyle.premiumIcon = getResourceId(
                R.styleable.ITWingMediaItemsView_ITWingMediaPremiumIcon,
                itemStyle.premiumIcon
            )
            if (hasValue(R.styleable.ITWingMediaItemsView_ITWingMediaPremiumIconTint)) {
                itemStyle.premiumIconTint =
                    getColor(R.styleable.ITWingMediaItemsView_ITWingMediaPremiumIconTint, Color.WHITE)
            }
        }
        readWallpaperItemCompatAttrs(attrs)
        adapter.showTitle = showTitle
        adapter.customLayoutRes = customLayoutRes
        adapter.style = itemStyle
    }

    private fun readWallpaperItemCompatAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        context.withStyledAttributes(attrs, R.styleable.ITWingWallpapersView) {
            placementName = placementName
                ?: getString(R.styleable.ITWingWallpapersView_ITWingWallpaperPlacement)
            columns = getInt(
                R.styleable.ITWingWallpapersView_ITWingWallpaperColumns,
                columns
            ).coerceAtLeast(1)
            horizontal =
                getBoolean(R.styleable.ITWingWallpapersView_ITWingWallpaperHorizontal, horizontal)
            limit = getInt(R.styleable.ITWingWallpapersView_ITWingWallpaperLimit, limit).coerceIn(
                1,
                500
            )
            showTrending = getBoolean(
                R.styleable.ITWingWallpapersView_ITWingWallpaperTopTrending,
                showTrending
            )
            showTitle =
                getBoolean(R.styleable.ITWingWallpapersView_ITWingWallpaperShowTitle, showTitle)
            if (customLayoutRes == 0) customLayoutRes =
                getResourceId(R.styleable.ITWingWallpapersView_ITWingWallpaperItemLayout, 0)
            premiumUnlockPlacement =
                getString(R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumUnlockPlacement)
                    ?: premiumUnlockPlacement
            itemStyle.widthPx = getDimensionPixelSize(
                R.styleable.ITWingWallpapersView_ITWingWallpaperItemWidth,
                itemStyle.widthPx
            )
            itemStyle.heightPx = getDimensionPixelSize(
                R.styleable.ITWingWallpapersView_ITWingWallpaperItemHeight,
                itemStyle.heightPx
            )
            itemStyle.spacingPx = getDimensionPixelSize(
                R.styleable.ITWingWallpapersView_ITWingWallpaperItemSpacing,
                itemStyle.spacingPx
            )
            itemStyle.cornerPx = getDimensionPixelSize(
                R.styleable.ITWingWallpapersView_ITWingWallpaperCornerRadius,
                itemStyle.cornerPx
            )
            itemStyle.titleColor = getColor(
                R.styleable.ITWingWallpapersView_ITWingWallpaperTitleTextColor,
                itemStyle.titleColor
            )
            itemStyle.backgroundColor = getColor(
                R.styleable.ITWingWallpapersView_ITWingWallpaperItemBackgroundColor,
                itemStyle.backgroundColor
            )
            itemStyle.strokeColor = getColor(
                R.styleable.ITWingWallpapersView_ITWingWallpaperItemStrokeColor,
                itemStyle.strokeColor
            )
            itemStyle.premiumMode = getInt(
                R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumMode,
                itemStyle.premiumMode
            )
            itemStyle.premiumIcon = getResourceId(
                R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIcon,
                itemStyle.premiumIcon
            )
            if (hasValue(R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIconTint)) {
                itemStyle.premiumIconTint = getColor(
                    R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIconTint,
                    Color.WHITE
                )
            }
        }
    }

    private fun resolvePlacement(): MediaPlacementConfig? {
        val config = mediaConfig()
        return placementName?.let { config.placements[it] }
    }

    private fun mediaConfig(): MediaLibraryConfig =
        when (mediaKind) {
            "videos" -> ITWingSDK.currentConfig().videos
            "vpn_servers" -> ITWingSDK.currentConfig().vpnServers
            else -> ITWingSDK.currentConfig().ringtones
        }

    private fun applyPlacement(placement: MediaPlacementConfig?): Boolean {
        if (placement == null) return true
        if (!placement.enabled) {
            emptyView.text = "This placement is disabled"
            emptyView.visibility = VISIBLE
            shimmer.stopShimmer()
            shimmer.visibility = GONE
            recyclerView.visibility = INVISIBLE
            return false
        }
        placement.limit?.let { limit = it.coerceIn(1, 500) }
        placement.columns?.let { columns = it.coerceAtLeast(1) }
        placement.horizontal?.let { horizontal = it }
        placement.showTitle?.let { showTitle = it }
        placement.premiumUnlockPlacement?.takeIf(String::isNotBlank)?.let { premiumUnlockPlacement = it }
        inlineAdEnabled = placement.inlineAdEnabled
        inlineAdPlacement = placement.inlineAdPlacement?.takeIf(String::isNotBlank)
        inlineAdInterval = placement.inlineAdInterval.coerceAtLeast(0)
        inlineAdStartAfter = placement.inlineAdStartAfter.coerceAtLeast(0)
        inlineAdMaxAds = placement.inlineAdMaxAds.coerceAtLeast(0)
        applyLayoutManager()
        return true
    }

    private fun applyLayoutManager() {
        recyclerView.layoutManager = if (horizontal) {
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        } else {
            GridLayoutManager(context, columns.coerceAtLeast(1))
        }
        recyclerView.overScrollMode = OVER_SCROLL_NEVER
        recyclerView.clipToPadding = false
        recyclerView.adapter = adWrappedAdapterOrContent()
    }

    private fun adWrappedAdapterOrContent(): RecyclerView.Adapter<out RecyclerView.ViewHolder> {
        val activity = context.findActivity()
        val placement = inlineAdPlacement
        if (
            activity == null ||
            !inlineAdEnabled ||
            placement.isNullOrBlank() ||
            inlineAdInterval <= 0 ||
            inlineAdMaxAds <= 0
        ) {
            return adapter
        }
        return ITWingRecyclerAdAdapter.wrap(
            activity = activity,
            recyclerView = recyclerView,
            contentAdapter = adapter,
            placement = placement,
            options = ITWingRecyclerAdOptions(
                enabled = true,
                interval = inlineAdInterval,
                startAfter = inlineAdStartAfter,
                maxAds = inlineAdMaxAds,
            ),
        )
    }

    private fun waitForReady(attempts: Int, block: () -> Unit) {
        if (ITWingSDK.isReady()) {
            block()
        } else if (attempts > 0) {
            postDelayed({ waitForReady(attempts - 1, block) }, 250)
        } else {
            hideLoading()
            emptyView.text = context.getString(R.string.itwing_sdk_not_ready_message)
            emptyView.visibility = VISIBLE
            ITWingSDK.showSdkFeatureError(
                context,
                context.getString(R.string.itwing_sdk_not_ready_title),
                context.getString(R.string.itwing_sdk_not_ready_message),
            )
        }
    }

    private fun shimmerRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        repeat(if (horizontal) 3 else columns.coerceAtLeast(1)) {
            addView(View(context).apply {
                background = rounded(SDKUi.shimmerBaseColor(context), dp(14).toFloat())
            }, LinearLayout.LayoutParams(0, dp(118), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }
}

open class ITWingMediaCategoriesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val mediaKind: String = "ringtones",
) : FrameLayout(context, attrs, defStyleAttr) {
    enum class DisplayMode { TEXT, IMAGE, BOTH }

    private val recyclerView = RecyclerView(context)
    private val adapter = MediaCategoryAdapter(mediaKind, ::handleCategoryClick)
    private var placementName: String? = null
    private var columns = 1
    private var horizontal = true
    private var linkedViewId = 0
    private var linkedView: ITWingMediaItemsView? = null
    private var displayMode = DisplayMode.TEXT
    private var customLayoutRes = 0
    private var clickListener: ((ITWingMediaCategory) -> Unit)? = null
    private var customBinder: ITWingMediaCategoryBinder? = null
    private val categoryStyle = MediaCategoryStyle()

    init {
        categoryStyle.selectedColor = SDKUi.primaryColor()
        categoryStyle.textColor = SDKUi.primaryTextColor(context)
        readAttrs(attrs)
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        recyclerView.adapter = adapter
        applyLayoutManager()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (linkedView == null && linkedViewId != 0) linkedView = rootView.findViewById(linkedViewId)
        reload()
    }

    fun reload() {
        waitForReady(20) {
            resolvePlacement()?.let { placement ->
                placement.columns?.let { columns = it.coerceAtLeast(1) }
                placement.horizontal?.let { horizontal = it }
                placement.categoryDisplayMode?.let {
                    displayMode = when (it.lowercase()) {
                        "image" -> DisplayMode.IMAGE
                        "both" -> DisplayMode.BOTH
                        else -> DisplayMode.TEXT
                    }
                }
                applyLayoutManager()
            }
            ITWingSDK.fetchMediaLibrary(mediaKind, callback = object : ITWingMediaCallback() {
                override fun onLoaded(response: ITWingMediaResponse) {
                    val all = ITWingMediaCategory("", "All", null, "All", null, -1)
                    adapter.displayMode = displayMode
                    adapter.customLayoutRes = customLayoutRes
                    adapter.customBinder = customBinder
                    adapter.style = categoryStyle
                    adapter.submit(listOf(all) + response.categories)
                }

                override fun onError(error: String) {
                    adapter.submit(emptyList())
                }
            })
        }
    }

    fun attachMediaView(view: ITWingMediaItemsView?) {
        linkedView = view
    }

    fun setCustomItemLayout(layoutRes: Int, binder: ITWingMediaCategoryBinder? = null) {
        customLayoutRes = layoutRes
        customBinder = binder
    }

    fun setOnCategoryClickListener(listener: ((ITWingMediaCategory) -> Unit)?) {
        clickListener = listener
    }

    private fun handleCategoryClick(category: ITWingMediaCategory) {
        linkedView?.filterCategory(category.takeIf { it.id.isNotBlank() })
        clickListener?.invoke(category)
    }

    private fun readAttrs(attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, R.styleable.ITWingMediaCategoriesView) {
            placementName = getString(R.styleable.ITWingMediaCategoriesView_ITWingMediaPlacement)
            columns = getInt(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryColumns,
                columns
            ).coerceAtLeast(1)
            horizontal = getBoolean(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryHorizontal,
                horizontal
            )
            linkedViewId =
                getResourceId(R.styleable.ITWingMediaCategoriesView_ITWingMediaLinkedView, 0)
            categoryStyle.widthPx = getDimensionPixelSize(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryItemWidth,
                categoryStyle.widthPx
            )
            categoryStyle.heightPx = getDimensionPixelSize(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryItemHeight,
                categoryStyle.heightPx
            )
            customLayoutRes = getResourceId(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryItemLayout,
                0
            )
            categoryStyle.selectedDrawable = getResourceId(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategorySelectedDrawable,
                categoryStyle.selectedDrawable
            )
            categoryStyle.unselectedDrawable = getResourceId(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryUnselectedDrawable,
                categoryStyle.unselectedDrawable
            )
            categoryStyle.selectedColor = getColor(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategorySelectedColor,
                categoryStyle.selectedColor
            )
            categoryStyle.textColor = getColor(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryTextColor,
                categoryStyle.textColor
            )
            categoryStyle.showTitle = getBoolean(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryShowTitle,
                categoryStyle.showTitle
            )
            displayMode = when (getInt(
                R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryDisplayMode,
                0
            )) {
                1 -> DisplayMode.IMAGE
                2 -> DisplayMode.BOTH
                else -> DisplayMode.TEXT
            }
        }
        readWallpaperCategoryCompatAttrs(attrs)
        resolvePlacement()?.let { placement ->
            placement.columns?.let { columns = it.coerceAtLeast(1) }
            placement.horizontal?.let { horizontal = it }
            placement.categoryDisplayMode?.let {
                displayMode = when (it.lowercase()) {
                    "image" -> DisplayMode.IMAGE
                    "both" -> DisplayMode.BOTH
                    else -> DisplayMode.TEXT
                }
            }
        }
    }

    private fun readWallpaperCategoryCompatAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        context.withStyledAttributes(attrs, R.styleable.ITWingWallpaperCategoriesView) {
            placementName = placementName
                ?: getString(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperPlacement)
                        ?: getString(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryPlacement)
            columns = getInt(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryColumns,
                columns
            ).coerceAtLeast(1)
            horizontal = getBoolean(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryHorizontal,
                horizontal
            )
            if (linkedViewId == 0) linkedViewId = getResourceId(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperLinkedView,
                0
            )
            categoryStyle.widthPx = getDimensionPixelSize(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemWidth,
                categoryStyle.widthPx
            )
            categoryStyle.heightPx = getDimensionPixelSize(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemHeight,
                categoryStyle.heightPx
            )
            if (customLayoutRes == 0) customLayoutRes = getResourceId(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemLayout,
                0
            )
            categoryStyle.selectedDrawable = getResourceId(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategorySelectedDrawable,
                categoryStyle.selectedDrawable
            )
            categoryStyle.unselectedDrawable = getResourceId(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryUnselectedDrawable,
                categoryStyle.unselectedDrawable
            )
            categoryStyle.selectedColor = getColor(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategorySelectedColor,
                categoryStyle.selectedColor
            )
            categoryStyle.textColor = getColor(
                R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryTextColor,
                categoryStyle.textColor
            )
            categoryStyle.showTitle = getBoolean(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryShowTitle, categoryStyle.showTitle)
            displayMode = when (getInt(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryDisplayMode, when (displayMode) {
                DisplayMode.IMAGE -> 1
                DisplayMode.BOTH -> 2
                else -> 0
            })) {
                1 -> DisplayMode.IMAGE
                2 -> DisplayMode.BOTH
                else -> DisplayMode.TEXT
            }
        }
    }

    private fun resolvePlacement(): MediaPlacementConfig? {
        val config = when (mediaKind) {
            "videos" -> ITWingSDK.currentConfig().videos
            "vpn_servers" -> ITWingSDK.currentConfig().vpnServers
            else -> ITWingSDK.currentConfig().ringtones
        }
        return placementName?.let { config.placements[it] }
    }

    private fun applyLayoutManager() {
        recyclerView.layoutManager = if (horizontal) LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) else GridLayoutManager(context, columns)
        recyclerView.overScrollMode = OVER_SCROLL_NEVER
    }

    private fun waitForReady(attempts: Int, block: () -> Unit) {
        if (ITWingSDK.isReady()) block() else if (attempts > 0) postDelayed({ waitForReady(attempts - 1, block) }, 250)
    }
}

class ITWingRingtonesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "ringtones", false)

class ITWingTopTrendsRingtoneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "ringtones", true)

class ITWingRingtoneCategoriesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaCategoriesView(context, attrs, defStyleAttr, "ringtones")

class ITWingVideosView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "videos", false)

class ITWingTopTrendsVideoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "videos", true)

class ITWingVideoCategoriesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaCategoriesView(context, attrs, defStyleAttr, "videos")

class ITWingVpnServersView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "vpn_servers", false) {

    private var tabsEnabled = false
    private var selectedTab = ServerTierTab.FREE
    private var tabsView: LinearLayout? = null
    private var contentTopMargin = 0

    init {
        context.withStyledAttributes(attrs, R.styleable.ITWingMediaItemsView) {
            tabsEnabled = getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaVpnTabsEnabled, false)
        }
        if (tabsEnabled) installTabs()
    }

    fun setVpnServerTabsEnabled(enabled: Boolean) {
        if (tabsEnabled == enabled) return
        tabsEnabled = enabled
        if (enabled) {
            installTabs()
        } else {
            removeTabs()
        }
        reload()
    }

    fun setVpnServerTierTab(tab: String) {
        selectedTab = if (tab.equals("premium", true) || tab.equals("owned", true)) ServerTierTab.PREMIUM else ServerTierTab.FREE
        updateTabStyles()
        reload()
    }

    override fun shouldDisplayItem(item: ITWingMediaItem): Boolean {
        if (!isVpnServerWorking(item)) return false
        if (!tabsEnabled) return true
        val publicServer = item.isPublicVpnServer()
        return if (selectedTab == ServerTierTab.FREE) publicServer else !publicServer
    }

    private fun installTabs() {
        if (tabsView != null) return
        val height = dp(40)
        val margin = dp(8)
        contentTopMargin = height + margin
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(tabButton("Free", ServerTierTab.FREE), LinearLayout.LayoutParams(0, height, 1f))
            addView(tabButton("Premium", ServerTierTab.PREMIUM), LinearLayout.LayoutParams(0, height, 1f).apply {
                leftMargin = dp(8)
            })
        }
        tabsView = tabs
        adjustContentTopMargin(contentTopMargin)
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.TOP))
        updateTabStyles()
    }

    private fun removeTabs() {
        tabsView?.let { removeView(it) }
        tabsView = null
        adjustContentTopMargin(0)
        contentTopMargin = 0
    }

    private fun adjustContentTopMargin(topMargin: Int) {
        val tabs = tabsView
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child == tabs) continue
            val params = child.layoutParams as? MarginLayoutParams ?: continue
            params.topMargin = topMargin
            child.layoutParams = params
        }
    }

    private fun tabButton(label: String, tab: ServerTierTab): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (selectedTab != tab) {
                    selectedTab = tab
                    updateTabStyles()
                    reload()
                }
            }
        }

    private fun updateTabStyles() {
        val tabs = tabsView ?: return
        for (index in 0 until tabs.childCount) {
            val child = tabs.getChildAt(index) as? TextView ?: continue
            val selected = (index == 0 && selectedTab == ServerTierTab.FREE) || (index == 1 && selectedTab == ServerTierTab.PREMIUM)
            child.setTextColor(if (selected) Color.BLACK else SDKUi.primaryTextColor(context))
            child.background = rounded(
                if (selected) SDKUi.primaryColor() else SDKUi.surfaceColor(context),
                dp(12).toFloat(),
                if (selected) SDKUi.primaryColor() else SDKUi.strokeColor(context),
                1,
            )
        }
    }

    private fun isVpnServerWorking(item: ITWingMediaItem): Boolean {
        val serverStatus = item.metadata["server_status"]?.toString()?.trim()?.lowercase()
        val pingStatus = item.metadata["last_ping_status"]?.toString()?.trim()?.lowercase()
        if (!serverStatus.isNullOrBlank() && serverStatus != "online") return false
        if (!pingStatus.isNullOrBlank() && pingStatus != "online") return false
        if (item.isPublicVpnServer()) {
            val stability = item.metadata["public_stability_status"]?.toString()?.trim()?.lowercase()
            val mode = item.metadata["last_ping_mode"]?.toString()?.trim()?.lowercase()
            if (stability != "stable" || mode != "tcp_socket") return false
        }
        return true
    }

    private fun ITWingMediaItem.isPublicVpnServer(): Boolean {
        val source = metadata["server_source"]?.toString()?.trim()?.lowercase()
        val tier = metadata["server_tier"]?.toString()?.trim()?.lowercase()
        return source == "vpngate_public" || tier == "public"
    }

    private enum class ServerTierTab {
        FREE,
        PREMIUM,
    }
}

class ITWingTopTrendsVpnServerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaItemsView(context, attrs, defStyleAttr, "vpn_servers", true)

class ITWingVpnCountriesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ITWingMediaCategoriesView(context, attrs, defStyleAttr, "vpn_servers")

private class MediaItemAdapter(
    private val kind: String,
    private val onClick: (View, ITWingMediaItem) -> Unit,
) : RecyclerView.Adapter<MediaItemAdapter.Holder>() {
    private val items = mutableListOf<ITWingMediaItem>()
    var showTitle = true
    var customLayoutRes = 0
    var customBinder: ITWingMediaItemBinder? = null
    var style = MediaItemStyle()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = if (customLayoutRes != 0) {
            LayoutInflater.from(parent.context).inflate(customLayoutRes, parent, false)
        } else {
            defaultMediaItem(parent.context)
        }
        view.applyMediaItemLayout(style)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        customBinder?.bind(holder.itemView, item, position) ?: holder.itemView.bindMediaItem(item, kind, showTitle, style)
        holder.itemView.setOnClickListener { onClick(it, item) }
    }

    fun submit(next: List<ITWingMediaItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)
}

private class MediaCategoryAdapter(
    private val kind: String,
    private val onClick: (ITWingMediaCategory) -> Unit,
) : RecyclerView.Adapter<MediaCategoryAdapter.Holder>() {
    private val items = mutableListOf<ITWingMediaCategory>()
    private var selected = ""
    var displayMode = ITWingMediaCategoriesView.DisplayMode.TEXT
    var customLayoutRes = 0
    var customBinder: ITWingMediaCategoryBinder? = null
    var style = MediaCategoryStyle()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = if (customLayoutRes != 0) LayoutInflater.from(parent.context).inflate(customLayoutRes, parent, false) else defaultCategoryItem(parent.context)
        view.applyMediaCategoryLayout(style)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isSelected = item.id == selected || (selected.isBlank() && item.id.isBlank())
        customBinder?.bind(holder.itemView, item, isSelected, position) ?: holder.itemView.bindMediaCategory(item, kind, displayMode, isSelected, style)
        holder.itemView.setOnClickListener {
            val old = selected
            selected = item.id
            notifyItemChanged(items.indexOfFirst { it.id == old }.coerceAtLeast(0))
            notifyItemChanged(position)
            onClick(item)
        }
    }

    fun submit(next: List<ITWingMediaCategory>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)
}

private fun defaultMediaItem(context: Context): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Color.WHITE, dp(16).toFloat(), Color.parseColor("#E5E7EB"), 1)
    setPadding(dp(6), dp(6), dp(6), dp(8))
    addView(FrameLayout(context).apply {
        id = R.id.itwing_media_art
        background = rounded(Color.parseColor("#111827"), dp(14).toFloat())
        addView(ImageView(context).apply {
            id = R.id.itwing_media_image
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(context).apply {
            id = R.id.itwing_media_icon
            text = "Audio"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(context).apply {
            id = R.id.itwing_media_premium
            text = "Premium"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#7C3AED"), dp(999).toFloat())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)))
    addView(TextView(context).apply {
        id = R.id.itwing_media_title
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#111827"))
        textSize = 14f
        maxLines = 2
        setPadding(dp(2), dp(8), dp(2), 0)
    })
    addView(TextView(context).apply {
        id = R.id.itwing_media_subtitle
        setTextColor(Color.parseColor("#64748B"))
        textSize = 12f
        maxLines = 1
        setPadding(dp(2), dp(2), dp(2), 0)
    })
}

private fun defaultCategoryItem(context: Context): View = FrameLayout(context).apply {
    background = rounded(Color.WHITE, dp(999).toFloat(), Color.parseColor("#E5E7EB"), 1)
    minimumHeight = dp(42)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    addView(ImageView(context).apply {
        id = R.id.itwing_media_category_image
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    addView(TextView(context).apply {
        id = R.id.itwing_media_category_flag
        textSize = 24f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    addView(TextView(context).apply {
        id = R.id.itwing_media_category_title
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#111827"))
        textSize = 13f
        gravity = Gravity.CENTER
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

private fun View.bindMediaItem(item: ITWingMediaItem, kind: String, showTitle: Boolean, style: MediaItemStyle) {
    val image = findViewByName<ImageView>("itwing_media_image", "media_image", "thumbnail", "country_flag")
    val icon = findViewByName<TextView>("itwing_media_icon")
    val title = findViewByName<TextView>("itwing_media_title", "media_title", "title", "country_name")
    val subtitle = findViewByName<TextView>(
        "itwing_media_subtitle",
        "itwing_media_sub_title",
        "media_subtitle",
        "media_sub_title",
        "subtitle",
        "server_name",
        "vpn_server_name",
    )
    val premium = findViewByName<TextView>("itwing_media_premium", "media_premium", "premium", "cost")
    val vpnLabel = if (kind == "vpn_servers") item.vpnListTitle() else item.title
    title?.text = vpnLabel
    title?.setTextColor(style.titleColor)
    title?.visibility = if (showTitle) View.VISIBLE else View.GONE
    if (kind == "vpn_servers") {
        subtitle?.text = item.vpnDisplaySubtitle
            ?: item.metadata["server_label"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: if (item.isPremium) "Premium server" else "Free server"
        subtitle?.visibility = if (showTitle && !subtitle?.text.isNullOrBlank()) View.VISIBLE else View.GONE
    } else {
        subtitle?.visibility = View.GONE
    }
    premium?.visibility = if (item.isPremium || kind == "vpn_servers") View.VISIBLE else View.GONE
    if (kind == "vpn_servers" && premium != null) {
        premium.setTextColor(if (item.isPremium) Color.parseColor("#F59E0B") else Color.parseColor("#22C55E"))
        premium.text = if (item.isPremium) "Pro" else "Free"
        premium.background = null
        premium.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    } else if (premium != null && item.isPremium && style.premiumIcon != 0 && style.premiumMode != 0) {
        premium.text = if (style.premiumMode == 1) "" else premium.text
        premium.setCompoundDrawablesWithIntrinsicBounds(style.premiumIcon, 0, 0, 0)
        style.premiumIconTint?.let { premium.compoundDrawableTintList = ColorStateList.valueOf(it) }
    }
    val artwork = item.thumbnailUrl?.takeIf(String::isNotBlank)
    if (artwork != null && image != null) {
        image.visibility = View.VISIBLE
        icon?.visibility = View.GONE
        Glide.with(image).load(artwork).centerCrop().into(image)
    } else {
        image?.visibility = View.GONE
        icon?.visibility = View.VISIBLE
        icon?.text = when (kind) {
            "videos" -> "Video"
            "vpn_servers" -> item.vpnFlagEmoji ?: item.vpnCountryCode ?: "VPN"
            else -> "Audio"
        }
    }
}

private fun ITWingMediaItem.vpnListTitle(): String {
    vpnDisplayTitle?.let { return it }
    val country = vpnCountryName ?: "VPN Server"
    val flag = vpnFlagEmoji
    return if (flag.isNullOrBlank()) country else "$flag $country"
}

private fun View.bindMediaCategory(item: ITWingMediaCategory, kind: String, mode: ITWingMediaCategoriesView.DisplayMode, selected: Boolean, style: MediaCategoryStyle) {
    val image = findViewByName<ImageView>("itwing_media_category_image", "media_category_image", "category_image")
    val flag = findViewByName<TextView>("itwing_media_category_flag", "media_category_flag", "category_flag")
    val title = findViewByName<TextView>("itwing_media_category_title", "media_category_title", "category_title", "title")
    if (selected && style.selectedDrawable != 0) {
        setBackgroundResource(style.selectedDrawable)
    } else if (!selected && style.unselectedDrawable != 0) {
        setBackgroundResource(style.unselectedDrawable)
    } else {
        background = rounded(if (selected) style.selectedColor else Color.WHITE, dp(999).toFloat(), Color.parseColor("#E5E7EB"), 1)
    }
    title?.text = item.name
    title?.setTextColor(if (selected) Color.WHITE else style.textColor)
    title?.visibility = if (!style.showTitle || mode == ITWingMediaCategoriesView.DisplayMode.IMAGE) View.GONE else View.VISIBLE
    val derivedFlag = if (kind == "vpn_servers" && item.id.isNotBlank()) item.countryFlagEmoji() else null
    val shouldShowImage = mode != ITWingMediaCategoriesView.DisplayMode.TEXT && !item.imageUrl.isNullOrBlank()
    val shouldShowFlag = mode != ITWingMediaCategoriesView.DisplayMode.TEXT && item.imageUrl.isNullOrBlank() && !derivedFlag.isNullOrBlank()
    image?.visibility = if (shouldShowImage) View.VISIBLE else View.GONE
    flag?.visibility = if (shouldShowFlag) View.VISIBLE else View.GONE
    flag?.text = derivedFlag.orEmpty()
    if (!item.imageUrl.isNullOrBlank() && image != null) {
        Glide.with(image).load(item.imageUrl).centerCrop().into(image)
    }
}

private fun ITWingMediaCategory.countryFlagEmoji(): String? {
    if (name.equals("All", ignoreCase = true)) return "🌐"
    return listOfNotNull(slug, name)
        .map { it.trim().take(2).uppercase() }
        .firstNotNullOfOrNull { it.toFlagEmoji() }
}

private fun View.applyMediaItemLayout(style: MediaItemStyle) {
    val width = style.widthPx.takeIf { it > 0 }
    val height = style.heightPx.takeIf { it > 0 }
    if (width != null || height != null) {
        layoutParams = RecyclerView.LayoutParams(width ?: ViewGroup.LayoutParams.WRAP_CONTENT, height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        val spacing = style.spacingPx.coerceAtLeast(0)
        it.setMargins(spacing, spacing, spacing, spacing)
    }
    if (style.cornerPx > 0) {
        background = rounded(style.backgroundColor, style.cornerPx.toFloat(), style.strokeColor, 1)
    }
}

private fun View.applyMediaCategoryLayout(style: MediaCategoryStyle) {
    val width = style.widthPx.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
    val height = style.heightPx.takeIf { it > 0 } ?: ViewGroup.LayoutParams.MATCH_PARENT
    layoutParams = RecyclerView.LayoutParams(width, height)
}

private inline fun <reified T : View> View.findViewByName(vararg names: String): T? {
    for (name in names) {
        val id = resources.getIdentifier(name, "id", context.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(name, "id", "com.itwingtech.itwingsdk")
        if (id != 0) findViewById<T>(id)?.let { return it }
    }
    return null
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun String.featureTitle(): String =
    when (this) {
        "videos" -> "Videos"
        "vpn_servers" -> "VPN servers"
        else -> "Ringtones"
    }

private fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
