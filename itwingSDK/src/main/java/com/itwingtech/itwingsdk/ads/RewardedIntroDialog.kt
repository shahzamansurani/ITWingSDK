package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.core.AdPlacementConfig
import com.itwingtech.itwingsdk.utils.safeCallback

internal object RewardedIntroDialog {
    fun show(activity: Activity, placement: AdPlacementConfig, onSkip: () -> Unit, onWatch: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            safeCallback(onSkip)
            return
        }

        val message = (placement.metadata["intro_message"] as? String)
            ?: activity.getString(R.string.watch_video_ad_to_proceed)

        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.watchad_dialog)
            setCancelable(true)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            findViewById<TextView?>(R.id.dialog_title)?.text = message
            findViewById<MaterialButton?>(R.id.btnSkip)?.setOnClickListener {
                dismiss()
                safeCallback(onSkip)
            }
            findViewById<MaterialButton?>(R.id.btn_watch)?.setOnClickListener {
                dismiss()
                safeCallback(onWatch)
            }
            show()
        }
    }
}
