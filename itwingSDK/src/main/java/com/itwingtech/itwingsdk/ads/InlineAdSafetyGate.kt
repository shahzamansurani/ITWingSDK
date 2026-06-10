package com.itwingtech.itwingsdk.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.itwingtech.itwingsdk.analytics.SDKTelemetry
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

internal object InlineAdSafetyGate {
    private const val PENDING_WINDOW_MS = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var pending = false
    @Volatile private var armedAtMs = 0L
    @Volatile private var sourceFormat = ""
    @Volatile private var sourcePlacement = ""
    @Volatile private var suppressedActivityRef: WeakReference<Activity>? = null
    @Volatile private var windowCallbackRef: WeakReference<SafetyWindowCallback>? = null

    fun arm(sourceFormat: String, placement: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            armOnMain(sourceFormat, placement)
        } else {
            mainHandler.post {
                armOnMain(sourceFormat, placement)
            }
        }
    }

    private fun armOnMain(sourceFormat: String, placement: String) {
        pending = true
        armedAtMs = System.currentTimeMillis()
        this.sourceFormat = sourceFormat
        sourcePlacement = placement
        suppressedActivityRef = null
        reloadCallbacks.clear()
        SDKTelemetry.track(
            "inline_ad_safety_armed",
            mapOf(
                "source_format" to sourceFormat,
                "source_placement" to placement,
            ),
        )
    }

    fun suppressInlineAd(
        activity: Activity,
        inlineFormat: String,
        placement: String,
        reloadAfterInteraction: () -> Unit,
    ): Boolean {
        if (!shouldSuppress(activity)) return false

        reloadCallbacks.add(reloadAfterInteraction)
        installInteractionWatcher(activity)
        SDKTelemetry.track(
            "inline_ad_safety_suppressed",
            mapOf(
                "inline_format" to inlineFormat,
                "inline_placement" to placement,
                "source_format" to sourceFormat,
                "source_placement" to sourcePlacement,
            ),
        )
        return true
    }

    private fun shouldSuppress(activity: Activity): Boolean {
        val now = System.currentTimeMillis()
        if (!pending && suppressedActivityRef?.get() == null) return false
        if (now - armedAtMs > PENDING_WINDOW_MS) {
            clear(activity, "expired", reload = false)
            return false
        }

        val suppressedActivity = suppressedActivityRef?.get()
        if (suppressedActivity != null) {
            return suppressedActivity === activity
        }

        if (!pending) return false
        pending = false
        suppressedActivityRef = WeakReference(activity)
        return true
    }

    private fun installInteractionWatcher(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is SafetyWindowCallback) return

        val wrapper = SafetyWindowCallback(
            activity = activity,
            original = current,
            onInteraction = {
                clear(activity, "user_interaction", reload = true)
            },
        )
        window.callback = wrapper
        windowCallbackRef = WeakReference(wrapper)
    }

    private fun clear(activity: Activity?, reason: String, reload: Boolean) {
        mainHandler.post {
            val targetActivity = activity ?: suppressedActivityRef?.get()
            val wrapper = windowCallbackRef?.get()
            if (targetActivity != null && wrapper != null && wrapper.activityRef.get() === targetActivity) {
                runCatching {
                    if (targetActivity.window?.callback === wrapper) {
                        targetActivity.window.callback = wrapper.original
                    }
                }
            }

            val callbacks = if (reload) reloadCallbacks.toList() else emptyList()
            pending = false
            suppressedActivityRef = null
            windowCallbackRef = null
            reloadCallbacks.clear()

            SDKTelemetry.track(
                "inline_ad_safety_cleared",
                mapOf(
                    "reason" to reason,
                    "source_format" to sourceFormat,
                    "source_placement" to sourcePlacement,
                    "reload_count" to callbacks.size,
                ),
            )

            callbacks.forEach { callback ->
                runCatching { callback() }
            }
        }
    }

    private class SafetyWindowCallback(
        activity: Activity,
        val original: Window.Callback,
        private val onInteraction: () -> Unit,
    ) : Window.Callback {
        val activityRef = WeakReference(activity)
        @Volatile private var cleared = false

        private fun notifyInteraction() {
            if (!cleared) {
                cleared = true
                onInteraction()
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) notifyInteraction()
            return original.dispatchKeyEvent(event)
        }

        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean =
            original.dispatchKeyShortcutEvent(event)

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) notifyInteraction()
            return original.dispatchTouchEvent(event)
        }

        override fun dispatchTrackballEvent(event: MotionEvent): Boolean =
            original.dispatchTrackballEvent(event)

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            original.dispatchGenericMotionEvent(event)

        override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
            original.dispatchPopulateAccessibilityEvent(event)

        override fun onCreatePanelView(featureId: Int): View? =
            original.onCreatePanelView(featureId)

        override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean =
            original.onCreatePanelMenu(featureId, menu)

        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
            original.onPreparePanel(featureId, view, menu)

        override fun onMenuOpened(featureId: Int, menu: Menu): Boolean =
            original.onMenuOpened(featureId, menu)

        override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean =
            original.onMenuItemSelected(featureId, item)

        override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) =
            original.onWindowAttributesChanged(attrs)

        override fun onContentChanged() =
            original.onContentChanged()

        override fun onWindowFocusChanged(hasFocus: Boolean) =
            original.onWindowFocusChanged(hasFocus)

        override fun onAttachedToWindow() =
            original.onAttachedToWindow()

        override fun onDetachedFromWindow() {
            notifyInteraction()
            original.onDetachedFromWindow()
        }

        override fun onPanelClosed(featureId: Int, menu: Menu) =
            original.onPanelClosed(featureId, menu)

        override fun onSearchRequested(): Boolean =
            original.onSearchRequested()

        override fun onSearchRequested(searchEvent: SearchEvent): Boolean =
            original.onSearchRequested(searchEvent)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? =
            original.onWindowStartingActionMode(callback)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
            original.onWindowStartingActionMode(callback, type)

        override fun onActionModeStarted(mode: ActionMode) =
            original.onActionModeStarted(mode)

        override fun onActionModeFinished(mode: ActionMode) =
            original.onActionModeFinished(mode)

        override fun onProvideKeyboardShortcuts(
            data: MutableList<KeyboardShortcutGroup>,
            menu: Menu?,
            deviceId: Int,
        ) = original.onProvideKeyboardShortcuts(data, menu, deviceId)

        override fun onPointerCaptureChanged(hasCapture: Boolean) =
            original.onPointerCaptureChanged(hasCapture)
    }
}
