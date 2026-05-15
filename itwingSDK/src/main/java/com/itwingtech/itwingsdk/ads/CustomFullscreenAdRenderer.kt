package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.core.CustomAdConfig
import com.itwingtech.itwingsdk.core.ITWingSDK
import com.itwingtech.itwingsdk.utils.safeCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

internal class CustomFullscreenAdRenderer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun canRender(placement: AdPlacementConfig): Boolean = placement.customAd != null

    fun preload(activity: Activity, placement: AdPlacementConfig) {
        val ad = placement.customAd ?: return
        if (!ad.imageUrl.isNullOrBlank()) {
            scope.launch { runCatching { downloadImage(ad.imageUrl) } }
        }
    }

    fun show(
        activity: Activity,
        placement: AdPlacementConfig,
        reward: (() -> Unit)? = null,
        onComplete: () -> Unit = {},
    ): Boolean {
        val ad = placement.customAd ?: return false
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val closeDelayMs = (ad.metadata["close_delay_ms"] as? Number)?.toLong()
            ?: (placement.metadata["close_delay_ms"] as? Number)?.toLong()
            ?: if (placement.format.contains("rewarded")) 5_000L else 1_500L
        var impressionTracked = false
        var completed = false

        fun complete() {
            if (completed) return
            completed = true
            if (placement.format.contains("rewarded")) safeCallback { reward?.invoke() }
            safeCallback(onComplete)
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener {
            ITWingSDK.trackCustomAdEvent(ad.id, "dismiss", eventMetadata(placement))
            complete()
        }

        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val progress = ProgressBar(activity).apply { isIndeterminate = true }
        root.addView(progress, FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.CENTER))

        val close = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
            alpha = 0f
            isEnabled = false
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(activity, 44), dp(activity, 44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(activity, 28)
            rightMargin = dp(activity, 18)
        })

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 22), dp(activity, 70), dp(activity, 22), dp(activity, 34))
        }
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        fun renderCreative() {
            progress.visibility = View.GONE
            if (!impressionTracked) {
                impressionTracked = true
                ITWingSDK.trackCustomAdImpression(ad.id, eventMetadata(placement))
            }
            renderMedia(activity, content, ad)
            renderCopy(activity, content, ad, placement)
            Handler(Looper.getMainLooper()).postDelayed({
                close.animate().alpha(1f).setDuration(180).start()
                close.isEnabled = true
            }, closeDelayMs.coerceAtLeast(0L))
        }

        dialog.setContentView(root)
        dialog.show()
        scope.launch {
            if (!ad.imageUrl.isNullOrBlank() && ad.html.isNullOrBlank()) {
                runCatching { downloadImage(ad.imageUrl) }
            }
            renderCreative()
        }
        return true
    }

    private fun renderMedia(activity: Activity, content: LinearLayout, ad: CustomAdConfig) {
        when {
            !ad.html.isNullOrBlank() -> {
                val webView = WebView(activity).apply {
                    settings.javaScriptEnabled = false
                    setBackgroundColor(Color.TRANSPARENT)
                    loadDataWithBaseURL(null, ad.html, "text/html", "UTF-8", null)
                }
                content.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            !ad.imageUrl.isNullOrBlank() -> {
                val image = ImageView(activity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                content.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                scope.launch {
                    val bitmap = runCatching { downloadImage(ad.imageUrl) }.getOrNull()
                    if (bitmap != null) image.setImageBitmap(bitmap)
                }
            }
            else -> {
                content.addView(TextView(activity).apply {
                    text = ad.headline ?: ad.name
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
    }

    private fun renderCopy(activity: Activity, content: LinearLayout, ad: CustomAdConfig, placement: AdPlacementConfig) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), 0)
        }
        content.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(activity).apply {
            text = ad.headline ?: ad.name
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        })
        if (!ad.body.isNullOrBlank()) {
            panel.addView(TextView(activity).apply {
                text = ad.body
                setTextColor(0xffd1d5db.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 8), 0, dp(activity, 14))
            })
        }
        if (!ad.cta.isNullOrBlank() && !ad.targetUrl.isNullOrBlank()) {
            panel.addView(MaterialButton(activity).apply {
                text = ad.cta
                setOnClickListener {
                    ITWingSDK.trackCustomAdClick(ad.id, eventMetadata(placement))
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.targetUrl))) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun eventMetadata(placement: AdPlacementConfig): Map<String, Any?> = mapOf(
        "placement" to placement.name,
        "format" to placement.format,
        "network" to "custom",
    )

    private suspend fun downloadImage(url: String) = withContext(Dispatchers.IO) {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
