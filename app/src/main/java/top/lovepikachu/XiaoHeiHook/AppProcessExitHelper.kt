package top.lovepikachu.XiaoHeiHook

import android.content.Context
import android.util.Log

/**
 * Legacy helper kept for binary/source compatibility.
 *
 * Older builds killed the normal manager process when WebIDE/MCP were disabled.
 * That made WebIDE shutdown fragile on some ROMs: after the WebIDE switch saved
 * enabled=false, any MainActivity destroy/recreate path could kill the UI process
 * and look like an app crash.  The manager process is now left to Android's normal
 * lifecycle instead of being terminated manually.
 */
object AppProcessExitHelper {
    private const val TAG = "XiaoHeiHook-AppExit"

    fun scheduleKillIfWebIdeDisabled(context: Context, reason: String) {
        Log.i(TAG, "skip manual app process kill, reason=$reason, package=${context.applicationContext.packageName}")
    }
}
