package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.CopyOnWriteArrayList

/** Non-blocking process-wide UMP gate. Core SDK startup never waits indefinitely on consent UI. */
internal object AdConsentManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var consentInformation: ConsentInformation? = null
    @Volatile private var resolved = false
    @Volatile private var requestInFlight = false

    fun canRequestAds(): Boolean = resolved && consentInformation?.canRequestAds() == true

    fun requestConsent(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (resolved) {
            onComplete(canRequestAds())
            return
        }
        callbacks += onComplete
        if (requestInFlight) return
        requestInFlight = true
        val finish = {
            resolved = true
            requestInFlight = false
            val allowed = canRequestAds()
            val waiting = callbacks.toList()
            callbacks.clear()
            waiting.forEach { callback -> callback(allowed) }
        }
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                finish()
                return@post
            }
            val info = UserMessagingPlatform.getConsentInformation(activity.applicationContext)
            consentInformation = info
            val params = ConsentRequestParameters.Builder().build()
            info.requestConsentInfoUpdate(activity, params, {
                if (info.isConsentFormAvailable) {
                    fun showWhenResumed(attempt: Int) {
                        if (activity.isFinishing || activity.isDestroyed || attempt >= 20) {
                            finish()
                        } else if (!activity.window.decorView.isShown) {
                            mainHandler.postDelayed({ showWhenResumed(attempt + 1) }, 250L)
                        } else {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { finish() }
                        }
                    }
                    showWhenResumed(0)
                } else {
                    finish()
                }
            }, { finish() })
        }
    }
}
