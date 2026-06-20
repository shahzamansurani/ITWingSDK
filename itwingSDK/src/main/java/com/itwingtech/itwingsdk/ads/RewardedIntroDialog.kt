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
                            R.id.dialog_title
                        )?.apply {
                            text = message
                            setTextColor(primaryColor)
                        }

                        findViewById<MaterialButton?>(
                            R.id.btnSkip
                        )?.apply {
                            setTextColor(primaryColor)
                            strokeColor = ColorStateList.valueOf(primaryColor)
                            strokeWidth = activity.resources.displayMetrics.density.toInt().coerceAtLeast(1)
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
                            backgroundTintList = ColorStateList.valueOf(primaryColor)
                            setTextColor(Color.WHITE)
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

                dialog.show()

                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )

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
}

//package com.itwingtech.itwingsdk.ads

//import android.app.Activity
//import android.app.Dialog
//import android.view.Window
//import android.view.WindowManager
//import android.widget.TextView
//import com.google.android.material.button.MaterialButton
//import com.itwingtech.itwingsdk.R
//import com.itwingtech.itwingsdk.core.AdPlacementConfig
//import com.itwingtech.itwingsdk.utils.safeCallback
//
//internal object RewardedIntroDialog {
//    fun show(activity: Activity, placement: AdPlacementConfig, onSkip: () -> Unit, onWatch: () -> Unit) {
//        if (activity.isFinishing || activity.isDestroyed) {
//            safeCallback(onSkip)
//            return
//        }
//
//        val message = (placement.metadata["intro_message"] as? String)
//            ?: activity.getString(R.string.watch_video_ad_to_proceed)
//
//        Dialog(activity).apply {
//            requestWindowFeature(Window.FEATURE_NO_TITLE)
//            setContentView(R.layout.watchad_dialog)
//            setCancelable(true)
//            window?.setBackgroundDrawableResource(android.R.color.transparent)
//            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
//            findViewById<TextView?>(R.id.dialog_title)?.text = message
//            findViewById<MaterialButton?>(R.id.btnSkip)?.setOnClickListener {
//                dismiss()
//                safeCallback(onSkip)
//            }
//            findViewById<MaterialButton?>(R.id.btn_watch)?.setOnClickListener {
//                dismiss()
//                safeCallback(onWatch)
//            }
//            show()
//        }
//    }
//}
