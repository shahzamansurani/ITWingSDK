package com.itwingtech.itwingsdk.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.core.FirebaseRuntimeManager
import com.itwingtech.itwingsdk.core.NotificationRuntimeManager

class ITWingFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        FirebaseRuntimeManager.configureFromCache(applicationContext)
    }

    override fun onNewToken(token: String) {
        NotificationRuntimeManager.onFirebaseToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        SDKTelemetry.track(
            "push_message_received",
            mapOf(
                "notification_id" to data["itwing_notification_id"],
                "campaign_id" to data["itwing_campaign_id"],
                "from" to message.from,
            ),
        )
        NotificationRuntimeManager.showPushNotification(applicationContext, data)
    }
}
