package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import com.airbnb.lottie.LottieAnimationView
import com.itwingtech.itwingsdk.R

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

                        window?.setLayout(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT
                        )

                        findViewById<LottieAnimationView?>(
                            R.id.lottieAnimationView
                        )?.let { lottie ->

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

//package com.itwingtech.itwingsdk.ads
//
//import android.app.Activity
//import android.app.Dialog
//import android.view.Window
//import android.view.WindowManager
//import com.airbnb.lottie.LottieAnimationView
//import com.itwingtech.itwingsdk.R
//
//internal class AdLoadingDialog(private val activity: Activity) {
//    private var dialog: Dialog? = null
//
//    fun show(lottieUrl: String?) {
//        if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) {
//            return
//        }
//
//        dialog = Dialog(activity).apply {
//            requestWindowFeature(Window.FEATURE_NO_TITLE)
//            setContentView(R.layout.dialog_loading)
//            setCancelable(false)
//            window?.setBackgroundDrawableResource(android.R.color.transparent)
//            window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
//            findViewById<LottieAnimationView?>(R.id.lottieAnimationView)?.let { lottie ->
//                if (!lottieUrl.isNullOrBlank()) {
//                    lottie.setAnimationFromUrl(lottieUrl)
//                    lottie.playAnimation()
//                }
//            }
//            show()
//        }
//    }
//
//    fun dismiss() {
//        runCatching {
//            dialog?.takeIf { it.isShowing }?.dismiss()
//        }
//        dialog = null
//    }
//}
