package top.lovepikachu.XiaoHeiHook

import android.content.Context
import android.util.Log
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager
import kotlin.system.exitProcess

/**
 * Keeps the normal manager process minimal.
 *
 * When WebIDE and MCP are both disabled, closing the task from Recent Apps should not
 * leave the main application process cached in the background. When either service is
 * enabled, the foreground-service process must be allowed to keep running.
 */
object AppProcessExitHelper {
    private const val TAG = "XiaoHeiHook-AppExit"

    @Volatile
    private var killScheduled: Boolean = false

    fun scheduleKillIfWebIdeDisabled(context: Context, reason: String) {
        val appContext = context.applicationContext
        val webIdeEnabled = runCatching { WebIdeManager.loadConfig(appContext).enabled }
            .getOrDefault(false)
        val mcpEnabled = runCatching { McpManager.loadConfig(appContext).enabled }
            .getOrDefault(false)

        if (webIdeEnabled || mcpEnabled) {
            Log.i(TAG, "skip app process kill because background service is enabled, webIde=$webIdeEnabled, mcp=$mcpEnabled, reason=$reason")
            return
        }

        if (killScheduled) return
        killScheduled = true

        val pid = android.os.Process.myPid()
        val packageName = appContext.packageName
        Log.i(TAG, "schedule main process kill, reason=$reason, pid=$pid")

        Thread({
            runCatching { Thread.sleep(180) }
            Log.i(TAG, "kill main app process, reason=$reason, pid=$pid, package=$packageName")
            android.os.Process.killProcess(pid)
            exitProcess(0)
        }, "XHH-main-process-killer").apply {
            isDaemon = false
            start()
        }
    }
}
