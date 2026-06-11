package top.lovepikachu.XiaoHeiHook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import top.lovepikachu.XiaoHeiHook.keepalive.AccessibilityKeepAliveManager
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager
import kotlin.system.exitProcess

/**
 * Controls the normal manager process lifecycle.
 *
 * WebIDE/MCP runtime services are independent foreground services.  When neither
 * service is enabled and the user closes/removes the manager UI task, keeping the
 * normal process around only wastes memory and can confuse users into thinking a
 * background service is still active.  In that case we schedule a delayed, guarded
 * self-exit.  The delayed re-check avoids killing the process during WebIDE/MCP
 * switch transitions or Activity recreate paths.
 */
object AppProcessExitHelper {
    private const val TAG = "XiaoHeiHook-AppExit"
    private const val EXIT_DELAY_MS = 1200L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var generation = 0

    fun cancelPendingExit(reason: String) {
        generation++
        Log.d(TAG, "cancel pending manager process exit, reason=$reason, generation=$generation")
    }

    fun scheduleExitIfNoRuntimeService(context: Context, reason: String) {
        val appContext = context.applicationContext
        val token = ++generation
        Log.i(TAG, "schedule guarded manager process exit, reason=$reason, generation=$token")
        mainHandler.postDelayed({
            if (token != generation) {
                Log.d(TAG, "skip manager process exit: superseded token=$token current=$generation")
                return@postDelayed
            }
            val decision = shouldExit(appContext)
            Log.i(TAG, "guarded manager process exit check: $decision, reason=$reason")
            if (decision.shouldExit) {
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        }, EXIT_DELAY_MS)
    }

    /** Legacy name kept for old call sites. */
    fun scheduleKillIfWebIdeDisabled(context: Context, reason: String) {
        scheduleExitIfNoRuntimeService(context, reason)
    }

    private fun shouldExit(context: Context): ExitDecision {
        val webIdeResult = runCatching { WebIdeManager.loadConfig(context).enabled }
            .onFailure { Log.w(TAG, "read WebIDE config failed", it) }
        val mcpResult = runCatching { McpManager.loadConfig(context).enabled }
            .onFailure { Log.w(TAG, "read MCP config failed", it) }
        val webIdeEnabled = webIdeResult.getOrDefault(false)
        val mcpEnabled = mcpResult.getOrDefault(false)
        val configReadOk = webIdeResult.isSuccess && mcpResult.isSuccess
        val accessibilityRequested = runCatching { AccessibilityKeepAliveManager.isRequested(context) }
            .getOrDefault(false)
        val accessibilityEnabled = runCatching { AccessibilityKeepAliveManager.isServiceEnabled(context) }
            .getOrDefault(false)

        if (configReadOk && !webIdeEnabled && !mcpEnabled) {
            runCatching { AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(context) }
                .onFailure { Log.w(TAG, "disable accessibility keep-alive before manager exit failed", it) }
        }

        val shouldExit = configReadOk && !webIdeEnabled && !mcpEnabled
        return ExitDecision(
            shouldExit = shouldExit,
            webIdeEnabled = webIdeEnabled,
            mcpEnabled = mcpEnabled,
            accessibilityRequested = accessibilityRequested,
            accessibilityEnabled = accessibilityEnabled
        )
    }

    private data class ExitDecision(
        val shouldExit: Boolean,
        val webIdeEnabled: Boolean,
        val mcpEnabled: Boolean,
        val accessibilityRequested: Boolean,
        val accessibilityEnabled: Boolean
    ) {
        override fun toString(): String {
            return "shouldExit=$shouldExit webIde=$webIdeEnabled mcp=$mcpEnabled a11yRequested=$accessibilityRequested a11yEnabled=$accessibilityEnabled"
        }
    }
}
