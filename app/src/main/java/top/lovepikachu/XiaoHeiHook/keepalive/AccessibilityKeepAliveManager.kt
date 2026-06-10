package top.lovepikachu.XiaoHeiHook.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager
import java.io.BufferedReader
import java.io.InputStreamReader

object AccessibilityKeepAliveManager {
    private const val TAG = "XiaoHeiHook-A11yKeepAlive"
    private const val PREFS = "xiaohei_accessibility_keep_alive"
    private const val KEY_REQUESTED = "requested"

    fun isRequested(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REQUESTED, false)
    }

    fun setRequested(context: Context, requested: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUESTED, requested).apply()
    }

    fun componentName(context: Context): ComponentName {
        return ComponentName(context.packageName, KeepAliveAccessibilityService::class.java.name)
    }

    fun componentId(context: Context): String = componentName(context).flattenToString()

    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        val target = componentId(context)
        for (item in splitter) {
            if (item.equals(target, ignoreCase = true)) return true
        }
        return false
    }

    fun isRootAvailable(): Boolean {
        return runRootCommand("id", timeoutMs = 2500).isSuccess
    }

    fun openAccessibilitySettings(context: Context): Result<Unit> {
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun enableWithRoot(context: Context): Result<Unit> {
        val target = componentId(context)
        val current = readEnabledServices(context)
        val services = current
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (services.none { it.equals(target, ignoreCase = true) }) {
            services += target
        }
        val joined = services.joinToString(":")
        val command = buildString {
            append("settings put secure enabled_accessibility_services ")
            append(shellQuote(joined))
            append("; settings put secure accessibility_enabled 1")
        }
        return runRootCommand(command).map {
            setRequested(context, true)
        }
    }

    fun disable(context: Context): Result<Unit> {
        setRequested(context, false)
        if (KeepAliveAccessibilityService.disableCurrentService()) return Result.success(Unit)
        if (!isServiceEnabled(context)) return Result.success(Unit)
        return disableWithRoot(context)
    }

    fun disableWithRoot(context: Context): Result<Unit> {
        val target = componentId(context)
        val services = readEnabledServices(context)
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(target, ignoreCase = true) }
        val joined = services.joinToString(":")
        val command = if (joined.isBlank()) {
            "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0"
        } else {
            "settings put secure enabled_accessibility_services ${shellQuote(joined)}; settings put secure accessibility_enabled 1"
        }
        return runRootCommand(command).map { Unit }
    }

    fun disableIfNoRuntimeNeedsKeepAlive(context: Context) {
        if (!isRequested(context)) return
        val webIdeEnabled = runCatching { WebIdeManager.loadConfig(context).enabled }.getOrDefault(false)
        val mcpEnabled = runCatching { McpManager.loadConfig(context).enabled }.getOrDefault(false)
        if (webIdeEnabled || mcpEnabled) return
        disable(context).onFailure {
            Log.w(TAG, "disable accessibility keep-alive after runtime stopped failed", it)
        }
    }

    private fun readEnabledServices(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun runRootCommand(command: String, timeoutMs: Long = 5000): Result<String> {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("root command timeout")
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException(stderr.ifBlank { "root command failed: $exitCode" })
            }
            stdout
        }
    }
}
