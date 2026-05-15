package com.itwingtech.itwingsdk.core

import android.content.Context
import com.onesignal.OneSignal
import com.itwingtech.itwingsdk.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal object NotificationRuntimeManager {
    private val initializedAppId = AtomicReference<String?>(null)
    private val registeredToken = AtomicReference<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configure(context: Context, config: ITWingConfig, repository: ConfigRepository? = null) {
        val notifications = config.notifications
        if (!notifications.enabled) return

        if (notifications.provider == "fcm") {
            registerFcmIfAvailable(repository)
            return
        }

        val appId = notifications.onesignalAppId?.takeIf { it.isNotBlank() } ?: return

        if (initializedAppId.get() != appId && initializedAppId.compareAndSet(initializedAppId.get(), appId)) {
            runCatching {
                OneSignal.initWithContext(context.applicationContext, appId)
            }
        }

        if (notifications.tags.isNotEmpty()) {
            runCatching {
                OneSignal.User.addTags(notifications.tags)
            }
        }

        if (notifications.promptForPermission) {
            scope.launch {
                runCatching {
                    OneSignal.Notifications.requestPermission(true)
                }
            }
        }
    }

    fun registerDeviceToken(token: String, provider: String = "fcm", repository: ConfigRepository?) {
        if (token.isBlank() || repository == null) return
        if (registeredToken.get() == token) return
        registeredToken.set(token)
        scope.launch {
            runCatching { repository.registerNotificationDevice(token, provider) }
        }
    }

    private fun registerFcmIfAvailable(repository: ConfigRepository?) {
        if (repository == null) return
        runCatching {
            val messaging = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            val instance = messaging.getMethod("getInstance").invoke(null)
            val task = messaging.getMethod("getToken").invoke(instance)
            val listenerClass = Class.forName("com.google.android.gms.tasks.OnCompleteListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, _, args ->
                val completedTask = args?.firstOrNull()
                val successful = completedTask?.javaClass?.getMethod("isSuccessful")?.invoke(completedTask) as? Boolean ?: false
                if (successful) {
                    val token = completedTask.javaClass.getMethod("getResult").invoke(completedTask) as? String
                    if (!token.isNullOrBlank()) registerDeviceToken(token, "fcm", repository)
                }
                null
            }
            task.javaClass.getMethod("addOnCompleteListener", listenerClass).invoke(task, proxy)
        }
    }
}
