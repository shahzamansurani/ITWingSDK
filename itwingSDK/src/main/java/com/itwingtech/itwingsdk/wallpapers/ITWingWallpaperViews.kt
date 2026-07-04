package com.itwingtech.itwingsdk.wallpapers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.core.WallpaperPlacementConfig
import com.itwingtech.itwingsdk.utils.NetworkState
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

fun interface ITWingWallpaperItemBinder {
    fun bind(view: View, item: ITWingWallpaperItem, position: Int)
}

fun interface ITWingWallpaperCategoryBinder {
    fun bind(view: View, category: ITWingWallpaperCategory, selected: Boolean, position: Int)
}

private data class WallpaperUiStyle(
    var columns: Int = 2,
    var horizontal: Boolean = false,
    var itemWidthDp: Int = 150,
    var itemHeightDp: Int = 190,
    var itemSpacingDp: Int = 6,
    var cornerRadiusDp: Int = 14,
    var itemWidthPx: Int? = null,
    var itemHeightPx: Int? = null,
    var itemSpacingPx: Int? = null,
    var cornerRadiusPx: Int? = null,
    var strokeWidthDp: Int = 0,
    var strokeColor: Int = 0x1A000000,
    var backgroundColor: Int = Color.WHITE,
    var selectedColor: Int = 0xFF4C00FF.toInt(),
    var textColor: Int = Color.WHITE,
    var mutedTextColor: Int = Color.WHITE,
    var showTitle: Boolean = false,
    var premiumBadgeColor: Int = 0xFFFFB020.toInt(),
    var premiumBadgeTextColor: Int = Color.WHITE,
    var premiumIconRes: Int = 0,
    var premiumIconTint: Int? = null,
    var premiumMode: PremiumMode = PremiumMode.TEXT,
    var titlePosition: TextPosition = TextPosition.BOTTOM,
    var selectedDrawableRes: Int = 0,
    var unselectedDrawableRes: Int = 0,
)

private enum class TextPosition { TOP, CENTER, BOTTOM, OUTSIDE_BOTTOM }

private enum class PremiumMode { TEXT, ICON, HIDDEN }

private object WallpaperResponseCache {
    private const val TTL_MS = 5 * 60_000L
    private const val DEFAULT_FETCH_LIMIT = 300
    private var cachedAt = 0L
    private var response: ITWingWallpaperResponse? = null
    private var cachedLimit = 0
    private var cachedTrendingLimit = 0
    private val waiters = CopyOnWriteArrayList<(Result<ITWingWallpaperResponse>) -> Unit>()
    private val trackedViews = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile
    private var loading = false

    fun load(
        limit: Int,
        trendingLimit: Int?,
        sort: String?,
        selectedWallpaperIds: List<String>,
        force: Boolean,
        callback: (Result<ITWingWallpaperResponse>) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val cached = response
        val hasSelectedIds = selectedWallpaperIds.isNotEmpty()
        val requestedLimit = maxOf(limit, DEFAULT_FETCH_LIMIT)
        val requestedTrendLimit = maxOf(trendingLimit ?: 0, cached?.topLimit ?: 0, 50)
        val cacheCoversRequest = cached != null &&
                !hasSelectedIds &&
                now - cachedAt < TTL_MS &&
                cachedLimit >= requestedLimit &&
                cachedTrendingLimit >= requestedTrendLimit

        if (!force && cacheCoversRequest) {
            callback(Result.success(cached))
            return
        }

        waiters.add(callback)
        if (loading) return
        loading = true
        ITWingSDK.fetchWallpapers(
            limit = if (hasSelectedIds) maxOf(limit, selectedWallpaperIds.size) else requestedLimit,
            trendingLimit = requestedTrendLimit,
            sort = sort,
            selectedWallpaperIds = selectedWallpaperIds,
            callback = object : ITWingWallpapersCallback() {
                override fun onLoaded(response: ITWingWallpaperResponse) {
                    if (!hasSelectedIds) {
                        cachedAt = System.currentTimeMillis()
                        cachedLimit = requestedLimit
                        cachedTrendingLimit = requestedTrendLimit
                        this@WallpaperResponseCache.response = response
                    }
                    loading = false
                    drain(Result.success(response))
                }

                override fun onError(error: String) {
                    loading = false
                    val stale = this@WallpaperResponseCache.response
                    if (stale != null) {
                        drain(Result.success(stale))
                    } else {
                        drain(Result.failure(IllegalStateException(error.ifBlank { "Wallpapers unavailable" })))
                    }
                }
            },
        )
    }

    fun markViewTracked(wallpaperId: String): Boolean = trackedViews.add(wallpaperId)

    private fun drain(result: Result<ITWingWallpaperResponse>) {
        val next = waiters.toList()
        waiters.clear()
        next.forEach { it(result) }
    }
}

open class ITWingWallpapersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val style = WallpaperUiStyle()
    private val recyclerView = RecyclerView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        clipToPadding = false
    }
    private val emptyText = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "No wallpapers available"
        setTextColor(0xFF6B7280.toInt())
        visibility = GONE
    }
    private val wallpaperAdapter = WallpaperAdapter(
        style = style,
        onClick = { view, item ->
            handleWallpaperClick(view, item)
        },
    )
    private val placeholderAdapter = PlaceholderAdapter(style)

    private var categorySlug: String? = null
    private var categoryId: String? = null
    private var limit = 100
    private var trendingLimit: Int? = null
    private var sort: String? = null
    private var showTrending = false
    private var premiumUnlockPlacement = "rewarded"
    private var premiumUnlockInProgress = false
    private var lastErrorDialogAt = 0L
    private var clickListener: ((ITWingWallpaperItem) -> Unit)? = null
    private var placementName: String? = null
    private var selectedWallpaperIds: List<String> = emptyList()

    init {
        readWallpaperAttrs(attrs)
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            emptyText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        configureLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val needsWrapHeight = mode == MeasureSpec.UNSPECIFIED ||
                (mode == MeasureSpec.AT_MOST && layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT)
        if (needsWrapHeight) {
            val margin = style.itemSpacingPx ?: dp(style.itemSpacingDp)
            val desiredRows = if (style.horizontal) 1 else 2
            val desiredHeight =
                ((style.itemHeightPx ?: dp(style.itemHeightDp)) + margin * 2) * desiredRows
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(
                    desiredHeight.coerceAtLeast(dp(72)),
                    MeasureSpec.EXACTLY
                )
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyRemotePlacement()
        if (wallpaperAdapter.itemCount == 0) reload()
    }

    fun setCategorySlug(value: String?) {
        val next = value?.takeIf { it.isNotBlank() }
        if (categorySlug == next && categoryId == null && wallpaperAdapter.itemCount > 0) return
        categorySlug = next
        categoryId = null
        reload()
    }

    fun setCategoryId(value: String?) {
        val next = value?.takeIf { it.isNotBlank() }
        if (categoryId == next && categorySlug == null && wallpaperAdapter.itemCount > 0) return
        categoryId = next
        categorySlug = null
        reload()
    }

    fun showAllWallpapers() {
        categoryId = null
        categorySlug = null
        reload()
    }

    fun setLimit(value: Int) {
        limit = value.coerceIn(1, 200)
        reload()
    }

    fun setSort(value: String?) {
        sort = value?.takeIf { it.isNotBlank() }
        reload()
    }

    fun setTopTrending(enabled: Boolean) {
        showTrending = enabled
        if (enabled) style.horizontal = true
        configureLayout()
        reload()
    }

    fun setTrendingLimit(value: Int?) {
        trendingLimit = value?.coerceIn(1, 200)
        reload()
    }

    fun setGridColumns(value: Int) {
        style.columns = value.coerceIn(1, 8)
        configureLayout()
    }

    fun setHorizontal(enabled: Boolean) {
        style.horizontal = enabled
        configureLayout()
    }

    fun setItemSizeDp(width: Int, height: Int) {
        style.itemWidthDp = width.coerceAtLeast(72)
        style.itemHeightDp = height.coerceAtLeast(72)
        notifyStyleChanged()
    }

    fun setItemSpacingDp(value: Int) {
        style.itemSpacingDp = value.coerceAtLeast(0)
        notifyStyleChanged()
    }

    fun setItemCornerRadiusDp(value: Int) {
        style.cornerRadiusDp = value.coerceAtLeast(0)
        notifyStyleChanged()
    }

    fun setItemStroke(widthDp: Int, color: Int) {
        style.strokeWidthDp = widthDp.coerceAtLeast(0)
        style.strokeColor = color
        notifyStyleChanged()
    }

    fun setItemBackgroundColor(color: Int) {
        style.backgroundColor = color
        notifyStyleChanged()
    }

    fun setShowTitle(enabled: Boolean) {
        style.showTitle = enabled
        notifyStyleChanged()
    }

    fun setTextColor(color: Int) {
        style.textColor = color
        notifyStyleChanged()
    }

    fun setCustomItemLayout(layoutRes: Int, binder: ITWingWallpaperItemBinder?) {
        wallpaperAdapter.customLayoutRes = layoutRes.takeIf { it != 0 }
        wallpaperAdapter.customBinder = binder
        notifyStyleChanged()
    }

    fun setOnWallpaperClickListener(listener: ((ITWingWallpaperItem) -> Unit)?) {
        clickListener = listener
    }

    fun setPremiumUnlockPlacement(placement: String?) {
        premiumUnlockPlacement = placement?.takeIf { it.isNotBlank() } ?: "rewarded"
    }

    fun reload() {
        applyRemotePlacement()
        if (limit <= 0) {
            recyclerView.adapter = wallpaperAdapter
            wallpaperAdapter.submit(emptyList())
            emptyText.visibility = VISIBLE
            return
        }
        if (!NetworkState.isOnline(context)) {
            showWallpaperItems(emptyList())
            emptyText.text = NetworkState.offlineMessage()
            emptyText.visibility = VISIBLE
            showFeatureError(NetworkState.offlineMessage()) { reload() }
            return
        }
        renderLoading()
        WallpaperResponseCache.load(
            limit = limit,
            trendingLimit = trendingLimit,
            sort = sort,
            selectedWallpaperIds = selectedWallpaperIds,
            force = false,
        ) { result ->
            runOnMain {
                result.onSuccess { response ->
                    val source = if (showTrending) response.trending else response.wallpapers
                    val items = source
                        .filterForCategory(categoryId, categorySlug)
                        .take(
                            if (showTrending) (trendingLimit
                                ?: response.topLimit).coerceAtLeast(1) else limit
                        )
                    emptyText.visibility = if (items.isEmpty()) VISIBLE else GONE
                    preloadImagesThenShow(items)
                    items.take(8).forEach {
                        if (WallpaperResponseCache.markViewTracked(it.id)) {
                            ITWingSDK.trackWallpaperView(it.id)
                        }
                    }
                    preloadImages(items)
                }.onFailure { throwable ->
                    showWallpaperItems(emptyList())
                    emptyText.text = throwable.message ?: "Wallpapers unavailable"
                    emptyText.visibility = VISIBLE
                    showFeatureError(throwable.message ?: "Wallpaper content could not be loaded.") { reload() }
                }
            }
        }
    }

    private fun handleWallpaperClick(sourceView: View, item: ITWingWallpaperItem) {
        if (!item.premium || hasPremiumAccess() || isWallpaperUnlocked(item.id)) {
            openWallpaper(item)
            return
        }
        if (premiumUnlockInProgress) return
        val activity = sourceView.context.findActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(
                sourceView.context,
                "Premium wallpaper requires rewarded ad.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        var rewarded = false
        premiumUnlockInProgress = true
        mainHandler.postDelayed({ premiumUnlockInProgress = false }, 45_000L)
        ITWingSDK.showRewarded(
            activity = activity,
            placement = premiumUnlockPlacement,
            onReward = { rewarded = true },
            onComplete = {
                premiumUnlockInProgress = false
                if (rewarded) {
                    markWallpaperUnlocked(item.id)
                    openWallpaper(item)
                } else {
                    Toast.makeText(
                        activity,
                        "Reward not completed. Wallpaper remains locked.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onUnavailableOrSkipped = {
                premiumUnlockInProgress = false
            },
        )
    }

    private fun hasPremiumAccess(): Boolean = ITWingSDK.getCurrentSubscription()?.active == true

    private fun openWallpaper(item: ITWingWallpaperItem) {
        ITWingSDK.trackWallpaperClick(item.id, mapOf("premium" to item.premium))
        clickListener?.invoke(item)
    }

    private fun isWallpaperUnlocked(wallpaperId: String): Boolean {
        return context.applicationContext
            .getSharedPreferences("itwing_wallpaper_unlocks", Context.MODE_PRIVATE)
            .getBoolean(wallpaperId, false)
    }

    private fun markWallpaperUnlocked(wallpaperId: String) {
        context.applicationContext
            .getSharedPreferences("itwing_wallpaper_unlocks", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(wallpaperId, true)
            .apply()
    }

    protected fun applyTrendDefaults() {
        showTrending = true
        style.horizontal = true
        style.itemWidthDp = 132
        style.itemHeightDp = 170
        configureLayout()
    }

    private fun readWallpaperAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val a = context.obtainStyledAttributes(attrs, R.styleable.ITWingWallpapersView)
        placementName = a.getString(R.styleable.ITWingWallpapersView_ITWingWallpaperPlacement)
            ?.takeIf { it.isNotBlank() }
        style.columns =
            a.getInt(R.styleable.ITWingWallpapersView_ITWingWallpaperColumns, style.columns)
                .coerceIn(1, 8)
        style.horizontal = a.getBoolean(
            R.styleable.ITWingWallpapersView_ITWingWallpaperHorizontal,
            style.horizontal
        )
        limit =
            a.getInt(R.styleable.ITWingWallpapersView_ITWingWallpaperLimit, limit).coerceIn(1, 500)
        trendingLimit = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperTrendingLimit,
            trendingLimit ?: 0
        ).takeIf { it > 0 }?.coerceIn(1, 200)
        sort = a.getString(R.styleable.ITWingWallpapersView_ITWingWallpaperSort)
            ?.takeIf { it.isNotBlank() }
        premiumUnlockPlacement =
            a.getString(R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumUnlockPlacement)
                ?.takeIf { it.isNotBlank() }
                ?: premiumUnlockPlacement
        showTrending =
            a.getBoolean(R.styleable.ITWingWallpapersView_ITWingWallpaperTopTrending, showTrending)
        if (showTrending) style.horizontal = true
        style.itemWidthPx =
            a.getDimensionPixelSize(R.styleable.ITWingWallpapersView_ITWingWallpaperItemWidth, -1)
                .takeIf { it > 0 }
        style.itemHeightPx =
            a.getDimensionPixelSize(R.styleable.ITWingWallpapersView_ITWingWallpaperItemHeight, -1)
                .takeIf { it > 0 }
        style.itemSpacingPx =
            a.getDimensionPixelSize(R.styleable.ITWingWallpapersView_ITWingWallpaperItemSpacing, -1)
                .takeIf { it >= 0 }
        style.cornerRadiusPx = a.getDimensionPixelSize(
            R.styleable.ITWingWallpapersView_ITWingWallpaperCornerRadius,
            -1
        ).takeIf { it >= 0 }
        style.itemWidthDp =
            a.getInt(R.styleable.ITWingWallpapersView_ITWingWallpaperItemWidthDp, style.itemWidthDp)
                .coerceAtLeast(72)
        style.itemHeightDp = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperItemHeightDp,
            style.itemHeightDp
        ).coerceAtLeast(72)
        style.itemSpacingDp = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperItemSpacingDp,
            style.itemSpacingDp
        ).coerceAtLeast(0)
        style.cornerRadiusDp = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperCornerRadiusDp,
            style.cornerRadiusDp
        ).coerceAtLeast(0)
        style.showTitle =
            a.getBoolean(R.styleable.ITWingWallpapersView_ITWingWallpaperShowTitle, style.showTitle)
        style.textColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperTitleTextColor,
            style.textColor,
        )
        style.mutedTextColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperMutedTextColor,
            style.mutedTextColor,
        )
        style.titlePosition = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperTitlePosition,
            style.titlePosition.ordinal,
        ).toTextPosition()
        style.premiumMode = a.getInt(
            R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumMode,
            style.premiumMode.ordinal,
        ).toPremiumMode()
        style.premiumBadgeColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumColor,
            style.premiumBadgeColor,
        )
        style.premiumBadgeTextColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumTextColor,
            style.premiumBadgeTextColor,
        )
        style.premiumIconRes = a.getResourceId(
            R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIcon,
            style.premiumIconRes,
        )
        if (a.hasValue(R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIconTint)) {
            style.premiumIconTint = a.getColor(
                R.styleable.ITWingWallpapersView_ITWingWallpaperPremiumIconTint,
                style.premiumIconTint ?: style.premiumBadgeTextColor,
            )
        }
        style.backgroundColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperItemBackgroundColor,
            style.backgroundColor
        )
        style.strokeColor = a.getColor(
            R.styleable.ITWingWallpapersView_ITWingWallpaperItemStrokeColor,
            style.strokeColor
        )
        wallpaperAdapter.customLayoutRes =
            a.getResourceId(R.styleable.ITWingWallpapersView_ITWingWallpaperItemLayout, 0)
                .takeIf { it != 0 }
        a.recycle()
    }

    private fun applyRemotePlacement() {
        val remote = placementName
            ?.let { ITWingSDK.currentConfig().wallpapers.placements[it] }
            ?: return
        applyRemoteWallpaperPlacement(remote)
        configureLayout()
        notifyStyleChanged()
    }

    private fun applyRemoteWallpaperPlacement(remote: WallpaperPlacementConfig) {
        if (!remote.enabled) {
            limit = 0
            return
        }
        remote.limit?.let { limit = it.coerceIn(1, 500) }
        remote.trendingLimit?.let { trendingLimit = it.coerceIn(1, 200) }
        remote.sort?.takeIf { it.isNotBlank() }?.let { sort = it }
        remote.columns?.let { style.columns = it.coerceIn(1, 8) }
        remote.horizontal?.let { style.horizontal = it }
        remote.itemWidthDp?.let {
            style.itemWidthDp = it.coerceAtLeast(48)
            style.itemWidthPx = null
        }
        remote.itemHeightDp?.let {
            style.itemHeightDp = it.coerceAtLeast(48)
            style.itemHeightPx = null
        }
        remote.itemSpacingDp?.let {
            style.itemSpacingDp = it.coerceAtLeast(0)
            style.itemSpacingPx = null
        }
        remote.cornerRadiusDp?.let {
            style.cornerRadiusDp = it.coerceAtLeast(0)
            style.cornerRadiusPx = null
        }
        remote.showTitle?.let { style.showTitle = it }
        remote.premiumUnlockPlacement?.takeIf { it.isNotBlank() }
            ?.let { premiumUnlockPlacement = it }
        selectedWallpaperIds =
            if (remote.contentSource == "manual") remote.selectedWallpaperIds else emptyList()
        if (remote.contentSource == "category" && !remote.categoryId.isNullOrBlank()) {
            categoryId = remote.categoryId
            categorySlug = null
        } else if (remote.contentSource == "entire_library" || remote.contentSource == "manual") {
            categoryId = null
            categorySlug = null
        }
        if (remote.type == "top_trends") {
            showTrending = true
            style.horizontal = true
        }
    }

    private fun configureLayout() {
        recyclerView.layoutManager = if (style.horizontal) {
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        } else {
            GridLayoutManager(context, style.columns)
        }
        recyclerView.adapter = wallpaperAdapter
    }

    private fun renderLoading() {
        if (wallpaperAdapter.itemCount > 0) return
        emptyText.visibility = GONE
        recyclerView.animate().cancel()
        recyclerView.alpha = 1f
        recyclerView.adapter = placeholderAdapter
        placeholderAdapter.count = if (style.horizontal) 6 else (style.columns * 3).coerceAtLeast(6)
    }

    private fun showWallpaperItems(items: List<ITWingWallpaperItem>) {
        val wasLoading = recyclerView.adapter === placeholderAdapter
        recyclerView.animate().cancel()
        recyclerView.adapter = wallpaperAdapter
        if (wasLoading && items.isNotEmpty()) {
            recyclerView.alpha = 0f
        }
        wallpaperAdapter.submit(items)
        if (wasLoading && items.isNotEmpty()) {
            recyclerView.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()
        } else {
            recyclerView.alpha = 1f
        }
    }

    private fun preloadImagesThenShow(items: List<ITWingWallpaperItem>) {
        if (items.isEmpty()) {
            showWallpaperItems(items)
            return
        }
        val visibleCount = if (style.horizontal) 4 else (style.columns * 2).coerceAtLeast(4)
        val targets = items.take(visibleCount)
        val remaining = AtomicInteger(targets.size)
        val finished = AtomicBoolean(false)

        fun finish() {
            if (finished.compareAndSet(false, true) && isAttachedToWindow) {
                showWallpaperItems(items)
            }
        }

        mainHandler.postDelayed({ finish() }, 3_000L)
        targets.forEach { item ->
            val imageUrl = item.imageUrl.toUsableMediaUrl()
            val previewUrl = (item.thumbnailUrl ?: item.imageUrl).toUsableMediaUrl()
            Glide.with(this)
                .load(previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        Glide.with(this@ITWingWallpapersView)
                            .load(imageUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .listener(object : RequestListener<Drawable> {
                                override fun onLoadFailed(
                                    e: GlideException?,
                                    model: Any?,
                                    target: Target<Drawable>,
                                    isFirstResource: Boolean,
                                ): Boolean {
                                    if (remaining.decrementAndGet() <= 0) finish()
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: Drawable,
                                    model: Any,
                                    target: Target<Drawable>?,
                                    dataSource: DataSource,
                                    isFirstResource: Boolean,
                                ): Boolean {
                                    if (remaining.decrementAndGet() <= 0) finish()
                                    return false
                                }
                            })
                            .preload(style.itemWidthPx ?: dp(style.itemWidthDp), style.itemHeightPx ?: dp(style.itemHeightDp))
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (remaining.decrementAndGet() <= 0) finish()
                        return false
                    }
                })
                .preload(style.itemWidthPx ?: dp(style.itemWidthDp), style.itemHeightPx ?: dp(style.itemHeightDp))
        }
    }

    private fun showFeatureError(reason: String, onRetry: () -> Unit) {
        val activity = context.findActivity() ?: return
        val now = System.currentTimeMillis()
        if (now - lastErrorDialogAt < 5_000L || activity.isFinishing || activity.isDestroyed) return
        lastErrorDialogAt = now
        ITWingSDK.showSdkFeatureError(
            context = activity,
            feature = "Wallpaper content",
            reason = reason.ifBlank {
                "Wallpaper content could not be loaded. Check your internet connection and try again."
            },
            onRetry = onRetry,
        )
    }

    private fun notifyStyleChanged() {
        wallpaperAdapter.notifyDataSetChanged()
        placeholderAdapter.notifyDataSetChanged()
    }

    private fun preloadImages(items: List<ITWingWallpaperItem>) {
        items.take(20).forEach { item ->
            Glide.with(this)
                .load((item.thumbnailUrl ?: item.imageUrl).toUsableMediaUrl())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload(
                    style.itemWidthPx ?: dp(style.itemWidthDp),
                    style.itemHeightPx ?: dp(style.itemHeightDp)
                )
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class WallpaperAdapter(
        private val style: WallpaperUiStyle,
        private val onClick: (View, ITWingWallpaperItem) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<ITWingWallpaperItem>()
        var customLayoutRes: Int? = null
        var customBinder: ITWingWallpaperItemBinder? = null

        init {
            setHasStableIds(true)
        }

        override fun getItemViewType(position: Int): Int = if (customLayoutRes != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = customLayoutRes
            if (viewType == 1 && layout != null) {
                val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
                return CustomHolder(view)
            }
            return DefaultWallpaperHolder(DefaultWallpaperCard(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            holder.itemView.layoutParams =
                holder.itemView.layoutParams?.applyStyle(holder.itemView, style)
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).applyStyle(holder.itemView, style)
            holder.itemView.setOnClickListener { onClick(holder.itemView, item) }
            if (holder is DefaultWallpaperHolder) {
                holder.card.bind(item, style)
            } else {
                if (customBinder != null) {
                    customBinder?.bind(holder.itemView, item, position)
                } else {
                    holder.itemView.bindWallpaperItemByCommonIds(item, style)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID

        fun submit(next: List<ITWingWallpaperItem>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }
    }

    private class PlaceholderAdapter(private val style: WallpaperUiStyle) :
        RecyclerView.Adapter<PlaceholderHolder>() {
        var count = 6
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceholderHolder {
            return PlaceholderHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.items_shimmer, parent, false)
            )
        }

        override fun onBindViewHolder(holder: PlaceholderHolder, position: Int) {
            holder.itemView.layoutParams =
                holder.itemView.layoutParams?.applyStyle(holder.itemView, style)
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).applyStyle(holder.itemView, style)
        }

        override fun getItemCount(): Int = count
    }

    private class CustomHolder(view: View) : RecyclerView.ViewHolder(view)
    private class PlaceholderHolder(view: View) : RecyclerView.ViewHolder(view)
    private class DefaultWallpaperHolder(val card: DefaultWallpaperCard) :
        RecyclerView.ViewHolder(card)
}

class ITWingTopTrendsWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ITWingWallpapersView(context, attrs, defStyleAttr) {
    init {
        applyTrendDefaults()
    }
}

class ITWingWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ITWingWallpapersView(context, attrs, defStyleAttr)

class ITWingWallpaperCategoriesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    enum class DisplayMode { TEXT, IMAGE, BOTH }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recyclerView = RecyclerView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    }
    private val style =
        WallpaperUiStyle(horizontal = true, itemWidthDp = 116, itemHeightDp = 92, showTitle = true)
    private val adapter: CategoryAdapter
    private var linkedWallpaperView: ITWingWallpapersView? = null
    private var linkedWallpaperViewId: Int = 0
    private var placementName: String? = null
    private var placementEnabled = true
    private var visibleCategoryIds: Set<String> = emptySet()
    private var selectedCategoryId: String? = null
    private var selectedCategorySlug: String? = null
    private var clickListener: ((ITWingWallpaperCategory) -> Unit)? = null

    init {
        adapter = CategoryAdapter(style) { category ->
            selectedCategoryId = category.id.takeIf { it.isNotBlank() }
            selectedCategorySlug = category.slug.takeIf { it.isNotBlank() }
            adapter.select(category.id)
            if (category.id.isBlank()) linkedWallpaperView?.showAllWallpapers() else linkedWallpaperView?.setCategoryId(
                category.id
            )
            clickListener?.invoke(category)
        }
        readCategoryAttrs(attrs)
        recyclerView.adapter = adapter
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val needsWrapHeight = mode == MeasureSpec.UNSPECIFIED ||
                (mode == MeasureSpec.AT_MOST && layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT)
        if (needsWrapHeight) {
            val margin = style.itemSpacingPx ?: dpLocal(style.itemSpacingDp)
            val desiredRows = if (style.horizontal) 1 else 2
            val desiredHeight =
                ((style.itemHeightPx ?: dpLocal(style.itemHeightDp)) + margin * 2) * desiredRows
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(
                    desiredHeight.coerceAtLeast(dpLocal(48)),
                    MeasureSpec.EXACTLY
                )
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyRemotePlacement()
        if (linkedWallpaperView == null && linkedWallpaperViewId != 0) {
            post {
                rootView?.findViewById<ITWingWallpapersView>(linkedWallpaperViewId)
                    ?.let { attachWallpapersView(it) }
            }
        }
        if (adapter.itemCount == 0) reload()
    }

    fun attachWallpapersView(view: ITWingWallpapersView?) {
        linkedWallpaperView = view
        if (selectedCategoryId.isNullOrBlank()) view?.showAllWallpapers() else view?.setCategoryId(
            selectedCategoryId
        )
    }

    fun setDisplayMode(mode: DisplayMode) {
        adapter.displayMode = mode
        adapter.notifyDataSetChanged()
    }

    fun setHorizontal(enabled: Boolean) {
        style.horizontal = enabled
        recyclerView.layoutManager = if (enabled) {
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        } else {
            GridLayoutManager(context, style.columns)
        }
    }

    fun setGridColumns(value: Int) {
        style.columns = value.coerceIn(1, 8)
        if (!style.horizontal) recyclerView.layoutManager =
            GridLayoutManager(context, style.columns)
    }

    fun setItemSizeDp(width: Int, height: Int) {
        style.itemWidthDp = width.coerceAtLeast(64)
        style.itemHeightDp = height.coerceAtLeast(48)
        adapter.notifyDataSetChanged()
    }

    fun setItemColors(selectedColor: Int, textColor: Int, backgroundColor: Int = Color.WHITE) {
        style.selectedColor = selectedColor
        style.textColor = textColor
        style.backgroundColor = backgroundColor
        adapter.notifyDataSetChanged()
    }

    fun setCustomItemLayout(layoutRes: Int, binder: ITWingWallpaperCategoryBinder?) {
        adapter.customLayoutRes = layoutRes.takeIf { it != 0 }
        adapter.customBinder = binder
        adapter.notifyDataSetChanged()
    }

    fun setOnCategoryClickListener(listener: ((ITWingWallpaperCategory) -> Unit)?) {
        clickListener = listener
    }

    fun reload() {
        applyRemotePlacement()
        if (!placementEnabled) {
            adapter.submit(emptyList())
            return
        }
        if (!NetworkState.isOnline(context)) {
            val all = ITWingWallpaperCategory("", "All", "", "All wallpapers", null, -1)
            adapter.submit(listOf(all))
            adapter.select("")
            ITWingSDK.showSdkFeatureError(context, "Wallpaper categories", NetworkState.offlineMessage()) { reload() }
            return
        }
        adapter.submitPlaceholders()
        WallpaperResponseCache.load(
            limit = 300,
            trendingLimit = 50,
            sort = null,
            selectedWallpaperIds = emptyList(),
            force = false,
        ) { result ->
            runOnMain {
                result.onSuccess { response ->
                    val all = ITWingWallpaperCategory(
                        id = "",
                        name = "All",
                        slug = "",
                        description = "All wallpapers",
                        imageUrl = null,
                        sortOrder = -1,
                    )
                    val remoteCategories = if (visibleCategoryIds.isEmpty()) {
                        response.categories
                    } else {
                        response.categories.filter { it.id in visibleCategoryIds }
                    }
                    val categories = listOf(all) + remoteCategories
                    if (!selectedCategoryId.isNullOrBlank() && remoteCategories.none { it.id == selectedCategoryId }) {
                        selectedCategoryId = null
                        selectedCategorySlug = null
                    }
                    adapter.submit(categories)
                    adapter.select(selectedCategoryId ?: "")
                    if (selectedCategoryId.isNullOrBlank()) linkedWallpaperView?.showAllWallpapers()
                    categories.mapNotNull { it.imageUrl }.take(12).forEach { url ->
                        Glide.with(this).load(url.toUsableMediaUrl())
                            .diskCacheStrategy(DiskCacheStrategy.ALL).preload()
                    }
                }.onFailure {
                    val all = ITWingWallpaperCategory("", "All", "", "All wallpapers", null, -1)
                    adapter.submit(listOf(all))
                    adapter.select("")
                }
            }
        }
    }

    private fun readCategoryAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val a = context.obtainStyledAttributes(attrs, R.styleable.ITWingWallpaperCategoriesView)
        placementName =
            a.getString(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperPlacement)
                ?.takeIf { it.isNotBlank() }
                ?: a.getString(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryPlacement)
                    ?.takeIf { it.isNotBlank() }
        style.columns = a.getInt(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryColumns,
            style.columns
        ).coerceIn(1, 8)
        style.horizontal = a.getBoolean(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryHorizontal,
            true
        )
        linkedWallpaperViewId =
            a.getResourceId(R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperLinkedView, 0)
        style.itemWidthPx = a.getDimensionPixelSize(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemWidth,
            -1
        ).takeIf { it > 0 }
        style.itemHeightPx = a.getDimensionPixelSize(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemHeight,
            -1
        ).takeIf { it > 0 }
        style.itemWidthDp = a.getInt(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemWidthDp,
            style.itemWidthDp
        ).coerceAtLeast(64)
        style.itemHeightDp = a.getInt(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemHeightDp,
            style.itemHeightDp
        ).coerceAtLeast(48)
        style.selectedColor = a.getColor(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategorySelectedColor,
            style.selectedColor
        )
        style.textColor = a.getColor(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryTextColor,
            style.textColor
        )
        style.mutedTextColor = a.getColor(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryMutedTextColor,
            style.mutedTextColor
        )
        style.showTitle = a.getBoolean(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryShowTitle,
            style.showTitle
        )
        style.titlePosition = a.getInt(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryTextPosition,
            style.titlePosition.ordinal,
        ).toTextPosition()
        style.selectedDrawableRes = a.getResourceId(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategorySelectedDrawable,
            0
        )
        style.unselectedDrawableRes = a.getResourceId(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryUnselectedDrawable,
            0
        )
        adapter.customLayoutRes = a.getResourceId(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryItemLayout,
            0
        ).takeIf { it != 0 }
        val displayMode = a.getInt(
            R.styleable.ITWingWallpaperCategoriesView_ITWingWallpaperCategoryDisplayMode,
            2
        )
        adapter.displayMode = DisplayMode.entries.getOrElse(displayMode) { DisplayMode.BOTH }
        a.recycle()
        setHorizontal(style.horizontal)
    }

    private fun applyRemotePlacement() {
        val remote = placementName
            ?.let { ITWingSDK.currentConfig().wallpapers.placements[it] }
            ?: return
        if (!remote.enabled) {
            placementEnabled = false
            adapter.submit(emptyList())
            return
        }
        placementEnabled = true
        remote.columns?.let { style.columns = it.coerceIn(1, 8) }
        remote.horizontal?.let { style.horizontal = it }
        remote.itemWidthDp?.let {
            style.itemWidthDp = it.coerceAtLeast(48)
            style.itemWidthPx = null
        }
        remote.itemHeightDp?.let {
            style.itemHeightDp = it.coerceAtLeast(48)
            style.itemHeightPx = null
        }
        remote.itemSpacingDp?.let {
            style.itemSpacingDp = it.coerceAtLeast(0)
            style.itemSpacingPx = null
        }
        remote.cornerRadiusDp?.let {
            style.cornerRadiusDp = it.coerceAtLeast(0)
            style.cornerRadiusPx = null
        }
        remote.categoryDisplayMode?.let { mode ->
            adapter.displayMode = when (mode.lowercase()) {
                "image" -> DisplayMode.IMAGE
                "both" -> DisplayMode.BOTH
                else -> DisplayMode.TEXT
            }
        }
        remote.showTitle?.let { style.showTitle = it }
        visibleCategoryIds = remote.selectedCategoryIds.filter { it.isNotBlank() }.toSet()
        setHorizontal(style.horizontal)
        adapter.notifyDataSetChanged()
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private class CategoryAdapter(
        private val style: WallpaperUiStyle,
        private val onClick: (ITWingWallpaperCategory) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<ITWingWallpaperCategory>()
        private var selectedId = ""
        private var placeholders = false
        var displayMode = DisplayMode.BOTH
        var customLayoutRes: Int? = null
        var customBinder: ITWingWallpaperCategoryBinder? = null

        init {
            setHasStableIds(true)
        }

        override fun getItemViewType(position: Int): Int = when {
            placeholders -> 0
            customLayoutRes != null -> 2
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                return PlaceholderHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.items_shimmer, parent, false)
                )
            }
            val layout = customLayoutRes
            if (viewType == 2 && layout != null) return CustomHolder(
                LayoutInflater.from(parent.context).inflate(layout, parent, false)
            )
            return DefaultCategoryHolder(DefaultCategoryCard(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.layoutParams =
                holder.itemView.layoutParams?.applyStyle(holder.itemView, style)
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).applyStyle(holder.itemView, style)
            if (placeholders) {
                return
            }
            val item = items[position]
            val selected = item.id == selectedId
            holder.itemView.setOnClickListener { onClick(item) }
            if (holder is DefaultCategoryHolder) {
                holder.card.bind(item, selected, displayMode, style)
            } else {
                if (customBinder != null) {
                    customBinder?.bind(holder.itemView, item, selected, position)
                } else {
                    holder.itemView.bindWallpaperCategoryByCommonIds(
                        item,
                        selected,
                        displayMode,
                        style
                    )
                }
            }
        }

        override fun getItemCount(): Int = if (placeholders) 6 else items.size

        override fun getItemId(position: Int): Long = if (placeholders) {
            -1L - position
        } else {
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID
        }

        fun submit(next: List<ITWingWallpaperCategory>) {
            placeholders = false
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        fun submitPlaceholders() {
            placeholders = true
            notifyDataSetChanged()
        }

        fun select(id: String) {
            selectedId = id
            notifyDataSetChanged()
        }
    }

    private class CustomHolder(view: View) : RecyclerView.ViewHolder(view)
    private class PlaceholderHolder(view: View) : RecyclerView.ViewHolder(view)
    private class DefaultCategoryHolder(val card: DefaultCategoryCard) :
        RecyclerView.ViewHolder(card)
}

private fun List<ITWingWallpaperItem>.filterForCategory(
    categoryId: String?,
    categorySlug: String?,
): List<ITWingWallpaperItem> {
    val normalizedId = categoryId?.takeIf { it.isNotBlank() }
    val normalizedSlug = categorySlug?.takeIf { it.isNotBlank() }
    if (normalizedId == null && normalizedSlug == null) return this
    return filter { item ->
        (normalizedId != null && item.categoryId == normalizedId) ||
                (normalizedSlug != null && item.categorySlug.equals(
                    normalizedSlug,
                    ignoreCase = true
                ))
    }
}

private class WallpaperSkeletonCard(context: Context) : LinearLayout(context) {
    private val imageBlock = View(context)
    private val lineOne = View(context)
    private val lineTwo = View(context)

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(imageBlock, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(lineOne, LayoutParams(LayoutParams.MATCH_PARENT, dp(10)).apply {
            setMargins(0, dp(8), 0, 0)
        })
        addView(
            lineTwo,
            LayoutParams((resources.displayMetrics.density * 72).roundToInt(), dp(9)).apply {
                setMargins(0, dp(7), 0, 0)
            })
    }

    fun bind(style: WallpaperUiStyle, position: Int) {
        background = rounded(0xFFFFFFFF.toInt(), 0x11000000, 1, radiusPx(style))
        imageBlock.background =
            rounded(0xFFE5E7EB.toInt(), 0, 0, (radiusPx(style) - dp(2)).coerceAtLeast(0))
        lineOne.background = rounded(0xFFE5E7EB.toInt(), 0, 0, dp(999))
        lineTwo.background = rounded(0xFFF1F5F9.toInt(), 0, 0, dp(999))
        alpha = if (position % 2 == 0) 0.82f else 0.62f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun startPulse() {
        animate()
            .alpha(0.45f)
            .setDuration(650L)
            .withEndAction {
                if (isAttachedToWindow) {
                    animate().alpha(0.88f).setDuration(650L).withEndAction {
                        if (isAttachedToWindow) startPulse()
                    }.start()
                }
            }
            .start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private class DefaultWallpaperCard(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), dp(4), dp(8), dp(6))
    }
    private val premiumBadge = TextView(context).apply {
        text = "PREMIUM"
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val premiumIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(5), dp(5), dp(5), dp(5))
        visibility = GONE
    }

    init {
        clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            premiumBadge,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dp(8), dp(8), 0)
            })
        addView(
            premiumIcon,
            LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(8), dp(8), 0)
            },
        )
        addView(
            title,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
    }

    fun bind(item: ITWingWallpaperItem, style: WallpaperUiStyle) {
        background = rounded(
            style.backgroundColor,
            style.strokeColor,
            dp(style.strokeWidthDp),
            radiusPx(style)
        )
        title.visibility = if (style.showTitle) VISIBLE else GONE
        title.text = item.title
        title.setTextColor(style.textColor)
        title.applyTitlePosition(style)
        val showPremium = item.premium && !hasPremiumPurchase() && style.premiumMode != PremiumMode.HIDDEN
        premiumBadge.visibility = if (showPremium && style.premiumMode == PremiumMode.TEXT) VISIBLE else GONE
        premiumIcon.visibility = if (showPremium && style.premiumMode == PremiumMode.ICON) VISIBLE else GONE
        premiumBadge.text = if (style.premiumMode == PremiumMode.ICON) "★" else "PREMIUM"
        premiumBadge.setTextColor(style.premiumBadgeTextColor)
        premiumBadge.background = rounded(style.premiumBadgeColor, 0, 0, dp(999))
        premiumIcon.setImageResource(style.premiumIconRes.takeIf { it != 0 } ?: android.R.drawable.btn_star_big_on)
        premiumIcon.setColorFilter(style.premiumIconTint ?: style.premiumBadgeTextColor)
        premiumIcon.background = rounded(style.premiumBadgeColor, 0, 0, dp(999))
        val imageUrl = item.imageUrl.toUsableMediaUrl()
        val previewUrl = (item.thumbnailUrl ?: item.imageUrl).toUsableMediaUrl()
        image.visibility = VISIBLE
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        Glide.with(image)
            .load(previewUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(180))
            .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
            .error(
                Glide.with(image)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
                    .error(rounded(0xFFF3F4F6.toInt(), 0, 0, radiusPx(style))),
            )
            .into(image)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private class DefaultCategoryCard(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    init {
        clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            title,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            },
        )
    }

    fun bind(
        item: ITWingWallpaperCategory,
        selected: Boolean,
        mode: ITWingWallpaperCategoriesView.DisplayMode,
        style: WallpaperUiStyle,
    ) {
        background = style.categoryBackground(context, selected)
        title.text = item.name
        title.setTextColor(if (selected) style.selectedColor else style.textColor)
        title.applyTitlePosition(style)
        image.visibility =
            if (mode == ITWingWallpaperCategoriesView.DisplayMode.TEXT) GONE else VISIBLE
        title.visibility =
            if (!style.showTitle || mode == ITWingWallpaperCategoriesView.DisplayMode.IMAGE) GONE else VISIBLE
        if (item.imageUrl.isNullOrBlank()) {
            image.setImageDrawable(
                rounded(
                    style.selectedColor.withAlpha(20),
                    0,
                    0,
                    radiusPx(style)
                )
            )
        } else {
            Glide.with(image)
                .load(item.imageUrl.toUsableMediaUrl())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
                .into(image)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun View.bindWallpaperItemByCommonIds(item: ITWingWallpaperItem, style: WallpaperUiStyle) {
    val image = findCommonView<ImageView>(
        "itwing_wallpaper_image",
        "wallpaper_image",
        "imgWallpaper",
        "image",
    )
    val title = findCommonView<TextView>(
        "itwing_wallpaper_title",
        "wallpaper_title",
        "title",
    )
    val titleLayout = findCommonView<FrameLayout>(
        "itwing_wallpaper_title_layout",
        "wallpaper_title_layout",
        "titleLayout",
    )
    val badge = findCommonViewByIdOnly<TextView>(
        "itwing_wallpaper_premium",
        "wallpaper_premium",
        "premiumBadge",
    )
    val badgeIcon = findCommonViewByIdOnly<ImageView>(
        "itwing_wallpaper_premium_icon",
        "wallpaper_premium_icon",
        "premiumIcon",
    )
    val stats = findCommonViewByIdOnly<TextView>(
        "itwing_wallpaper_stats",
        "wallpaper_stats",
        "stats",
    )

    title?.text = item.title
    title?.setTextColor(style.textColor)
    title?.visibility = if (style.showTitle) View.VISIBLE else View.GONE
    title?.applyTitlePosition(style)
    titleLayout?.visibility = if (style.showTitle) View.VISIBLE else View.GONE
    stats?.text = "${item.stats.clicks} clicks"
    stats?.setTextColor(style.mutedTextColor)
    badge?.visibility = if (item.premium && !hasPremiumPurchase() && style.premiumMode == PremiumMode.TEXT) View.VISIBLE else View.GONE
    badge?.text = if (style.premiumMode == PremiumMode.ICON) "★" else (badge?.text?.takeIf { it.isNotBlank() } ?: "PREMIUM")
    badge?.setTextColor(style.premiumBadgeTextColor)
    badge?.background = rounded(style.premiumBadgeColor, 0, 0, dpLocal(999))
    badgeIcon?.visibility = if (item.premium && !hasPremiumPurchase() && style.premiumMode == PremiumMode.ICON) View.VISIBLE else View.GONE
    badgeIcon?.setImageResource(style.premiumIconRes.takeIf { it != 0 } ?: android.R.drawable.btn_star_big_on)
    badgeIcon?.setColorFilter(style.premiumIconTint ?: style.premiumBadgeTextColor)
    image?.let {
        val imageUrl = item.imageUrl.toUsableMediaUrl()
        val previewUrl = (item.thumbnailUrl ?: item.imageUrl).toUsableMediaUrl()
        it.visibility = View.VISIBLE
        it.scaleType = ImageView.ScaleType.CENTER_CROP
        Glide.with(it)
            .load(previewUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(180))
            .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
            .error(
                Glide.with(it)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
                    .error(rounded(0xFFF3F4F6.toInt(), 0, 0, radiusPx(style))),
            )
            .into(it)
    }
}

private fun View.bindWallpaperCategoryByCommonIds(
    item: ITWingWallpaperCategory,
    selected: Boolean,
    mode: ITWingWallpaperCategoriesView.DisplayMode,
    style: WallpaperUiStyle,
) {
    val declaredImage = findCommonViewByIdOnly<ImageView>(
        "itwing_category_image",
        "wallpaper_category_image",
        "categoryImage",
        "image",
    )
    val image = declaredImage ?: if (mode == ITWingWallpaperCategoriesView.DisplayMode.TEXT) {
        null
    } else {
        ensureAutoCategoryImageView()
    }
    val title = findCommonView<TextView>(
        "itwing_category_title",
        "wallpaper_category_title",
        "categoryTitle",
        "title",
    )
    title?.text = item.name
    title?.setTextColor(if (selected) style.selectedColor else style.textColor)
    title?.applyTitlePosition(style)
    image?.visibility = if (mode == ITWingWallpaperCategoriesView.DisplayMode.TEXT) View.GONE else View.VISIBLE
    title?.visibility = if (!style.showTitle) { View.GONE
    } else if (mode == ITWingWallpaperCategoriesView.DisplayMode.IMAGE && image != null) {
        View.GONE
    } else {
        View.VISIBLE
    }
    image?.let {
        if (item.imageUrl.isNullOrBlank()) {
            it.setImageDrawable(rounded(style.selectedColor.withAlpha(20), 0, 0, radiusPx(style)))
        } else {
            Glide.with(it)
                .load(item.imageUrl.toUsableMediaUrl())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .placeholder(rounded(0xFFE5E7EB.toInt(), 0, 0, radiusPx(style)))
                .into(it)
        }
    }
    background = style.categoryBackground(context, selected)
}

private fun Int.toTextPosition(): TextPosition = when (this) {
    0 -> TextPosition.TOP
    1 -> TextPosition.CENTER
    3 -> TextPosition.OUTSIDE_BOTTOM
    else -> TextPosition.BOTTOM
}

private fun Int.toPremiumMode(): PremiumMode = when (this) {
    1 -> PremiumMode.ICON
    2 -> PremiumMode.HIDDEN
    else -> PremiumMode.TEXT
}

private fun hasPremiumPurchase(): Boolean = ITWingSDK.getCurrentSubscription()?.active == true

private fun TextPosition.frameGravity(): Int = when (this) {
    TextPosition.TOP -> Gravity.TOP
    TextPosition.CENTER -> Gravity.CENTER
    TextPosition.BOTTOM,
    TextPosition.OUTSIDE_BOTTOM -> Gravity.BOTTOM
}

private fun TextPosition.textGravity(): Int = when (this) {
    TextPosition.CENTER -> Gravity.CENTER
    else -> Gravity.CENTER
}

private fun TextView.applyTitlePosition(style: WallpaperUiStyle) {
    gravity = style.titlePosition.textGravity()
    (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
        params.gravity = style.titlePosition.frameGravity()
        layoutParams = params
    }
}

private fun View.ensureAutoCategoryImageView(): ImageView? {
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child.tag == "itwing_auto_category_image" && child is ImageView) return child
    }
    val image = ImageView(context).apply {
        tag = "itwing_auto_category_image"
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    val params: ViewGroup.LayoutParams = when (group) {
        is FrameLayout -> FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        is LinearLayout -> if (group.orientation == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        else -> ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    group.addView(image, 0, params)
    return image
}

private inline fun <reified T : View> View.findCommonView(vararg names: String): T? {
    for (name in names) {
        val id = resources.getIdentifier(name, "id", context.packageName)
        if (id != 0) {
            val view = findViewById<View>(id)
            if (view is T) return view
        }
    }
    return findViewByType(T::class.java)
}

private inline fun <reified T : View> View.findCommonViewByIdOnly(vararg names: String): T? {
    for (name in names) {
        val id = resources.getIdentifier(name, "id", context.packageName)
        if (id != 0) {
            val view = findViewById<View>(id)
            if (view is T) return view
        }
    }
    return null
}

private fun <T : View> View.findViewByType(type: Class<T>): T? {
    if (type.isInstance(this)) return type.cast(this)
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val match = getChildAt(i).findViewByType(type)
            if (match != null) return match
        }
    }
    return null
}

private fun View.dpLocal(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

private fun View.radiusPx(style: WallpaperUiStyle): Int =
    style.cornerRadiusPx ?: dpLocal(style.cornerRadiusDp)

private fun WallpaperUiStyle.categoryBackground(context: Context, selected: Boolean) =
    when {
        selected && selectedDrawableRes != 0 -> ContextCompat.getDrawable(
            context,
            selectedDrawableRes
        )

        !selected && unselectedDrawableRes != 0 -> ContextCompat.getDrawable(
            context,
            unselectedDrawableRes
        )

        else -> rounded(
            fill = if (selected) selectedColor.withAlpha(28) else backgroundColor,
            strokeColor = if (selected) selectedColor else 0x14000000,
            strokeWidth = (context.resources.displayMetrics.density * if (selected) 2 else 1).roundToInt(),
            radius = cornerRadiusPx
                ?: (context.resources.displayMetrics.density * cornerRadiusDp).roundToInt(),
        )
    }

private fun ViewGroup.LayoutParams.applyStyle(
    view: View,
    style: WallpaperUiStyle
): ViewGroup.LayoutParams {
    val margin = style.itemSpacingPx ?: view.dp(style.itemSpacingDp)
    val width = if (style.horizontal) {
        style.itemWidthPx ?: view.dp(style.itemWidthDp)
    } else {
        ViewGroup.LayoutParams.MATCH_PARENT
    }
    val height = if (style.horizontal && style.itemHeightPx == null) {
        ViewGroup.LayoutParams.MATCH_PARENT
    } else {
        style.itemHeightPx ?: view.dp(style.itemHeightDp)
    }
    val params = when (this) {
        is RecyclerView.LayoutParams -> this
        else -> RecyclerView.LayoutParams(width, height)
    }
    params.width = width
    params.height = height
    params.setMargins(margin, margin, margin, margin)
    return params
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Int.withAlpha(alpha: Int): Int =
    Color.argb(alpha.coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))

private fun rounded(fill: Int, strokeColor: Int, strokeWidth: Int, radius: Int): GradientDrawable =
    GradientDrawable().apply {
        color = android.content.res.ColorStateList.valueOf(fill)
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
