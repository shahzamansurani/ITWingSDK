package com.itwingtech.itwingsdk.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.SDKUi
import com.itwingtech.itwingsdk.utils.withAlpha

@androidx.annotation.OptIn(UnstableApi::class)
class ITWingMediaPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    enum class MediaType { AUTO, VIDEO, AUDIO }

    private val playerView = PlayerView(context)
    private val posterView = ImageView(context)
    private val loadingView = ProgressBar(context)
    private val errorView = TextView(context)
    private val muteButton = ImageButton(context)

    private var player: ExoPlayer? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var mediaUrl: String? = null
    private var posterUrl: String? = null
    private var mediaType = MediaType.AUTO
    private var autoPlay = false
    private var loop = false
    private var muted = false
    private var showControls = true
    private var showMuteButton = true
    private var keepScreenOnForPlayback = false
    private var controllerTimeoutMs = 5000
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var cornerRadiusPx = 0f
    private var playerBackgroundColor = backgroundColor()
    private var loadingColor = SDKUi.primaryColor()
    private var errorTextColor = SDKUi.mutedTextColor(context)
    private var errorTextSizePx = 0f
    private var muteIconRes = R.drawable.no_sound
    private var unmuteIconRes = R.drawable.unmute
    private var muteIconTint = Color.WHITE
    private var muteBackgroundRes = R.drawable.icon_back
    private var lastPosition = 0L
    private var wasPlayingBeforePause = false

    init {
        readAttrs(attrs)
        setupViews()
        applyChrome()
        mediaUrl?.takeIf { it.isNotBlank() }?.let { setMediaUrl(it, posterUrl, autoPlay) }
    }

    fun setMediaUrl(url: String?, poster: String? = null, playWhenReady: Boolean = autoPlay) {
        mediaUrl = url
        posterUrl = poster ?: posterUrl
        autoPlay = playWhenReady

        if (url.isNullOrBlank()) {
            showError(context.getString(R.string.itwing_media_player_error))
            return
        }

        showLoading()
        renderPoster()
        preparePlayer(url)
    }

    fun play() {
        runCatching {
            player?.play()
        }
    }

    fun pause() {
        runCatching {
            player?.pause()
        }
    }

    fun stop() {
        runCatching {
            player?.stop()
            lastPosition = 0L
        }
    }

    fun seekTo(positionMs: Long) {
        runCatching {
            player?.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    fun currentPosition(): Long = player?.currentPosition ?: lastPosition

    fun duration(): Long = player?.duration ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun setMuted(value: Boolean) {
        muted = value
        player?.volume = if (muted) 0f else 1f
        updateMuteIcon()
    }

    fun setLooping(value: Boolean) {
        loop = value
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun release() {
        runCatching {
            lastPosition = player?.currentPosition ?: lastPosition
            playerView.player = null
            player?.release()
            player = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachLifecycle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = null
        release()
    }

    override fun onPause(owner: LifecycleOwner) {
        runCatching {
            lastPosition = player?.currentPosition ?: lastPosition
            wasPlayingBeforePause = player?.isPlaying == true
            player?.pause()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        runCatching {
            player?.seekTo(lastPosition)
            if (wasPlayingBeforePause) player?.play()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    private fun setupViews() {
        clipChildren = true
        clipToPadding = true

        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(posterView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(loadingView, LayoutParams(dp(42), dp(42), Gravity.CENTER))
        addView(errorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        addView(muteButton, LayoutParams(dp(38), dp(38), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })

        posterView.scaleType = ImageView.ScaleType.CENTER_CROP
        errorView.gravity = Gravity.CENTER
        errorView.visibility = GONE
        loadingView.visibility = GONE
        muteButton.visibility = if (showMuteButton) VISIBLE else GONE
        muteButton.setOnClickListener { setMuted(!muted) }
        playerView.useController = showControls
        playerView.controllerShowTimeoutMs = controllerTimeoutMs
        playerView.resizeMode = resizeMode
        playerView.keepScreenOn = keepScreenOnForPlayback
    }

    private fun applyChrome() {
        setBackgroundColor(playerBackgroundColor)
        ViewCompat.setBackgroundTintList(loadingView, ColorStateList.valueOf(loadingColor))
        errorView.setTextColor(errorTextColor)
        if (errorTextSizePx > 0f) {
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_PX, errorTextSizePx)
        } else {
            errorView.textSize = 14f
        }
        muteButton.setBackgroundResource(muteBackgroundRes)
        muteButton.imageTintList = ColorStateList.valueOf(muteIconTint)
        updateMuteIcon()

        if (cornerRadiusPx > 0f) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }
    }

    private fun preparePlayer(url: String) {
        release()
        runCatching {
            player = ExoPlayer.Builder(context.applicationContext).build().also { exoPlayer ->
                exoPlayer.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                exoPlayer.volume = if (muted) 0f else 1f
                exoPlayer.playWhenReady = autoPlay
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> showLoading()
                            Player.STATE_READY -> showReady()
                            Player.STATE_ENDED -> if (!loop) showReady()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showError(context.getString(R.string.itwing_media_player_error))
                        ITWingSDK.analytics.track(
                            "sdk_media_player_error",
                            mapOf("message" to (error.message ?: "playback_error")),
                        )
                    }
                })
                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                exoPlayer.prepare()
            }
        }.onFailure {
            showError(context.getString(R.string.itwing_media_player_error))
        }
    }

    private fun renderPoster() {
        if (posterUrl.isNullOrBlank()) {
            posterView.visibility = if (isAudio()) VISIBLE else GONE
            posterView.setBackgroundColor(SDKUi.primaryColor().withAlpha(32))
            return
        }

        posterView.visibility = VISIBLE
        Glide.with(posterView)
            .load(posterUrl)
            .centerCrop()
            .into(posterView)
    }

    private fun showLoading() {
        errorView.visibility = GONE
        loadingView.visibility = VISIBLE
    }

    private fun showReady() {
        loadingView.visibility = GONE
        errorView.visibility = GONE
        if (!isAudio()) posterView.visibility = GONE
        muteButton.visibility = if (showMuteButton) VISIBLE else GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = GONE
        errorView.text = message
        errorView.visibility = VISIBLE
        posterView.visibility = if (posterUrl.isNullOrBlank()) GONE else VISIBLE
    }

    private fun updateMuteIcon() {
        muteButton.setImageResource(if (muted) muteIconRes else unmuteIconRes)
        muteButton.contentDescription = context.getString(
            if (muted) R.string.itwing_media_player_unmute else R.string.itwing_media_player_mute
        )
    }

    private fun attachLifecycle() {
        val owner = findViewTreeLifecycleOwner() ?: return
        if (owner == lifecycleOwner) return
        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(this)
    }

    private fun isAudio(): Boolean {
        if (mediaType == MediaType.AUDIO) return true
        if (mediaType == MediaType.VIDEO) return false
        val lower = mediaUrl.orEmpty().substringBefore("?").lowercase()
        return lower.endsWith(".mp3") ||
                lower.endsWith(".wav") ||
                lower.endsWith(".m4a") ||
                lower.endsWith(".aac") ||
                lower.endsWith(".ogg") ||
                lower.endsWith(".flac")
    }

    private fun readAttrs(attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, R.styleable.ITWingMediaPlayerView) {
            mediaUrl = getString(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerUrl)
            posterUrl = getString(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerPosterUrl)
            autoPlay = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerAutoPlay, autoPlay)
            loop = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerLoop, loop)
            muted = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerMuted, muted)
            showControls = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerShowControls, showControls)
            showMuteButton = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerShowMuteButton, showMuteButton)
            keepScreenOnForPlayback = getBoolean(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerKeepScreenOn, keepScreenOnForPlayback)
            controllerTimeoutMs = getInt(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerControllerTimeout, controllerTimeoutMs)
            mediaType = when (getInt(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerMediaType, 0)) {
                1 -> MediaType.VIDEO
                2 -> MediaType.AUDIO
                else -> MediaType.AUTO
            }
            resizeMode = when (getInt(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerResizeMode, 0)) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                4 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            playerBackgroundColor = getColor(
                R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerBackgroundColor,
                playerBackgroundColor
            )
            cornerRadiusPx = getDimension(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerCornerRadius, cornerRadiusPx)
            loadingColor = getColor(
                R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerLoadingColor,
                loadingColor
            )
            errorTextColor = getColor(
                R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerErrorTextColor,
                errorTextColor
            )
            errorTextSizePx = getDimension(
                R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerErrorTextSize,
                errorTextSizePx
            )
            muteIconRes = getResourceId(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerMuteIcon, muteIconRes)
            unmuteIconRes = getResourceId(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerUnmuteIcon, unmuteIconRes)
            muteIconTint = getColor(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerMuteIconTint, muteIconTint)
            muteBackgroundRes = getResourceId(R.styleable.ITWingMediaPlayerView_ITWingMediaPlayerMuteButtonBackground, muteBackgroundRes)
        }
    }

    private fun backgroundColor(): Int = ContextCompat.getColor(context, R.color.itwing_sdk_surface)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
