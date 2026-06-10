package top.lovepikachu.XiaoHeiHook.keepalive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Minimal, user-enabled accessibility service used only as an optional keep-alive signal.
 *
 * It does not retrieve window content, does not perform gestures, and ignores all events.
 * The service can disable itself when XiaoHeiHook no longer needs keep-alive assistance.
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = WeakReference(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 1000L
            flags = AccessibilityServiceInfo.DEFAULT
        }
        Log.i(TAG, "accessibility keep-alive service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally ignored. This service is only a user-enabled keep-alive anchor.
    }

    override fun onInterrupt() {
        Log.i(TAG, "accessibility keep-alive service interrupted")
    }

    override fun onDestroy() {
        val service = current?.get()
        if (service === this) current = null
        Log.i(TAG, "accessibility keep-alive service destroyed")
        super.onDestroy()
    }

    fun requestDisableSelf(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                disableSelf()
                true
            }.getOrElse {
                Log.w(TAG, "disable accessibility service via disableSelf failed", it)
                false
            }
        } else {
            false
        }
    }

    companion object {
        private const val TAG = "XiaoHeiHook-A11yKeepAlive"
        private var current: WeakReference<KeepAliveAccessibilityService>? = null

        fun disableCurrentService(): Boolean {
            return current?.get()?.requestDisableSelf() == true
        }
    }
}
