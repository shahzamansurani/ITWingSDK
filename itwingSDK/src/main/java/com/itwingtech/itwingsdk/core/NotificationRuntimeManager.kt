package com.itwingtech.itwingsdk.core

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessaging
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import com.itwingtech.itwingsdk.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationRuntimeManager {
    private const val CHANNEL_ID = "itwing_sdk_notifications"
    private const val PERMISSION_REQUEST_CODE = 4721
    private const val PREFS_NAME = "itwing_sdk_notifications"
    private const val KEY_PENDING_FCM_TOKEN = "pending_fcm_token"
    private const val KEY_PENDING_DELIVERED_IDS = "pending_delivered_ids"
    private const val KEY_PENDING_OPENED_IDS = "pending_opened_ids"
    private const val KEY_SHOWN_NOTIFICATION_IDS = "shown_notification_ids"
    private const val KEY_OPENED_NOTIFICATION_IDS = "opened_notification_ids"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val shownIds = ConcurrentHashMap.newKeySet<String>()
    private var repository: ConfigRepository? = null
    private var appContext: Context? = null
    private var config: NotificationConfig = NotificationConfig()

    fun configure(activity: Activity, sdkConfig: ITWingConfig, repository: ConfigRepository? = null) {
        val notifications = sdkConfig.notifications
        if (repository == null) {
            SDKTelemetry.track("notifications_config_skipped", mapOf("reason" to "missing_repository"))
            return
        }

        this.repository = repository
        this.appContext = activity.applicationContext
        this.config = notifications
        createChannel(activity.applicationContext)
        registerFirebaseToken(activity.applicationContext)

        if (!notifications.enabled) {
            SDKTelemetry.track("notifications_config_skipped", mapOf("reason" to "delivery_disabled_token_registration_enabled"))
            return
        }

        SDKTelemetry.track(
            "notifications_configured",
            mapOf(
                "provider" to notifications.provider,
                "poll_interval_seconds" to notifications.pollIntervalSeconds,
                "topics" to notifications.topics.joinToString(","),
                "segments" to notifications.segments.joinToString(","),
            ),
        )

        if (notifications.promptForPermission) requestPermissionIfNeeded(activity)

        scope.launch {
            runCatching {
                repository.registerItwingNotificationDevice(
                    topics = notifications.topics,
                    segments = notifications.segments,
                    tags = notifications.tags,
                )
                SDKTelemetry.track("notification_device_registered", mapOf("provider" to "itwing"))
            }.onFailure {
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "notification_device_register"))
            }
            syncNow()
            flushPendingEvents()
        }
        if (started.compareAndSet(false, true)) {
            scheduleNext()
        }
    }

    fun onFirebaseToken(context: Context, token: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_PENDING_FCM_TOKEN, token) }
        SDKTelemetry.track("fcm_token_refreshed")
        registerDeviceToken(token, "fcm", repository)
    }

    fun registerFcmDevice(context: Context, repository: ConfigRepository) {
        this.repository = repository
        this.appContext = context.applicationContext
        createChannel(context.applicationContext)
        registerFirebaseToken(context.applicationContext)
    }

    fun showPushNotification(context: Context, data: Map<String, String>) {
        val id = data["itwing_notification_id"]
            ?: data["itwing_campaign_id"]
            ?: System.currentTimeMillis().toString()
        if (wasNotificationHandled(context.applicationContext, id)) {
            SDKTelemetry.track("notification_display_skipped", mapOf("notification_id" to id, "reason" to "already_handled"))
            return
        }
        val item = JSONObject()
            .put("id", id)
            .put("title", data["title"] ?: data["itwing_title"] ?: context.applicationInfo.loadLabel(context.packageManager).toString())
            .put("body", data["body"] ?: data["itwing_body"] ?: "")
            .put("image_url", data["image_url"] ?: data["itwing_image_url"] ?: "")
            .put("deep_link", data["deep_link"] ?: data["itwing_deep_link"] ?: "")
            .put("data", JSONObject(data))

        appContext = context.applicationContext
        createChannel(context.applicationContext)
        scope.launch {
            showNotification(context.applicationContext, item)
            repository?.let { repo ->
                runCatching { repo.reportNotificationEvent(id, "delivered") }
            } ?: storePendingEvent(context.applicationContext, KEY_PENDING_DELIVERED_IDS, id)
            SDKTelemetry.track("notification_delivered", mapOf("notification_id" to id, "transport" to "fcm"))
        }
    }

    fun syncNow() {
        val repo = repository ?: return
        val context = appContext ?: return
        val notifications = config
        if (!notifications.enabled) return

        scope.launch {
            runCatching {
                val response = repo.fetchPendingNotifications()
                val items = response.optJSONArray("notifications") ?: return@runCatching
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    if (wasNotificationHandled(context, id)) continue
                    if (!shownIds.add(id)) continue
                    showNotification(context, item)
                    runCatching { repo.reportNotificationEvent(id, "delivered") }
                    SDKTelemetry.track(
                        "notification_delivered",
                        mapOf(
                            "notification_id" to id,
                            "has_image" to item.optString("image_url").isNotBlank(),
                            "has_deeplink" to item.optString("deep_link").isNotBlank(),
                        ),
                    )
                }
            }.onFailure {
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "notification_sync"))
            }
        }
    }

    fun reportOpened(notificationId: String?) {
        if (notificationId.isNullOrBlank()) return
        appContext?.let {
            markNotificationOpened(it, notificationId)
            NotificationManagerCompat.from(it).cancel(notificationId.hashCode())
        }
        val repo = repository
        if (repo == null) {
            appContext?.let { storePendingEvent(it, KEY_PENDING_OPENED_IDS, notificationId) }
            return
        }
        scope.launch {
            runCatching { repo.reportNotificationEvent(notificationId, "opened") }
            SDKTelemetry.track("notification_opened", mapOf("notification_id" to notificationId))
        }
    }

    fun registerDeviceToken(token: String, provider: String = "itwing", repository: ConfigRepository?) {
        if (repository == null) {
            SDKTelemetry.track("notification_token_sync_skipped", mapOf("provider" to provider, "reason" to "missing_repository"))
            return
        }
        this.repository = repository
        scope.launch {
            runCatching {
                if (provider == "fcm") {
                    repository.registerNotificationDevice(
                        token = token,
                        provider = "fcm",
                        topics = config.topics,
                        segments = config.segments,
                        tags = config.tags,
                    )
                } else {
                    repository.registerItwingNotificationDevice()
                }
            }
                .onSuccess {
                    SDKTelemetry.track(
                        "notification_token_synced",
                        mapOf(
                            "provider" to provider,
                            "token_length" to token.length,
                        ),
                    )
                }
                .onFailure {
                    SDKTelemetry.track(
                        "notification_token_sync_failed",
                        mapOf(
                            "provider" to provider,
                            "message" to (it.message ?: it.javaClass.simpleName),
                        ),
                    )
                    SDKTelemetry.recordNonFatal(it, mapOf("operation" to "notification_token_sync"))
                }
        }
    }

    private fun registerFirebaseToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_PENDING_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }?.let {
            registerDeviceToken(it, "fcm", repository)
        }

        runCatching {
            SDKTelemetry.track("fcm_token_fetch_started")
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        SDKTelemetry.track("fcm_token_fetch_succeeded", mapOf("token_length" to token.length))
                        onFirebaseToken(context, token)
                    } else {
                        SDKTelemetry.track("fcm_token_fetch_failed", mapOf("message" to "empty_token"))
                    }
                }
                .addOnFailureListener {
                    SDKTelemetry.track("fcm_token_fetch_failed", mapOf("message" to (it.message ?: it.javaClass.simpleName)))
                    SDKTelemetry.recordNonFatal(it, mapOf("operation" to "fcm_token_fetch"))
                }
        }.onFailure {
            SDKTelemetry.track("fcm_token_fetch_failed", mapOf("message" to (it.message ?: it.javaClass.simpleName)))
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "fcm_token_fetch"))
        }
    }

    private fun flushPendingEvents() {
        val context = appContext ?: return
        val repo = repository ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val delivered = prefs.getStringSet(KEY_PENDING_DELIVERED_IDS, emptySet()).orEmpty()
        val opened = prefs.getStringSet(KEY_PENDING_OPENED_IDS, emptySet()).orEmpty()
        if (delivered.isEmpty() && opened.isEmpty()) return

        scope.launch {
            delivered.forEach { id -> runCatching { repo.reportNotificationEvent(id, "delivered") } }
            opened.forEach { id -> runCatching { repo.reportNotificationEvent(id, "opened") } }
            prefs.edit {
                remove(KEY_PENDING_DELIVERED_IDS)
                remove(KEY_PENDING_OPENED_IDS)
            }
        }
    }

    private fun storePendingEvent(context: Context, key: String, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        current.add(id)
        prefs.edit { putStringSet(key, current.toList().takeLast(200).toSet()) }
    }

    private fun scheduleNext() {
        val delayMs = config.pollIntervalSeconds.coerceAtLeast(60) * 1000L
        mainHandler.postDelayed({
            syncNow()
            scheduleNext()
        }, delayMs)
    }

    private suspend fun showNotification(context: Context, item: JSONObject) = withContext(Dispatchers.IO) {
        val id = item.optString("id")
        val title = item.optString("title", context.applicationInfo.loadLabel(context.packageManager).toString())
        val body = item.optString("body", "")
        val deepLink = item.optString("deep_link").takeIf { it.isNotBlank() }
        val payload = item.optJSONObject("data") ?: JSONObject()
        val imageUrl = payload.optString("big_picture_url").takeIf { it.isNotBlank() }
            ?: item.optString("image_url").takeIf { it.isNotBlank() }
        val largeIconUrl = payload.optString("large_icon_url").takeIf { it.isNotBlank() }
        val channelId = payload.optString("android_channel_id").takeIf { it.isNotBlank() } ?: CHANNEL_ID
        val accentColor = payload.optString("accent_color").takeIf { it.isNotBlank() }
        val priority = if (payload.optString("priority", "high") == "normal") {
            NotificationCompat.PRIORITY_DEFAULT
        } else {
            NotificationCompat.PRIORITY_HIGH
        }
        val groupKey = payload.optString("group_key").takeIf { it.isNotBlank() }

        val intent = deepLink
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()

        intent.setPackage(context.packageName)
        intent.putExtra("itwing_notification_id", id)
        intent.putExtra("itwing_deep_link", deepLink)
        payload.keys().forEach { key ->
            intent.putExtra(key, payload.optString(key))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        createChannel(context, channelId)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(pendingIntent)

        groupKey?.let { builder.setGroup(it) }
        accentColor?.let { color ->
            runCatching { Color.parseColor(color) }.getOrNull()?.let { builder.color = it }
        }

        largeIconUrl?.let { url ->
            loadBitmap(url)?.let { bitmap -> builder.setLargeIcon(bitmap) }
        }

        imageUrl?.let { url ->
            loadBitmap(url)?.let { bitmap ->
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(body))
            }
        }

        addAction(context, builder, id, payload, 1)
        addAction(context, builder, id, payload, 2)
        addAction(context, builder, id, payload, 3)

        if (canPostNotifications(context)) {
            NotificationManagerCompat.from(context).notify(id.hashCode(), builder.build())
            markNotificationShown(context, id)
        }
    }

    private fun wasNotificationHandled(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_OPENED_NOTIFICATION_IDS, emptySet()).orEmpty().contains(id) ||
            prefs.getStringSet(KEY_SHOWN_NOTIFICATION_IDS, emptySet()).orEmpty().contains(id)
    }

    private fun markNotificationShown(context: Context, id: String) {
        storeBoundedSet(context, KEY_SHOWN_NOTIFICATION_IDS, id)
    }

    private fun markNotificationOpened(context: Context, id: String) {
        storeBoundedSet(context, KEY_OPENED_NOTIFICATION_IDS, id)
        storeBoundedSet(context, KEY_SHOWN_NOTIFICATION_IDS, id)
    }

    private fun storeBoundedSet(context: Context, key: String, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        current.add(id)
        prefs.edit { putStringSet(key, current.toList().takeLast(500).toSet()) }
    }

    private fun createChannel(context: Context) {
        createChannel(context, CHANNEL_ID)
    }

    private fun createChannel(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            if (channelId == CHANNEL_ID) "ITWing Notifications" else channelId,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications sent from the ITWing admin panel."
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadBitmap(url: String) = runCatching {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun addAction(
        context: Context,
        builder: NotificationCompat.Builder,
        notificationId: String,
        payload: JSONObject,
        index: Int,
    ) {
        val label = payload.optString("action_${index}_label").takeIf { it.isNotBlank() } ?: return
        val actionId = payload.optString("action_${index}_id").takeIf { it.isNotBlank() } ?: "action_$index"
        val actionType = payload.optString("action_${index}_type", "open_url")
        val actionUrl = payload.optString("action_${index}_url").takeIf { it.isNotBlank() }
        val intent = when (actionType) {
            "open_url" -> actionUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            "share" -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, actionUrl ?: payload.optString("deep_link", ""))
            }
            else -> null
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        if (actionType != "open_url" && actionType != "share") {
            intent.setPackage(context.packageName)
        }
        intent.putExtra("itwing_notification_id", notificationId)
        intent.putExtra("itwing_notification_action", actionId)
        intent.putExtra("itwing_notification_action_type", actionType)
        intent.putExtra("itwing_notification_action_url", actionUrl)
        if (actionType == "save_image") {
            intent.putExtra("itwing_notification_save_image_url", payload.optString("big_picture_url").ifBlank { payload.optString("image_url") })
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            "$notificationId:$actionId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(context.applicationInfo.icon, label, pendingIntent)
    }
}
