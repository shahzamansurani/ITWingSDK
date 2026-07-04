package com.itwingtech.itwingsdk.media

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.itwingtech.itwingsdk.utils.NetworkState
import java.util.concurrent.CopyOnWriteArraySet

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
        setTextColor(Color.parseColor("#667085"))
        textSize = 14f
        text = "No media available"
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

    init {
        readAttrs(attrs)
        addView(shimmer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shimmer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            repeat(4) { addView(shimmerRow()) }
        })
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        recyclerView.adapter = adapter
        applyLayoutManager()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (adapter.itemCount == 0) reload()
    }

    fun reload() {
        showLoading()
        if (!NetworkState.isOnline(context)) {
            hideLoading()
            adapter.submit(emptyList())
            emptyView.text = NetworkState.offlineMessage()
            emptyView.visibility = VISIBLE
            ITWingSDK.showSdkFeatureError(context, "${mediaKind.featureTitle()} unavailable", NetworkState.offlineMessage()) { reload() }
            return
        }
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
                        submit(source.take(placement?.limit ?: limit))
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

    private fun submit(items: List<ITWingMediaItem>) {
        adapter.showTitle = showTitle
        adapter.customLayoutRes = customLayoutRes
        adapter.customBinder = customBinder
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
        val a = context.obtainStyledAttributes(attrs, R.styleable.ITWingMediaItemsView)
        placementName = a.getString(R.styleable.ITWingMediaItemsView_ITWingMediaPlacement)
        columns = a.getInt(R.styleable.ITWingMediaItemsView_ITWingMediaColumns, columns).coerceAtLeast(1)
        horizontal = a.getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaHorizontal, horizontal)
        limit = a.getInt(R.styleable.ITWingMediaItemsView_ITWingMediaLimit, limit).coerceIn(1, 500)
        showTrending = a.getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaTopTrending, showTrending)
        showTitle = a.getBoolean(R.styleable.ITWingMediaItemsView_ITWingMediaShowTitle, showTitle)
        customLayoutRes = a.getResourceId(R.styleable.ITWingMediaItemsView_ITWingMediaItemLayout, 0)
        premiumUnlockPlacement = a.getString(R.styleable.ITWingMediaItemsView_ITWingMediaPremiumUnlockPlacement) ?: premiumUnlockPlacement
        a.recycle()
        adapter.showTitle = showTitle
        adapter.customLayoutRes = customLayoutRes
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
        if (placement.enabled == false) {
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
    }

    private fun waitForReady(attempts: Int, block: () -> Unit) {
        if (ITWingSDK.isReady()) {
            block()
        } else if (attempts > 0) {
            postDelayed({ waitForReady(attempts - 1, block) }, 250)
        } else {
            hideLoading()
            emptyView.text = "SDK is not ready. Check internet connection and SDK initialization."
            emptyView.visibility = VISIBLE
            ITWingSDK.showSdkFeatureError(context, "SDK not ready", "Check internet connection and SDK initialization.")
        }
    }

    private fun shimmerRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        repeat(if (horizontal) 3 else columns.coerceAtLeast(1)) {
            addView(View(context).apply {
                background = rounded(Color.parseColor("#E5E7EB"), dp(14).toFloat())
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
    private val adapter = MediaCategoryAdapter(::handleCategoryClick)
    private var placementName: String? = null
    private var columns = 1
    private var horizontal = true
    private var linkedViewId = 0
    private var linkedView: ITWingMediaItemsView? = null
    private var displayMode = DisplayMode.TEXT
    private var customLayoutRes = 0
    private var clickListener: ((ITWingMediaCategory) -> Unit)? = null
    private var customBinder: ITWingMediaCategoryBinder? = null

    init {
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
        if (!NetworkState.isOnline(context)) {
            adapter.submit(emptyList())
            ITWingSDK.showSdkFeatureError(context, "${mediaKind.featureTitle()} categories", NetworkState.offlineMessage()) { reload() }
            return
        }
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
                    adapter.submit(listOf(all) + response.categories)
                }

                override fun onError(error: String) {
                    ITWingSDK.showSdkFeatureError(context, "Categories unavailable", error)
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
        val a = context.obtainStyledAttributes(attrs, R.styleable.ITWingMediaCategoriesView)
        placementName = a.getString(R.styleable.ITWingMediaCategoriesView_ITWingMediaPlacement)
        columns = a.getInt(R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryColumns, columns).coerceAtLeast(1)
        horizontal = a.getBoolean(R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryHorizontal, horizontal)
        linkedViewId = a.getResourceId(R.styleable.ITWingMediaCategoriesView_ITWingMediaLinkedView, 0)
        customLayoutRes = a.getResourceId(R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryItemLayout, 0)
        displayMode = when (a.getInt(R.styleable.ITWingMediaCategoriesView_ITWingMediaCategoryDisplayMode, 0)) {
            1 -> DisplayMode.IMAGE
            2 -> DisplayMode.BOTH
            else -> DisplayMode.TEXT
        }
        a.recycle()
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
    ITWingMediaItemsView(context, attrs, defStyleAttr, "vpn_servers", false)

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = if (customLayoutRes != 0) {
            LayoutInflater.from(parent.context).inflate(customLayoutRes, parent, false)
        } else {
            defaultMediaItem(parent.context)
        }
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        customBinder?.bind(holder.itemView, item, position) ?: holder.itemView.bindMediaItem(item, kind, showTitle)
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
    private val onClick: (ITWingMediaCategory) -> Unit,
) : RecyclerView.Adapter<MediaCategoryAdapter.Holder>() {
    private val items = mutableListOf<ITWingMediaCategory>()
    private var selected = ""
    var displayMode = ITWingMediaCategoriesView.DisplayMode.TEXT
    var customLayoutRes = 0
    var customBinder: ITWingMediaCategoryBinder? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = if (customLayoutRes != 0) LayoutInflater.from(parent.context).inflate(customLayoutRes, parent, false) else defaultCategoryItem(parent.context)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isSelected = item.id == selected || (selected.isBlank() && item.id.isBlank())
        customBinder?.bind(holder.itemView, item, isSelected, position) ?: holder.itemView.bindMediaCategory(item, displayMode, isSelected)
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
        id = R.id.itwing_media_category_title
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#111827"))
        textSize = 13f
        gravity = Gravity.CENTER
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

private fun View.bindMediaItem(item: ITWingMediaItem, kind: String, showTitle: Boolean) {
    val image = findViewByName<ImageView>("itwing_media_image", "media_image", "thumbnail")
    val icon = findViewByName<TextView>("itwing_media_icon")
    val title = findViewByName<TextView>("itwing_media_title", "media_title", "title")
    val premium = findViewByName<TextView>("itwing_media_premium", "media_premium", "premium")
    title?.text = item.title
    title?.visibility = if (showTitle) View.VISIBLE else View.GONE
    premium?.visibility = if (item.isPremium) View.VISIBLE else View.GONE
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
            "vpn_servers" -> "VPN"
            else -> "Audio"
        }
    }
}

private fun View.bindMediaCategory(item: ITWingMediaCategory, mode: ITWingMediaCategoriesView.DisplayMode, selected: Boolean) {
    val image = findViewByName<ImageView>("itwing_media_category_image", "media_category_image", "category_image")
    val title = findViewByName<TextView>("itwing_media_category_title", "media_category_title", "category_title", "title")
    background = rounded(if (selected) ITWingSDK.sdkPrimaryColorInt() else Color.WHITE, dp(999).toFloat(), Color.parseColor("#E5E7EB"), 1)
    title?.text = item.name
    title?.setTextColor(if (selected) Color.WHITE else Color.parseColor("#111827"))
    title?.visibility = if (mode == ITWingMediaCategoriesView.DisplayMode.IMAGE) View.GONE else View.VISIBLE
    image?.visibility = if (mode == ITWingMediaCategoriesView.DisplayMode.TEXT || item.imageUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    if (!item.imageUrl.isNullOrBlank() && image != null) {
        Glide.with(image).load(item.imageUrl).centerCrop().into(image)
    }
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
