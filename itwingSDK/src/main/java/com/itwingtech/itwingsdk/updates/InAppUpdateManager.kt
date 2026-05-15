package com.itwingtech.itwingsdk.updates

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.itwingtech.itwingsdk.core.ITWingConfig

class InAppUpdateManager(private val configProvider: () -> ITWingConfig) {
    fun check(activity: Activity) {
        val settings = configProvider().app["in_app_updates"] as? Map<*, *> ?: return
        val enabled = settings["enabled"] as? Boolean ?: false
        if (!enabled) return

        val updateType = when ((settings["type"] as? String)?.lowercase()) {
            "immediate" -> AppUpdateType.IMMEDIATE
            else -> AppUpdateType.FLEXIBLE
        }
        val minStalenessDays = (settings["min_staleness_days"] as? Number)?.toInt() ?: 0
        val minPriority = (settings["priority"] as? Number)?.toInt() ?: 0
        val manager = AppUpdateManagerFactory.create(activity)

        manager.appUpdateInfo.addOnSuccessListener { info ->
            val staleEnough = minStalenessDays <= 0 || (info.clientVersionStalenessDays() ?: -1) >= minStalenessDays
            val priorityEnough = minPriority <= 0 || info.updatePriority() >= minPriority
            if (
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(updateType) &&
                staleEnough &&
                priorityEnough
            ) {
                manager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(updateType).build())
            }
        }
    }
}
