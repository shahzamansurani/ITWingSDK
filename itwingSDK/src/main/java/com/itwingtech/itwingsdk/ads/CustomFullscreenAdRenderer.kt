package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.databinding.CustomInterstitialBinding
import com.itwingtech.itwingsdk.utils.SDKMediaView
import java.util.concurrent.atomic.AtomicBoolean

internal class CustomFullscreenAdRenderer {
    private var activeMediaView: SDKMediaView? = null
    private var isCtaOpened = false
    /*
    |--------------------------------------------------------------------------
    | Playback Restore
    |--------------------------------------------------------------------------
    */
    private var lastPlaybackPosition = 0L
    private var wasPlayingBeforePause = false

    /*
    |--------------------------------------------------------------------------
    | Can Render
    |--------------------------------------------------------------------------
    */

    fun canRender(
        placement: AdPlacementConfig
    ): Boolean {

        return placement.customAd != null
    }

    /*
    |--------------------------------------------------------------------------
    | Preload
    |--------------------------------------------------------------------------
    */

    fun preload(
        activity: Activity,
        placement: AdPlacementConfig
    ) {

        val ad =
            placement.customAd ?: return

        val mediaUrl = ad.mediaUrl() ?: return

        activity.runOnUiThread {
            runCatching {
                Glide.with(activity.applicationContext)
                    .load(mediaUrl)
                    .preload()
            }
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Show
    |--------------------------------------------------------------------------
    */

    fun show(
        activity: Activity,
        placement: AdPlacementConfig,
        reward: (() -> Unit)? = null,
        onComplete: () -> Unit = {}
    ): Boolean {

        val ad =
            placement.customAd ?: return false
        val completion = FullscreenCompletion(onComplete)
        val isRewardedPlacement = placement.format.contains("rewarded", ignoreCase = true)
        val rewardEarned = AtomicBoolean(false)
        val fullscreenOwner = FullscreenAdState.tryBegin("custom_${placement.format}", placement.name)
            ?: return false

        val dialog = Dialog(
            activity,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )

        dialog.requestWindowFeature(
            Window.FEATURE_NO_TITLE
        )

        val binding =
            CustomInterstitialBinding
                .inflate(
                    LayoutInflater.from(activity)
                )

        /*
        |--------------------------------------------------------------------------
        | Views
        |--------------------------------------------------------------------------
        */

        val advertiserView =
            binding.root.findViewById<TextView>(
                R.id.ad_advertiser
            )

        val iconView =
            binding.root.findViewById<ImageView>(
                R.id.ad_app_icon
            )

        val adTag =
            binding.root.findViewById<TextView>(
                R.id.ad_ic
            )

        val ratingView =
            binding.root.findViewById<RatingBar>(
                R.id.ad_stars
            )

        val storeView =
            binding.root.findViewById<TextView>(
                R.id.ad_store
            )

        /*
        |--------------------------------------------------------------------------
        | Loading
        |--------------------------------------------------------------------------
        */

        binding.adLoading.visibility =
            View.VISIBLE

        /*
        |--------------------------------------------------------------------------
        | Media
        |--------------------------------------------------------------------------
        */

        activeMediaView = binding.adMedia
        binding.adMedia.hideInternalMute()
        binding.adMedia.attachExternalMuteButton(
            binding.fullscreenMute
        )

        binding.adMedia.render(
            ad.mediaUrl(),
            ad.isVideo()
        )

        binding.adMedia.play()

        /*
        |--------------------------------------------------------------------------
        | Delay Loading Hide
        |--------------------------------------------------------------------------
        */

        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            binding.adLoading.visibility =
                View.GONE

        }, 650)

        /*
        |--------------------------------------------------------------------------
        | Text
        |--------------------------------------------------------------------------
        */

        binding.adTitle.text =
            ad.headline ?: ad.name

        binding.adBody.text =
            ad.body.orEmpty()

        binding.adCta.text =
            ad.cta ?: "Open"

        advertiserView.text =
            ad.brandName()
                ?: "Sponsored"

        ratingView.rating =
            ad.brandRating()

        storeView.text =
            "Sponsored"

        adTag.text =
            ad.adIcon()

        /*
        |--------------------------------------------------------------------------
        | App Icon
        |--------------------------------------------------------------------------
        */

        loadImage(
            ad.brandLogoUrl(),
            iconView,
            activity
        )

        /*
        |--------------------------------------------------------------------------
        | CTA Color
        |--------------------------------------------------------------------------
        */

        val ctaDrawable =
            binding.adCta.background
                ?.mutate() as? GradientDrawable

        ctaDrawable?.setColor(
            parseColorSafe(
                ad.primaryColor(),
                Color.rgb(
                    37,
                    99,
                    235
                )
            )
        )

        /*
        |--------------------------------------------------------------------------
        | Ad Badge Color
        |--------------------------------------------------------------------------
        */

        val adTagDrawable =
            adTag.background
                ?.mutate() as? GradientDrawable

        adTagDrawable?.setColor(
            parseColorSafe(
                ad.primaryColor(),
                Color.rgb(
                    37,
                    99,
                    235
                )
            )
        )

        /*
        |--------------------------------------------------------------------------
        | CTA CLICK
        |--------------------------------------------------------------------------
        */

        binding.adCta.setOnClickListener {

            ITWingSDK.trackCustomAdClick(
                ad.id,
                mapOf(
                    "placement" to placement.name
                )
            )

            activeMediaView?.let { media ->

                lastPlaybackPosition =
                    media.currentPosition()

                wasPlayingBeforePause =
                    media.isPlaying()

                media.pause()
            }

            isCtaOpened = true

            runCatching {

                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        ad.targetUrl?.toUri()
                    )
                )
            }
        }

        /*
        |--------------------------------------------------------------------------
        | Resume On Return
        |--------------------------------------------------------------------------
        */

        val lifecycleCallbacks =
            object :
                Application.ActivityLifecycleCallbacks {

                override fun onActivityResumed(
                    resumedActivity: Activity
                ) {

                    if (
                        resumedActivity == activity &&
                        isCtaOpened
                    ) {

                        isCtaOpened = false

                        Handler(
                            Looper.getMainLooper()
                        ).postDelayed({

                            activeMediaView?.let { media ->

                                /*
                                |--------------------------------------------------------------------------
                                | Restore Surface
                                |--------------------------------------------------------------------------
                                */

                                media.restorePlayer()

                                /*
                                |--------------------------------------------------------------------------
                                | Restore Position
                                |--------------------------------------------------------------------------
                                */

                                media.seekTo(
                                    lastPlaybackPosition
                                )

                                /*
                                |--------------------------------------------------------------------------
                                | Resume Playback
                                |--------------------------------------------------------------------------
                                */

                                if (
                                    wasPlayingBeforePause
                                ) {

                                    media.play()
                                }
                            }

                        }, 400)
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                }

                override fun onActivityStarted(
                    activity: Activity
                ) {
                }

                override fun onActivityPaused(
                    activity: Activity
                ) {
                }

                override fun onActivityStopped(
                    activity: Activity
                ) {
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) {
                }

                override fun onActivityDestroyed(
                    destroyedActivity: Activity
                ) {

                    if (
                        destroyedActivity ==
                        activity
                    ) {

                        runCatching {

                            activity.application
                                .unregisterActivityLifecycleCallbacks(
                                    this
                                )
                        }
                    }
                }
            }

        activity.application
            .registerActivityLifecycleCallbacks(
                lifecycleCallbacks
            )

        /*
        |--------------------------------------------------------------------------
        | Close Delay
        |--------------------------------------------------------------------------
        */

        binding.adClose.alpha = 0f

        binding.adClose.isEnabled = false

        val closeDelay = when {

            placement.format.contains(
                "rewarded",
                true
            ) -> 6000L

            else -> 3000L
        }

        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            if (isRewardedPlacement && rewardEarned.compareAndSet(false, true)) {
                reward?.invoke()
            }

            binding.adClose.animate().alpha(1f).setDuration(250).start()

            binding.adClose.isEnabled =
                true

        }, closeDelay)

        /*
        |--------------------------------------------------------------------------
        | Close
        |--------------------------------------------------------------------------
        */

        binding.adClose.setOnClickListener {
            destroy(binding.adMedia)
            dialog.dismiss()
        }

        /*
        |--------------------------------------------------------------------------
        | Dismiss
        |--------------------------------------------------------------------------
        */

        dialog.setOnDismissListener {

            destroy(binding.adMedia)
            FullscreenAdState.end(fullscreenOwner)

            runCatching {
                activity.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
            }

            preload(
                activity,
                placement
            )

            if (!isRewardedPlacement || rewardEarned.get()) {
                completion.complete()
            }
        }

        /*
        |--------------------------------------------------------------------------
        | Back Press
        |--------------------------------------------------------------------------
        */

        dialog.setOnKeyListener { _,
                                  keyCode,
                                  event ->

            if (
                keyCode ==
                KeyEvent.KEYCODE_BACK &&
                event.action ==
                KeyEvent.ACTION_UP
            ) {

                destroy(binding.adMedia)

                dialog.dismiss()

                true

            } else {

                false
            }
        }

        dialog.setContentView(
            binding.root
        )

        dialog.show()

        return true
    }

    private fun destroy(
        mediaView: SDKMediaView?
    ) {

        runCatching {

            mediaView?.release()
        }
    }

    /*
    |--------------------------------------------------------------------------
    | Helpers
    |--------------------------------------------------------------------------
    */

    private fun CustomAdConfig.mediaUrl(): String? =
        mediaUrl
            ?: videoUrl
            ?: imageUrl

    private fun CustomAdConfig.isVideo(): Boolean =
        mediaType.equals(
            "video",
            true
        )

    private fun CustomAdConfig.primaryColor(): String? =
        metadata["ad_primary_color"]
                as? String
            ?: (
                    metadata["brand"]
                            as? Map<*, *>
                    )?.get("primary_color")
                    as? String

    private fun CustomAdConfig.brandName(): String? =
        (
                metadata["brand"]
                        as? Map<*, *>
                )?.get("name")
                as? String
            ?: campaignGroup

    private fun CustomAdConfig.brandRating(): Float {

        val value =
            metadata["brand_rating"]
                ?: (
                        metadata["brand"]
                                as? Map<*, *>
                        )?.get("rating")

        return when (value) {

            is Number ->
                value.toFloat()

            is String ->
                value.toFloatOrNull()

            else ->
                null
        }?.coerceIn(0f, 5f)
            ?: 4.5f
    }

    private fun CustomAdConfig.brandLogoUrl(): String? {

        val brand =
            metadata["brand"]
                    as? Map<*, *>
                ?: return null

        return brand["logo_url"]
                as? String
    }

    private fun CustomAdConfig.adIcon(): String =
        (
                metadata["ad_icon"]
                        as? String
                )?.takeIf {
                it.isNotBlank()
            }
            ?: "AD"

    private fun parseColorSafe(
        value: String?,
        fallback: Int
    ): Int =

        runCatching {

            if (value.isNullOrBlank()) {

                fallback

            } else {

                value.toColorInt()
            }

        }.getOrDefault(fallback)

    private fun loadImage(
        url: String?,
        imageView: ImageView?,
        activity: Activity
    ) {

        if (url.isNullOrBlank()) {
            return
        }

        activity.runOnUiThread {
            runCatching {
                imageView?.let {
                    Glide.with(activity).load(url).fitCenter().into(it)
                }
                imageView?.visibility = View.VISIBLE
            }
        }
    }
}
