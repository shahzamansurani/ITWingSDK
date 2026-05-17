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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val shownIds = ConcurrentHashMap.newKeySet<String>()
    private var repository: ConfigRepository? = null
    private var appContext: Context? = null
    private var config: NotificationConfig = NotificationConfig()

    fun configure(activity: Activity, sdkConfig: ITWingConfig, repository: ConfigRepository? = null) {
        val notifications = sdkConfig.notifications
        if (!notifications.enabled || repository == null) return

        this.repository = repository
        this.appContext = activity.applicationContext
        this.config = notifications

        createChannel(activity.applicationContext)
        if (notifications.promptForPermission) requestPermissionIfNeeded(activity)

        scope.launch {
            runCatching {
                repository.registerItwingNotificationDevice(
                    topics = notifications.topics,
                    segments = notifications.segments,
                    tags = notifications.tags,
                )
            }
            syncNow()
        }

        if (started.compareAndSet(false, true)) {
            scheduleNext()
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
                    if (!shownIds.add(id)) continue
                    showNotification(context, item)
                    runCatching { repo.reportNotificationEvent(id, "delivered") }
                }
            }
        }
    }

    fun reportOpened(notificationId: String?) {
        if (notificationId.isNullOrBlank()) return
        val repo = repository ?: return
        scope.launch {
            runCatching { repo.reportNotificationEvent(notificationId, "opened") }
        }
    }

    fun registerDeviceToken(token: String, provider: String = "itwing", repository: ConfigRepository?) {
        if (provider != "itwing" || repository == null) return
        this.repository = repository
        scope.launch {
            runCatching { repository.registerItwingNotificationDevice() }
        }
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
        val imageUrl = item.optString("image_url").takeIf { it.isNotBlank() }

        val intent = deepLink
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()

        intent.setPackage(context.packageName)
        intent.putExtra("itwing_notification_id", id)
        intent.putExtra("itwing_deep_link", deepLink)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        imageUrl?.let { url ->
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { bitmap ->
                builder.setLargeIcon(bitmap)
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(body))
            }
        }

        if (canPostNotifications(context)) {
            NotificationManagerCompat.from(context).notify(id.hashCode(), builder.build())
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ITWing Notifications",
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
}
