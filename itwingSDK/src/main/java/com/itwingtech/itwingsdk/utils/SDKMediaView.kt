package com.itwingtech.itwingsdk.utils

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.databinding.SdkMediaViewBinding
import java.util.concurrent.atomic.AtomicBoolean

class SDKMediaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(
    context,
    attrs,
    defStyleAttr
) {

    private val binding =
        SdkMediaViewBinding.inflate(
            LayoutInflater.from(context),
            this,
            true
        )

    private var pausedForExternalNavigation =
        false

    private var lastPlaybackPosition =
        0L

    private var wasPlayingBeforePause =
        false

    private var internalMuteHidden = false

    private val imageView = binding.sdkImage

    private val playerView =
        binding.sdkVideo

    private val muteButton =
        binding.sdkMute

    private var player:
            ExoPlayer? = null

    private var isVideo = false

    private var isMuted = true

    /*
    |--------------------------------------------------------------------------
    | Fullscreen External Mute
    |--------------------------------------------------------------------------
    */

    private var externalMuteButton:
            ImageView? = null

    init {

        setupMuteButton()
    }

    /*
    |--------------------------------------------------------------------------
    | Render
    |--------------------------------------------------------------------------
    */

    @JvmOverloads
    fun render(
        url: String?,
        video: Boolean,
        loop: Boolean = true,
        onCompleted: (() -> Unit)? = null,
    ) {

        release()

        if (url.isNullOrBlank()) {
            return
        }

        isVideo = video

        if (video) {

            renderVideo(url, loop, onCompleted)

        } else {

            renderImage(url)
        }

        muteButton.visibility = when {
            !video -> GONE
            internalMuteHidden -> GONE
            else -> VISIBLE
        }

        updateMuteButtonStyle()
    }

    /*
    |--------------------------------------------------------------------------
    | Image
    |--------------------------------------------------------------------------
    */

    private fun renderImage(
        url: String
    ) {

        imageView.visibility =
            VISIBLE

        playerView.visibility =
            GONE

        Glide.with(context)
            .load(url)
            .fitCenter()
            .into(imageView)
    }

    /*
    |--------------------------------------------------------------------------
    | Video
    |--------------------------------------------------------------------------
    */

    private fun renderVideo(
        url: String,
        loop: Boolean,
        onCompleted: (() -> Unit)?,
    ) {

        imageView.visibility =
            GONE

        playerView.visibility =
            VISIBLE

        val completionSent = AtomicBoolean(false)
        player =
            ExoPlayer.Builder(context)
                .build()
                .also { exoPlayer ->
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED && completionSent.compareAndSet(false, true)) {
                                onCompleted?.invoke()
                            }
                        }
                    })
                }

        playerView.player =
            player

        playerView.useController =
            false

        playerView.resizeMode =
            AspectRatioFrameLayout.RESIZE_MODE_FIT

        val mediaItem =
            MediaItem.fromUri(url)

        player?.apply {

            setMediaItem(mediaItem)

            prepare()

            /*
            |--------------------------------------------------------------------------
            | LOOP FOREVER
            |--------------------------------------------------------------------------
            */

            repeatMode =
                if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

            playWhenReady = true

            volume =
                if (isMuted) 0f else 1f
        }

        muteButton.bringToFront()

        muteButton.translationZ =
            dp(999).toFloat()
    }

    /*
    |--------------------------------------------------------------------------
    | Play
    |--------------------------------------------------------------------------
    */

    fun play() {

        runCatching {

            player?.play()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Pause
    |--------------------------------------------------------------------------
    */

    fun pause() {

        runCatching {

            player?.pause()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Resume
    |--------------------------------------------------------------------------
    */

    fun resume() {

        runCatching {

            player?.play()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Host Pause
    |--------------------------------------------------------------------------
    */

    fun onHostPause() {

        runCatching {

            if (
                player?.isPlaying == true
            ) {

                player?.pause()
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Host Resume
    |--------------------------------------------------------------------------
    */

    fun onHostResume() {
        runCatching {
            player?.play()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Restore Player
    |--------------------------------------------------------------------------
    */

    fun restorePlayer() {
        runCatching {
            playerView.player = null

            playerView.player = player

            muteButton.bringToFront()

            muteButton.translationZ =
                dp(999).toFloat()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Current Position
    |--------------------------------------------------------------------------
    */

    fun currentPosition(): Long {

        return player?.currentPosition
            ?: 0L
    }

    /*
    |--------------------------------------------------------------------------
    | Is Playing
    |--------------------------------------------------------------------------
    */

    fun isPlaying(): Boolean {

        return player?.isPlaying
            ?: false
    }

    /*
    |--------------------------------------------------------------------------
    | Seek
    |--------------------------------------------------------------------------
    */

    fun seekTo(
        position: Long
    ) {

        runCatching {

            player?.seekTo(position)
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Release
    |--------------------------------------------------------------------------
    */

    fun release() {

        runCatching {

            playerView.player = null

            player?.release()

            player = null
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Internal Mute
    |--------------------------------------------------------------------------
    */

    private fun setupMuteButton() {

        muteButton.setOnClickListener {

            isMuted = !isMuted

            player?.volume =
                if (isMuted) 0f else 1f

            updateMuteIcon()

            updateExternalMuteIcon()
        }

        updateMuteIcon()
    }

    /*
    |--------------------------------------------------------------------------
    | External Fullscreen Mute
    |--------------------------------------------------------------------------
    */

    fun attachExternalMuteButton(
        button: ImageView?
    ) {

        externalMuteButton = button

        button ?: return

        updateExternalMuteIcon()

        button.setOnClickListener {

            isMuted = !isMuted

            player?.volume =
                if (isMuted) 0f else 1f

            updateMuteIcon()

            updateExternalMuteIcon()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Hide Internal Mute
    |--------------------------------------------------------------------------
    */

    fun hideInternalMute() {
        internalMuteHidden = true
        muteButton.visibility = GONE
    }

    /*
    |--------------------------------------------------------------------------
    | Update Internal Icon
    |--------------------------------------------------------------------------
    */

    private fun updateMuteIcon() {

        muteButton.setImageResource(

            if (isMuted) {

                R.drawable.no_sound

            } else {

                R.drawable.unmute
            }
        )

        updateExternalMuteIcon()
    }

    /*
    |--------------------------------------------------------------------------
    | Update External Icon
    |--------------------------------------------------------------------------
    */

    private fun updateExternalMuteIcon() {
        externalMuteButton
            ?.setImageResource(
                if (isMuted) {
                    R.drawable.no_sound
                } else {
                    R.drawable.unmute
                }
            )
    }

    /*
    |--------------------------------------------------------------------------
    | Adaptive Mute Size
    |--------------------------------------------------------------------------
    */

    private fun updateMuteButtonStyle() {

        post {

            val width = measuredWidth
            val height = measuredHeight

            if (
                width <= 0 ||
                height <= 0
            ) {
                return@post
            }

            val isSmall =
                width < dp(160) ||
                        height < dp(100)

            val isMedium =
                width < dp(260)

            val buttonSize = when {

                isSmall -> dp(22)

                isMedium -> dp(28)

                else -> dp(36)
            }

            val iconPadding = when {

                isSmall -> dp(4)

                isMedium -> dp(5)

                else -> dp(7)
            }

            val margin = when {

                isSmall -> dp(4)

                isMedium -> dp(6)

                else -> dp(10)
            }

            val params =
                muteButton.layoutParams
                        as LayoutParams

            params.width =
                buttonSize

            params.height =
                buttonSize

            params.gravity =
                Gravity.BOTTOM or Gravity.END

            params.setMargins(
                margin,
                margin,
                margin,
                margin
            )

            muteButton.layoutParams =
                params

            muteButton.setPadding(
                iconPadding,
                iconPadding,
                iconPadding,
                iconPadding
            )

            muteButton.translationZ =
                dp(999).toFloat()

            muteButton.bringToFront()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | DP
    |--------------------------------------------------------------------------
    */

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }

    fun pauseForExternalNavigation() {

        runCatching {

            lastPlaybackPosition =
                player?.currentPosition ?: 0L

            wasPlayingBeforePause =
                player?.isPlaying ?: false

            pausedForExternalNavigation =
                true

            player?.pause()
        }
    }

    fun resumeFromExternalNavigation() {

        if (!pausedForExternalNavigation) {
            return
        }

        pausedForExternalNavigation =
            false

        postDelayed({

            runCatching {

                restorePlayer()

                player?.seekTo(
                    lastPlaybackPosition
                )

                if (wasPlayingBeforePause) {

                    player?.play()
                }
            }

        }, 300)
    }
}
