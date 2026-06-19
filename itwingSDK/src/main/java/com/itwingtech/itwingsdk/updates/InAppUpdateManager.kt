package com.itwingtech.itwingsdk.updates

import android.app.Activity
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
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.ITWingConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class InAppUpdateManager(private val configProvider: () -> ITWingConfig) {
    private var manager: AppUpdateManager? = null
    private var activityRef: WeakReference<Activity>? = null
    private val flowInProgress = AtomicBoolean(false)
    private var lastCheckMs: Long = 0L
    private var installStateListener: InstallStateUpdatedListener? = null
    private var automaticLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var automaticLauncherOwner: WeakReference<ComponentActivity>? = null

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
                SDKTelemetry.track(
                    "in_app_update_flow_result",
                    mapOf("result_code" to result.resultCode),
                )
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

    fun check(
        activity: Activity,
        force: Boolean = false,
        launcher: ActivityResultLauncher<IntentSenderRequest>? = null,
        onResult: ((String) -> Unit)? = null,
    ) {
        if (!activity.isUsable()) {
            onResult?.invoke("Activity is not available for in-app updates.")
            return
        }

        val settings = settings()
        val enabled = settings?.boolean("enabled", false) ?: force
        if (!enabled) {
            onResult?.invoke("In-app updates are disabled in admin config.")
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_THROTTLE_MS) {
            resumeIfNeeded(activity)
            onResult?.invoke("In-app update check throttled; resumed any pending update.")
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
                    startUpdateFlow(appUpdateManager, activity, info, AppUpdateType.IMMEDIATE, launcher ?: automaticLauncher, onResult)
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    completeFlexibleUpdate(appUpdateManager)
                    onResult?.invoke("Downloaded flexible update is being completed.")
                }

                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && staleEnough && priorityEnough -> {
                    val typeToLaunch = when {
                        info.isUpdateTypeAllowed(updateType) -> updateType
                        updateType == AppUpdateType.IMMEDIATE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                        updateType == AppUpdateType.FLEXIBLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                        else -> null
                    }
                    if (typeToLaunch != null) {
                        startUpdateFlow(appUpdateManager, activity, info, typeToLaunch, launcher ?: automaticLauncher, onResult)
                    } else {
                        SDKTelemetry.track("in_app_update_not_allowed")
                        onResult?.invoke("A Play update is available, but Play does not allow immediate or flexible update for this install.")
                    }
                }

                info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> {
                    onResult?.invoke("No Play update is available for this install.")
                }

                !staleEnough -> {
                    onResult?.invoke("Update is available but below configured staleness threshold.")
                }

                !priorityEnough -> {
                    onResult?.invoke("Update is available but below configured priority threshold.")
                }

                else -> {
                    onResult?.invoke("In-app update is not available right now.")
                }
            }
        }.addOnFailureListener {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_check"))
            SDKTelemetry.track("in_app_update_check_failed", mapOf("message" to (it.message ?: "unknown")))
            onResult?.invoke("In-app update check failed: ${it.message ?: "unknown error"}")
        }
    }

    fun onResume(activity: Activity) {
        if (!activity.isUsable()) return
        resumeIfNeeded(activity)
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
                }

                else -> {
                    flowInProgress.set(false)
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
    ) {
        if (!activity.isUsable()) {
            onResult?.invoke("Activity is not available for update flow.")
            return
        }
        if (!flowInProgress.compareAndSet(false, true)) {
            onResult?.invoke("An in-app update flow is already running.")
            return
        }

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
                } else {
                    flowInProgress.set(false)
                    onResult?.invoke("Google Play did not start the in-app update flow.")
                }
                if (updateType == AppUpdateType.FLEXIBLE) {
                    flowInProgress.set(false)
                }
            }.onFailure {
                flowInProgress.set(false)
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_flow", "type" to updateType.label()))
                SDKTelemetry.track("in_app_update_flow_failed", mapOf("type" to updateType.label(), "message" to (it.message ?: "unknown")))
                onResult?.invoke("In-app update flow failed: ${it.message ?: "unknown error"}")
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
            if (updateType == AppUpdateType.FLEXIBLE) {
                flowInProgress.set(false)
            }
        }.addOnFailureListener {
            flowInProgress.set(false)
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_flow", "type" to updateType.label()))
            SDKTelemetry.track("in_app_update_flow_failed", mapOf("type" to updateType.label(), "message" to (it.message ?: "unknown")))
            onResult?.invoke("In-app update flow failed: ${it.message ?: "unknown error"}")
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
                SDKTelemetry.track("in_app_update_completed")
            }
            .addOnFailureListener {
                flowInProgress.set(false)
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_complete"))
                SDKTelemetry.track("in_app_update_complete_failed", mapOf("message" to (it.message ?: "unknown")))
            }
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
    }
}
