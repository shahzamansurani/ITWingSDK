package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.utils.safeCallback

internal object RewardedIntroDialog {

    private val mainHandler =
        Handler(Looper.getMainLooper())

    fun show(
        activity: Activity,
        placement: AdPlacementConfig,
        primaryColor: Int,
        onSkip: () -> Unit,
        onWatch: () -> Unit
    ) {
        runOnMain {

            if (!isActivityUsable(activity)) {
                safeCallback(onSkip)
                return@runOnMain
            }

            var callbackCalled =
                false

            fun callSkipOnce() {
                if (callbackCalled) {
                    return
                }

                callbackCalled =
                    true

                safeCallback(onSkip)
            }

            fun callWatchOnce() {
                if (callbackCalled) {
                    return
                }

                callbackCalled =
                    true

                safeCallback(onWatch)
            }

            runCatching {

                val title =
                    (
                        placement.metadata["intro_title"]
                            as? String
                        )?.takeIf {
                        it.isNotBlank()
                    }
                        ?: activity.getString(
                            R.string.rewarded_intro_title
                        )

                val message =
                    (
                            placement.metadata["intro_message"]
                                    as? String
                            )?.takeIf {
                            it.isNotBlank()
                        }
                        ?: activity.getString(
                            R.string.watch_video_ad_to_proceed
                        )

                val dialog =
                    Dialog(activity).apply {

                        requestWindowFeature(
                            Window.FEATURE_NO_TITLE
                        )

                        setContentView(
                            R.layout.watchad_dialog
                        )

                        setCancelable(true)

                        setCanceledOnTouchOutside(true)

                        window?.setBackgroundDrawableResource(
                            android.R.color.transparent
                        )

                        findViewById<TextView?>(
                            R.id.dialog_header
                        )?.apply {
                            text = title
                        }

                        findViewById<TextView?>(
                            R.id.dialog_title
                        )?.apply {
                            text = message
                        }

                        findViewById<TextView?>(
                            R.id.btn_close
                        )?.setOnClickListener {
                            runCatching {
                                dismiss()
                            }

                            callSkipOnce()
                        }

                        findViewById<MaterialButton?>(
                            R.id.btnSkip
                        )?.apply {
                            setTextColor(primaryColor)
                            strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 120))
                            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 28))
                            setOnClickListener {

                                runCatching {
                                    dismiss()
                                }

                                callSkipOnce()
                            }
                        }

                        findViewById<MaterialButton?>(
                            R.id.btn_watch
                        )?.apply {
                            val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.58) {
                                Color.BLACK
                            } else {
                                Color.WHITE
                            }
                            backgroundTintList = ColorStateList.valueOf(primaryColor)
                            setTextColor(onPrimary)
                            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 44))
                            setOnClickListener {

                                runCatching {
                                    dismiss()
                                }

                                callWatchOnce()
                            }
                        }

                        setOnCancelListener {

                            callSkipOnce()
                        }

                        setOnDismissListener {

                            if (!callbackCalled) {
                                callSkipOnce()
                            }
                        }
                    }

                if (!isActivityUsable(activity)) {
                    callSkipOnce()
                    return@runCatching
                }

                runCatching {
                    dialog.show()
                    dialog.window?.setLayout(
                        activity.dialogWidth(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                }.onFailure {
                    callSkipOnce()
                }

            }.onFailure {

                callSkipOnce()
            }
        }
    }

    private fun runOnMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            block()
        } else {
            mainHandler.post {
                block()
            }
        }
    }

    private fun isActivityUsable(
        activity: Activity
    ): Boolean {
        return !activity.isFinishing &&
                !activity.isDestroyed
    }

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val maxWidth = (430 * density).toInt()
        val margin = (28 * density).toInt()
        return minOf(maxWidth, screenWidth - margin).coerceAtLeast((300 * density).toInt())
    }
}
