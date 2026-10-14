package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import com.airbnb.lottie.LottieAnimationView
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.ui.SdkDialogTheme
import com.itwingtech.itwingsdk.ui.SdkDialogWindow

internal class AdLoadingDialog(
    private val activity: Activity
) {
    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var dialog: Dialog? = null

    fun show(
        lottieUrl: String?
    ) {
        runOnMain {

            if (!isActivityUsable()) {
                return@runOnMain
            }

            if (dialog?.isShowing == true) {
                return@runOnMain
            }

            runCatching {

                dialog =
                    Dialog(activity).apply {

                        requestWindowFeature(
                            Window.FEATURE_NO_TITLE
                        )

                        setContentView(
                            R.layout.dialog_loading
                        )

                        setCancelable(false)

                        window?.setBackgroundDrawableResource(
                            android.R.color.transparent
                        )

                        findViewById<LottieAnimationView?>(
                            R.id.lottieAnimationView
                        )?.let { lottie ->

                            lottie.setColorFilter(SdkDialogTheme.primaryColor(activity))

                            if (!lottieUrl.isNullOrBlank()) {

                                runCatching {

                                    lottie.setAnimationFromUrl(
                                        lottieUrl
                                    )

                                    lottie.playAnimation()
                                }
                            }
                        }

                        if (isActivityUsable()) {
                            show()
                            SdkDialogWindow.apply(
                                window,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT
                            )
                        }
                    }

            }.onFailure {

                dialog =
                    null
            }
        }
    }

    fun dismiss() {
        runOnMain {

            runCatching {

                dialog
                    ?.takeIf {
                        it.isShowing
                    }
                    ?.dismiss()
            }

            dialog =
                null
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

    private fun isActivityUsable(): Boolean {
        return !activity.isFinishing &&
                !activity.isDestroyed
    }
}
