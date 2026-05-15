package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import com.airbnb.lottie.LottieAnimationView
import com.itwingtech.itwingsdk.R

internal class AdLoadingDialog(private val activity: Activity) {
    private var dialog: Dialog? = null

    fun show(lottieUrl: String?) {
        if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) {
            return
        }

        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_loading)
            setCancelable(false)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            findViewById<LottieAnimationView?>(R.id.lottieAnimationView)?.let { lottie ->
                if (!lottieUrl.isNullOrBlank()) {
                    lottie.setAnimationFromUrl(lottieUrl)
                    lottie.playAnimation()
                }
            }
            show()
        }
    }

    fun dismiss() {
        runCatching {
            dialog?.takeIf { it.isShowing }?.dismiss()
        }
        dialog = null
    }
}
