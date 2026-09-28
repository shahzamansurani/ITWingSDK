package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** One process-wide UMP flow shared by SDK initialization and every Google ad format. */
internal object AdConsentManager {
    @Volatile private var consentInformation: ConsentInformation? = null
    @Volatile private var resolved = false
    @Volatile private var requestsAllowed = false
    private var requestInFlight = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    fun canRequestAds(): Boolean =
        resolved && requestsAllowed && runCatching { consentInformation?.canRequestAds() == true }.getOrDefault(false)

    fun isResolved(): Boolean = resolved

    fun requestConsent(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { requestConsent(activity, onComplete) }
            return
        }
        if (resolved) {
            onComplete(canRequestAds())
            return
        }
        callbacks += onComplete
        if (requestInFlight) return
        requestInFlight = true

        runCatching {
            val info = UserMessagingPlatform.getConsentInformation(activity.applicationContext)
            consentInformation = info
            val parameters = ConsentRequestParameters.Builder().build()
            info.requestConsentInfoUpdate(
                activity,
                parameters,
                {
                    // A consent form is UI. Never attempt to present one from a
                    // stale/destroyed Activity or while its lifecycle is paused.
                    if (!activity.isFinishing && !activity.isDestroyed && activity.isResumedForConsent()) {
                        runCatching {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                                finish(info, info.canRequestAds())
                            }
                        }.onFailure { finish(info, info.canRequestAds()) }
                    } else {
                        finish(info, info.canRequestAds())
                    }
                },
                {
                    // UMP retains valid prior consent; permit ads only if its
                    // authoritative persisted state says they may be requested.
                    finish(info, info.canRequestAds())
                },
            )
        }.onFailure {
            finish(consentInformation, runCatching { consentInformation?.canRequestAds() == true }.getOrDefault(false))
        }
    }

    fun privacyOptionsRequired(): Boolean = runCatching {
        consentInformation?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }.getOrDefault(false)

    fun showPrivacyOptions(activity: Activity, onComplete: (Boolean) -> Unit = {}) {
        if (!privacyOptionsRequired() || activity.isFinishing || activity.isDestroyed) {
            onComplete(false)
            return
        }
        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                requestsAllowed = runCatching { consentInformation?.canRequestAds() == true }.getOrDefault(false)
                onComplete(requestsAllowed)
            }
        }.onFailure { onComplete(false) }
    }

    private fun finish(info: ConsentInformation?, allowed: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            android.os.Handler(Looper.getMainLooper()).post { finish(info, allowed) }
            return
        }
        if (resolved) return
        consentInformation = info
        requestsAllowed = allowed
        resolved = true
        requestInFlight = false
        val result = canRequestAds()
        callbacks.toList().also { callbacks.clear() }.forEach { callback ->
            runCatching { callback(result) }
        }
    }

    private fun Activity.isResumedForConsent(): Boolean =
        if (this is LifecycleOwner) lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) else hasWindowFocus()
}
