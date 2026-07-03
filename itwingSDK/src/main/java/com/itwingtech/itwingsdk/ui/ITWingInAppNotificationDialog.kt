package com.itwingtech.itwingsdk.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.itwingtech.itwingsdk.R
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal object ITWingInAppNotificationDialog {
    private var currentDialog: AlertDialog? = null

    fun isShowing(): Boolean = currentDialog?.isShowing == true

    fun show(
        activity: Activity,
        item: JSONObject,
        primaryColor: Int,
        onHandled: (String) -> Unit,
    ): Boolean {
        if (!activity.isUsable() || isShowing()) return false

        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return false
        val title = item.optString("title", activity.applicationInfo.loadLabel(activity.packageManager).toString())
        val body = item.optString("body", "")
        val deepLink = item.optString("deep_link").takeIf { it.isNotBlank() }
        val payload = item.optJSONObject("data") ?: JSONObject()
        val imageUrl = payload.optString("big_picture_url").takeIf { it.isNotBlank() }
            ?: item.optString("image_url").takeIf { it.isNotBlank() }
        val accentColor = payload.optString("accent_color").takeIf { it.isNotBlank() }
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: primaryColor
        val onPrimary = if (ColorUtils.calculateLuminance(accentColor) > 0.58) Color.BLACK else Color.WHITE
        val handled = AtomicBoolean(false)

        fun markHandled(reason: String) {
            if (handled.compareAndSet(false, true)) {
                onHandled(id)
                SDKTelemetry.track(
                    "notification_in_app_handled",
                    mapOf("notification_id" to id, "reason" to reason),
                )
            }
        }

        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_itwing_in_app_notification, null, false)
        content.findViewById<TextView>(R.id.itwing_in_app_notification_title).text = title
        content.findViewById<TextView>(R.id.itwing_in_app_notification_body).apply {
            text = body
            visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        }
        content.findViewById<TextView>(R.id.itwing_in_app_notification_badge).apply {
            text = payload.optString("badge").takeIf { it.isNotBlank() } ?: activity.getString(R.string.itwing_in_app_notification_badge)
            setTextColor(accentColor)
        }

        val mediaFrame = content.findViewById<FrameLayout>(R.id.itwing_in_app_notification_media_frame)
        val image = content.findViewById<ImageView>(R.id.itwing_in_app_notification_image)
        if (imageUrl.isNullOrBlank()) {
            mediaFrame.visibility = View.GONE
        } else {
            mediaFrame.visibility = View.VISIBLE
            runCatching {
                Glide.with(image)
                    .load(imageUrl)
                    .centerCrop()
                    .into(image)
            }.onFailure {
                mediaFrame.visibility = View.GONE
                SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_notification_image_load"))
            }
        }

        val alert = AlertDialog.Builder(activity)
            .setView(content)
            .create()
        alert.setCancelable(false)
        alert.setCanceledOnTouchOutside(false)
        alert.setOnDismissListener {
            if (currentDialog === alert) currentDialog = null
        }

        content.findViewById<ImageButton>(R.id.itwing_in_app_notification_close).apply {
            imageTintList = ColorStateList.valueOf(accentColor)
            setOnClickListener {
                markHandled("close")
                alert.dismiss()
            }
        }

        val actions = parseActions(payload)
        val container = content.findViewById<LinearLayout>(R.id.itwing_in_app_notification_actions)
        val resolvedActions = if (actions.isNotEmpty()) {
            actions
        } else {
            listOf(
                NotificationAction(
                    id = "default",
                    label = if (deepLink.isNullOrBlank()) activity.getString(R.string.itwing_in_app_notification_ok) else activity.getString(R.string.itwing_in_app_notification_open),
                    type = if (deepLink.isNullOrBlank()) "dismiss" else "open_url",
                    url = deepLink,
                ),
            )
        }
        resolvedActions.take(3).forEachIndexed { index, action ->
            container.addView(
                MaterialButton(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        activity.dp(46),
                    ).apply {
                        if (index > 0) topMargin = activity.dp(8)
                    }
                    text = action.label
                    isAllCaps = false
                    cornerRadius = activity.dp(14)
                    textSize = 14f
                    setTextColor(if (index == 0) onPrimary else accentColor)
                    backgroundTintList = ColorStateList.valueOf(if (index == 0) accentColor else Color.WHITE)
                    strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 120))
                    strokeWidth = if (index == 0) 0 else activity.dp(1)
                    rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 36))
                    setOnClickListener {
                        markHandled("action_${action.id}")
                        runAction(activity, id, action, deepLink)
                        alert.dismiss()
                    }
                },
            )
        }

        return runCatching {
            currentDialog?.dismiss()
            currentDialog = alert
            alert.show()
            alert.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            alert.window?.setLayout(activity.dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
            SDKTelemetry.track("notification_in_app_shown", mapOf("notification_id" to id))
            true
        }.getOrElse {
            currentDialog = null
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_notification_show", "notification_id" to id))
            false
        }
    }

    private fun parseActions(payload: JSONObject): List<NotificationAction> {
        val fromJson = payload.optString("actions_json")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val label = item.optString("label").takeIf { it.isNotBlank() } ?: continue
                        add(
                            NotificationAction(
                                id = item.optString("id").takeIf { it.isNotBlank() } ?: "action_${index + 1}",
                                label = label,
                                type = item.optString("type", "open_url"),
                                url = item.optString("url").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
            .orEmpty()
        if (fromJson.isNotEmpty()) return fromJson

        return (1..3).mapNotNull { index ->
            val label = payload.optString("action_${index}_label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NotificationAction(
                id = payload.optString("action_${index}_id").takeIf { it.isNotBlank() } ?: "action_$index",
                label = label,
                type = payload.optString("action_${index}_type", "open_url"),
                url = payload.optString("action_${index}_url").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun runAction(activity: Activity, notificationId: String, action: NotificationAction, fallbackUrl: String?) {
        if (!activity.isUsable()) return
        val intent = when (action.type) {
            "open_url" -> (action.url ?: fallbackUrl)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            "share" -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, action.url ?: fallbackUrl.orEmpty())
            }
            "open_app", "custom" -> activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            else -> null
        } ?: return

        runCatching {
            if (action.type != "open_url" && action.type != "share") {
                intent.setPackage(activity.packageName)
            }
            intent.putExtra("itwing_notification_id", notificationId)
            intent.putExtra("itwing_notification_action", action.id)
            intent.putExtra("itwing_notification_action_type", action.type)
            intent.putExtra("itwing_notification_action_url", action.url)
            activity.startActivity(intent)
        }.onFailure {
            SDKTelemetry.recordNonFatal(it, mapOf("operation" to "in_app_notification_action", "action" to action.id))
        }
    }

    private data class NotificationAction(
        val id: String,
        val label: String,
        val type: String,
        val url: String?,
    )

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun Activity.dialogWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val maxWidth = (430 * density).toInt()
        val margin = (28 * density).toInt()
        return minOf(maxWidth, screenWidth - margin).coerceAtLeast((300 * density).toInt())
    }

    private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
