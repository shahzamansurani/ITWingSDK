package com.itwingtech.itwingsdk.updates

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.itwingtech.itwingsdk.ads.FullscreenAdState
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.ITWingConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class InAppUpdateManager(private val configProvider: () -> ITWingConfig) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: AppUpdateManager? = null
    private var activityRef: WeakReference<Activity>? = null
    private val flowInProgress = AtomicBoolean(false)
    private val pendingAutomaticCheck = AtomicBoolean(false)
    private val preSplashCheckInFlight = AtomicBoolean(false)
    private var lastCheckMs: Long = 0L
    private var installStateListener: InstallStateUpdatedListener? = null
    private var automaticLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var automaticLauncherOwner: WeakReference<ComponentActivity>? = null
    private var activeFullscreenOwner: String? = null
    private var pendingAfterUpdateFlow: (() -> Unit)? = null

    fun bind(activity: Activity) {
        val owner = activity as? ComponentActivity ?: run {
            SDKTelemetry.track("in_app_update_auto_launcher_unavailable", mapOf("reason" to "activity_not_component"))
            return
        }
        if (!owner.isUsable()) return
        if (automaticLauncher != null && automaticLauncherOwner?.get() === owner) return
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            SDKTelemetry.track("in_app_update_auto_launcher_unavailable", mapOf("reason" to "activity_already_started"))
            return
        }

        runCatching {
            owner.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                flowInProgress.set(false)
                endActiveFullscreenOwner()
                SDKTelemetry.track(
                    "in_app_update_flow_result",
                    mapOf("result_code" to result.resultCode),
                )
                if (result.resultCode == Activity.RESULT_OK) {
                    // An accepted immediate update restarts the app. Do not continue into a
                    // splash ad in the old process, otherwise users can receive two ads.
                    pendingAfterUpdateFlow = null
                    preSplashCheckInFlight.set(false)
                } else {
                    completePendingUpdateContinuation()
                }
            }
        }.onSuccess {
            automaticLauncher = it
            automaticLauncherOwner = WeakReference(owner)
            SDKTelemetry.track("in_app_update_auto_launcher_registered")
        }.onFailure {
            automaticLauncher = null
            automaticLauncherOwner = null
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_auto_launcher_register"))
            SDKTelemetry.track("in_app_update_auto_launcher_failed", mapOf("message" to (it.message ?: "unknown")))
        }
    }

    fun checkBeforeSplash(activity: Activity, onContinue: () -> Unit) {
        if (!activity.isUsable()) {
            onContinue()
            return
        }
        val settings = settings()
        val enabled = settings?.boolean("enabled", false) ?: false
        if (!enabled) {
            onContinue()
            return
        }
        if (flowInProgress.get()) {
            pendingAfterUpdateFlow = onContinue
            return
        }
        if (!preSplashCheckInFlight.compareAndSet(false, true)) {
            pendingAfterUpdateFlow = onContinue
            return
        }

        var continued = false
        fun continueOnce() {
            if (continued) return
            continued = true
            preSplashCheckInFlight.set(false)
            onContinue()
        }

        check(
            activity = activity,
            force = true,
            launcher = automaticLauncher,
            deferForFullscreen = false,
            onFlowStarted = {
                pendingAfterUpdateFlow = ::continueOnce
            },
            onNoFlow = ::continueOnce,
        )
    }

    fun check(
        activity: Activity,
        force: Boolean = false,
        launcher: ActivityResultLauncher<IntentSenderRequest>? = null,
        onResult: ((String) -> Unit)? = null,
        deferForFullscreen: Boolean = true,
        onFlowStarted: (() -> Unit)? = null,
        onNoFlow: (() -> Unit)? = null,
    ) {
        if (!activity.isUsable()) {
            onResult?.invoke("Activity is not available for in-app updates.")
            onNoFlow?.invoke()
            return
        }

        if (!force && deferForFullscreen && FullscreenAdState.isActive()) {
            pendingAutomaticCheck.set(true)
            scheduleAutomaticRetry(activity)
            SDKTelemetry.track(
                "in_app_update_deferred",
                mapOf("reason" to "fullscreen_flow_active", "owner" to FullscreenAdState.activeOwner()),
            )
            onResult?.invoke("In-app update check deferred until splash/ad flow finishes.")
            onNoFlow?.invoke()
            return
        }

        val settings = settings()
        val enabled = settings?.boolean("enabled", false) ?: force
        if (!enabled) {
            onResult?.invoke("In-app updates are disabled in admin config.")
            onNoFlow?.invoke()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_THROTTLE_MS) {
            resumeIfNeeded(activity)
            onResult?.invoke("In-app update check throttled; resumed any pending update.")
            onNoFlow?.invoke()
            return
        }
        lastCheckMs = now
        activityRef = WeakReference(activity)

        val updateType = when (settings?.string("type")?.lowercase()) {
            "flexible" -> AppUpdateType.FLEXIBLE
            else -> AppUpdateType.IMMEDIATE
        }
        val minStalenessDays = settings?.int("min_staleness_days", 0) ?: 0
        val minPriority = settings?.int("priority", 0) ?: 0
        val appUpdateManager = manager ?: AppUpdateManagerFactory.create(activity).also {
            manager = it
        }
        registerFlexibleListener(appUpdateManager)

        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val staleEnough = force || minStalenessDays <= 0 || (info.clientVersionStalenessDays() ?: -1) >= minStalenessDays
            val priorityEnough = force || minPriority <= 0 || info.updatePriority() >= minPriority
            SDKTelemetry.track(
                "in_app_update_checked",
                mapOf(
                    "availability" to info.updateAvailability(),
                    "configured_type" to updateType.label(),
                    "staleness_days" to (info.clientVersionStalenessDays() ?: -1),
                    "priority" to info.updatePriority(),
                    "stale_enough" to staleEnough,
                    "priority_enough" to priorityEnough,
                    "immediate_allowed" to info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                    "flexible_allowed" to info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                ),
            )

            when {
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    startUpdateFlow(appUpdateManager, activity, info, AppUpdateType.IMMEDIATE, launcher ?: automaticLauncher, onResult, onFlowStarted, onNoFlow)
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    completeFlexibleUpdate(appUpdateManager)
                    onResult?.invoke("Downloaded flexible update is being completed.")
                    onNoFlow?.invoke()
                }

                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && staleEnough && priorityEnough -> {
                    val typeToLaunch = when {
                        onFlowStarted != null && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                        info.isUpdateTypeAllowed(updateType) -> updateType
                        updateType == AppUpdateType.IMMEDIATE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                        updateType == AppUpdateType.FLEXIBLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                        else -> null
                    }
                    if (typeToLaunch != null) {
                        startUpdateFlow(appUpdateManager, activity, info, typeToLaunch, launcher ?: automaticLauncher, onResult, onFlowStarted, onNoFlow)
                    } else {
                        SDKTelemetry.track("in_app_update_not_allowed")
                        onResult?.invoke("A Play update is available, but Play does not allow immediate or flexible update for this install.")
                        onNoFlow?.invoke()
                    }
                }

                info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> {
                    onResult?.invoke("No Play update is available for this install.")
                    onNoFlow?.invoke()
                }

                !staleEnough -> {
                    onResult?.invoke("Update is available but below configured staleness threshold.")
                    onNoFlow?.invoke()
                }

                !priorityEnough -> {
                    onResult?.invoke("Update is available but below configured priority threshold.")
                    onNoFlow?.invoke()
                }

                else -> {
                    onResult?.invoke("In-app update is not available right now.")
                    onNoFlow?.invoke()
                }
            }
        }.addOnFailureListener {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_check"))
            SDKTelemetry.track("in_app_update_check_failed", mapOf("message" to (it.message ?: "unknown")))
            onResult?.invoke("In-app update check failed: ${it.message ?: "unknown error"}")
            onNoFlow?.invoke()
        }
    }

    fun onResume(activity: Activity) {
        if (!activity.isUsable()) return
        resumeIfNeeded(activity)
        if (pendingAutomaticCheck.get()) {
            scheduleAutomaticRetry(activity, delayMs = 700L)
        }
    }

    private fun resumeIfNeeded(activity: Activity) {
        val appUpdateManager = manager ?: return
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    startUpdateFlow(appUpdateManager, activity, info, AppUpdateType.IMMEDIATE, automaticLauncher, null)
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    completeFlexibleUpdate(appUpdateManager)
                    completePendingUpdateContinuation()
                }

                else -> {
                    flowInProgress.set(false)
                    endActiveFullscreenOwner()
                    completePendingUpdateContinuation()
                }
            }
        }
    }

    private fun startUpdateFlow(
        appUpdateManager: AppUpdateManager,
        activity: Activity,
        info: AppUpdateInfo,
        updateType: Int,
        launcher: ActivityResultLauncher<IntentSenderRequest>?,
        onResult: ((String) -> Unit)?,
        onFlowStarted: (() -> Unit)? = null,
        onNoFlow: (() -> Unit)? = null,
    ) {
        if (!activity.isUsable()) {
            onResult?.invoke("Activity is not available for update flow.")
            onNoFlow?.invoke()
            return
        }
        if (!flowInProgress.compareAndSet(false, true)) {
            onResult?.invoke("An in-app update flow is already running.")
            if (onFlowStarted != null) {
                onFlowStarted.invoke()
            } else {
                onNoFlow?.invoke()
            }
            return
        }
        val fullscreenOwner = FullscreenAdState.tryBegin("play_update", "in_app_update")
        if (fullscreenOwner == null) {
            flowInProgress.set(false)
            pendingAutomaticCheck.set(true)
            scheduleAutomaticRetry(activity)
            SDKTelemetry.track(
                "in_app_update_deferred",
                mapOf("reason" to "fullscreen_flow_active", "owner" to FullscreenAdState.activeOwner()),
            )
            onResult?.invoke("In-app update flow deferred because another full-screen flow is active.")
            onNoFlow?.invoke()
            return
        }
        activeFullscreenOwner = fullscreenOwner

        SDKTelemetry.track("in_app_update_flow_starting", mapOf("type" to updateType.label()))
        if (launcher != null) {
            runCatching {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    launcher,
                    AppUpdateOptions.newBuilder(updateType).build(),
                )
            }.onSuccess { started ->
                SDKTelemetry.track("in_app_update_flow_started", mapOf("type" to updateType.label(), "result" to started))
                if (started) {
                    onResult?.invoke("In-app update flow started (${updateType.label()}).")
                    onFlowStarted?.invoke()
                } else {
                    flowInProgress.set(false)
                    endActiveFullscreenOwner()
                    onResult?.invoke("Google Play did not start the in-app update flow.")
                    onNoFlow?.invoke()
                }
            }.onFailure {
                flowInProgress.set(false)
                endActiveFullscreenOwner()
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_flow", "type" to updateType.label()))
                SDKTelemetry.track("in_app_update_flow_failed", mapOf("type" to updateType.label(), "message" to (it.message ?: "unknown")))
                onResult?.invoke("In-app update flow failed: ${it.message ?: "unknown error"}")
                onNoFlow?.invoke()
            }
            return
        }

        appUpdateManager.startUpdateFlow(
            info,
            activity,
            AppUpdateOptions.newBuilder(updateType).build(),
        ).addOnSuccessListener {
            SDKTelemetry.track("in_app_update_flow_started", mapOf("type" to updateType.label(), "result" to it))
            onResult?.invoke("In-app update flow started (${updateType.label()}).")
            onFlowStarted?.invoke()
        }.addOnFailureListener {
            flowInProgress.set(false)
            endActiveFullscreenOwner()
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_flow", "type" to updateType.label()))
            SDKTelemetry.track("in_app_update_flow_failed", mapOf("type" to updateType.label(), "message" to (it.message ?: "unknown")))
            onResult?.invoke("In-app update flow failed: ${it.message ?: "unknown error"}")
            onNoFlow?.invoke()
        }
    }

    private fun registerFlexibleListener(appUpdateManager: AppUpdateManager) {
        if (installStateListener != null) return
        installStateListener = InstallStateUpdatedListener { state ->
            SDKTelemetry.track("in_app_update_install_state", mapOf("status" to state.installStatus()))
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                completeFlexibleUpdate(appUpdateManager)
            }
        }.also {
            appUpdateManager.registerListener(it)
        }
    }

    private fun completeFlexibleUpdate(appUpdateManager: AppUpdateManager) {
        SDKTelemetry.track("in_app_update_completing")
        appUpdateManager.completeUpdate()
            .addOnSuccessListener {
                flowInProgress.set(false)
                endActiveFullscreenOwner()
                completePendingUpdateContinuation()
                SDKTelemetry.track("in_app_update_completed")
            }
            .addOnFailureListener {
                flowInProgress.set(false)
                endActiveFullscreenOwner()
                completePendingUpdateContinuation()
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_complete"))
                SDKTelemetry.track("in_app_update_complete_failed", mapOf("message" to (it.message ?: "unknown")))
            }
    }

    private fun scheduleAutomaticRetry(activity: Activity, delayMs: Long = 1500L, attempt: Int = 0) {
        if (attempt > MAX_DEFERRED_RETRIES) return
        val activityReference = WeakReference(activity)
        mainHandler.postDelayed({
            val current = activityReference.get()
            if (current == null || !current.isUsable() || !pendingAutomaticCheck.get()) {
                return@postDelayed
            }
            if (FullscreenAdState.isActive()) {
                scheduleAutomaticRetry(current, delayMs, attempt + 1)
                return@postDelayed
            }
            pendingAutomaticCheck.set(false)
            check(current, force = false)
        }, delayMs)
    }

    private fun endActiveFullscreenOwner() {
        FullscreenAdState.end(activeFullscreenOwner)
        activeFullscreenOwner = null
    }

    private fun completePendingUpdateContinuation() {
        val continuation = pendingAfterUpdateFlow
        pendingAfterUpdateFlow = null
        preSplashCheckInFlight.set(false)
        continuation?.invoke()
    }

    private fun settings(): Map<*, *>? = configProvider().app["in_app_updates"] as? Map<*, *>

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
        else -> default
    }

    private fun Map<*, *>.int(key: String, default: Int): Int = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Int.label(): String = when (this) {
        AppUpdateType.IMMEDIATE -> "immediate"
        AppUpdateType.FLEXIBLE -> "flexible"
        else -> toString()
    }

    companion object {
        private const val CHECK_THROTTLE_MS = 6 * 60 * 60 * 1000L
        private const val MAX_DEFERRED_RETRIES = 20
    }
}
