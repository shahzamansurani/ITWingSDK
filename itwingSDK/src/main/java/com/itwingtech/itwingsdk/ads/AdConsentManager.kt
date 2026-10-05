package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small, explicit UMP state machine. A UMP transport/form error is retryable;
 * it is not silently converted into a permanent SDK-wide ad disablement.
 */
internal object AdConsentManager {
    enum class State {
        UNKNOWN,
        REQUESTING,
        CAN_REQUEST_ADS,
        CANNOT_REQUEST_ADS,
        ERROR_RETRYABLE,
    }

    data class Result(
        val canRequestAds: Boolean,
        val state: State,
        val reason: String,
    )

    private const val FORM_WAIT_ATTEMPTS = 20
    private const val FORM_WAIT_DELAY_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = CopyOnWriteArrayList<(Result) -> Unit>()
    @Volatile private var consentInformation: ConsentInformation? = null
    @Volatile private var state = State.UNKNOWN
    @Volatile private var requestInFlight = false

    fun state(): State = state

    fun canRequestAds(): Boolean =
        state == State.CAN_REQUEST_ADS && consentInformation?.canRequestAds() == true

    fun requestConsent(activity: Activity, appId: String, onComplete: (Result) -> Unit) {
        if (state == State.CAN_REQUEST_ADS || state == State.CANNOT_REQUEST_ADS) {
            onComplete(finalResult(if (state == State.CAN_REQUEST_ADS) "cached_state" else "cached_denial"))
            return
        }

        callbacks += onComplete
        if (requestInFlight) return
        requestInFlight = true
        state = State.REQUESTING

        fun finish(reason: String, retryable: Boolean = false) {
            val info = consentInformation
            val allowed = runCatching { info?.canRequestAds() == true }.getOrDefault(false)
            state = when {
                allowed -> State.CAN_REQUEST_ADS
                retryable -> State.ERROR_RETRYABLE
                else -> State.CANNOT_REQUEST_ADS
            }
            requestInFlight = false
            val result = Result(allowed, state, reason)
            val waiting = callbacks.toList()
            callbacks.clear()
            waiting.forEach { callback -> callback(result) }
        }

        fun finishFromForm(formErrorReason: String?) {
            finish(formErrorReason ?: "consent_form_done", retryable = formErrorReason != null)
        }

        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                finish("activity_unavailable", retryable = true)
                return@post
            }

            val info = UserMessagingPlatform.getConsentInformation(activity.applicationContext)
            consentInformation = info
            val params = ConsentRequestParameters.Builder()
                .setAdMobAppId(appId)
                .build()
            info.requestConsentInfoUpdate(
                activity,
                params,
                {
                    val status = runCatching { info.consentStatus }.getOrDefault(-1)
                    val canRequest = runCatching { info.canRequestAds() }.getOrDefault(false)
                    ConsentTrace.emit(activity, "CONSENT_INFO_SUCCESS", "status=$status canRequestAds=$canRequest")
                    if (!info.isConsentFormAvailable) {
                        finish(if (canRequest) "consent_not_required" else "consent_unavailable")
                        return@requestConsentInfoUpdate
                    }

                    ConsentTrace.emit(activity, "CONSENT_FORM_REQUIRED")
                    fun showWhenResumed(attempt: Int) {
                        if (activity.isFinishing || activity.isDestroyed) {
                            finish("activity_unavailable", retryable = true)
                        } else if (attempt >= FORM_WAIT_ATTEMPTS) {
                            finish("consent_form_not_presented", retryable = true)
                        } else if (!activity.window.decorView.isShown) {
                            mainHandler.postDelayed({ showWhenResumed(attempt + 1) }, FORM_WAIT_DELAY_MS)
                        } else {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                                ConsentTrace.emit(activity, "CONSENT_FORM_DONE", "error=${formError?.errorCode ?: 0}")
                                finishFromForm(formError?.message?.take(120)?.replace("\n", " "))
                            }
                        }
                    }
                    showWhenResumed(0)
                },
                { error ->
                    val canRequestAfterError = runCatching { info.canRequestAds() }.getOrDefault(false)
                    ConsentTrace.emit(
                        activity,
                        "CONSENT_INFO_ERROR",
                        "code=${error.errorCode} message=${error.message.take(120).replace("\n", " ")}",
                    )
                    // UMP retains the previous session state on update failure. Honor it
                    // when it allows requests; otherwise leave the result retryable.
                    finish(
                        reason = if (canRequestAfterError) "consent_update_error_previous_state_allowed" else "consent_update_error_retryable",
                        retryable = !canRequestAfterError,
                    )
                },
            )
        }
    }

    private fun finalResult(reason: String): Result {
        val allowed = canRequestAds()
        return Result(allowed, state, reason)
    }
}

private object ConsentTrace {
    fun emit(activity: Activity, stage: String, details: String = "") {
        com.itwingtech.itwingsdk.core.StartupTrace.event(activity, stage, details)
    }
}
