package com.itwingtech.itwingsdk.updates

import android.app.Activity
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

    fun check(activity: Activity, force: Boolean = false) {
        if (!activity.isUsable()) return
        val settings = settings() ?: return
        val enabled = settings.boolean("enabled", false)
        if (!enabled) return

        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_THROTTLE_MS) {
            resumeIfNeeded(activity)
            return
        }
        lastCheckMs = now
        activityRef = WeakReference(activity)

        val updateType = when (settings.string("type")?.lowercase()) {
            "immediate" -> AppUpdateType.IMMEDIATE
            else -> AppUpdateType.FLEXIBLE
        }
        val minStalenessDays = settings.int("min_staleness_days", 0)
        val minPriority = settings.int("priority", 0)
        val appUpdateManager = manager ?: AppUpdateManagerFactory.create(activity.applicationContext).also {
            manager = it
        }
        registerFlexibleListener(appUpdateManager)

        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val staleEnough = minStalenessDays <= 0 || (info.clientVersionStalenessDays() ?: -1) >= minStalenessDays
            val priorityEnough = minPriority <= 0 || info.updatePriority() >= minPriority
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
                    startUpdateFlow(appUpdateManager, activity, info, AppUpdateType.IMMEDIATE)
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    completeFlexibleUpdate(appUpdateManager)
                }

                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && staleEnough && priorityEnough -> {
                    val typeToLaunch = when {
                        info.isUpdateTypeAllowed(updateType) -> updateType
                        updateType == AppUpdateType.IMMEDIATE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                        updateType == AppUpdateType.FLEXIBLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                        else -> null
                    }
                    if (typeToLaunch != null) {
                        startUpdateFlow(appUpdateManager, activity, info, typeToLaunch)
                    } else {
                        SDKTelemetry.track("in_app_update_not_allowed")
                    }
                }

                else -> Unit
            }
        }.addOnFailureListener {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_check"))
            SDKTelemetry.track("in_app_update_check_failed", mapOf("message" to (it.message ?: "unknown")))
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
                    startUpdateFlow(appUpdateManager, activity, info, AppUpdateType.IMMEDIATE)
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    completeFlexibleUpdate(appUpdateManager)
                }
            }
        }
    }

    private fun startUpdateFlow(
        appUpdateManager: AppUpdateManager,
        activity: Activity,
        info: AppUpdateInfo,
        updateType: Int,
    ) {
        if (!activity.isUsable()) return
        if (!flowInProgress.compareAndSet(false, true)) return

        SDKTelemetry.track("in_app_update_flow_starting", mapOf("type" to updateType.label()))
        appUpdateManager.startUpdateFlow(
            info,
            activity,
            AppUpdateOptions.newBuilder(updateType).build(),
        ).addOnSuccessListener {
            SDKTelemetry.track("in_app_update_flow_started", mapOf("type" to updateType.label(), "result" to it))
            if (updateType == AppUpdateType.FLEXIBLE) {
                flowInProgress.set(false)
            }
        }.addOnFailureListener {
            flowInProgress.set(false)
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_update_flow", "type" to updateType.label()))
            SDKTelemetry.track("in_app_update_flow_failed", mapOf("type" to updateType.label(), "message" to (it.message ?: "unknown")))
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
