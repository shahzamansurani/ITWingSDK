package com.itwingtech.itwingsdk.ui

import android.app.Activity
import com.itwingtech.itwingsdk.ads.AdLoadingDialog

class ITWingLoadingDialog internal constructor(
    private val activity: Activity,
    private val defaultLottieUrlProvider: () -> String?,
) {
    private val delegate = AdLoadingDialog(activity)

    @JvmOverloads
    fun show(lottieUrl: String? = defaultLottieUrlProvider()) {
        if (activity.isFinishing || activity.isDestroyed) return
        delegate.show(lottieUrl)
    }

    fun dismiss() {
        delegate.dismiss()
    }
}
