package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.ScriptPrefs
import top.lovepikachu.XiaoHeiHook.debug.DebugProtocol
import top.lovepikachu.XiaoHeiHook.keepalive.MainProcessKeepAliveService

/** Client used by WebIDE(:webide) to execute Remote Preferences / LSPosed operations in the main process. */
class WebIdeBridgeClient(private val context: Context) {
    private val uri: Uri = Uri.parse("content://${WebIdeBridgeProvider.AUTHORITY}")

    data class BridgeStatus(
        val ok: Boolean,
        val xposedServiceReady: Boolean,
        val remotePreferencesReady: Boolean,
        val process: String,
        val error: String? = null
    )

    fun status(): BridgeStatus {
        val b = call(WebIdeBridgeProvider.METHOD_STATUS)
        return BridgeStatus(
            ok = b.getBoolean("ok", false),
            xposedServiceReady = b.getBoolean("xposedServiceReady", false),
            remotePreferencesReady = b.getBoolean("remotePreferencesReady", false),
            process = b.getString("process").orEmpty(),
            error = b.getString("error")
        )
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        // Fast path for same-process/debug use.
        XiaoHeiApplication.remotePreferences?.let { return it.getBoolean(key, defaultValue) }
        val b = call(
            WebIdeBridgeProvider.METHOD_GET_BOOLEAN,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_KEY, key)
                putBoolean(WebIdeBridgeProvider.ARG_DEFAULT, defaultValue)
            }
        )
        return b.getBoolean("value", defaultValue)
    }


    fun getString(key: String, defaultValue: String = ""): String {
        XiaoHeiApplication.remotePreferences?.let { return it.getString(key, defaultValue) ?: defaultValue }
        val b = call(
            WebIdeBridgeProvider.METHOD_GET_STRING,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_KEY, key)
                putString(WebIdeBridgeProvider.ARG_DEFAULT_STRING, defaultValue)
            }
        )
        return b.getString("value") ?: defaultValue
    }

    fun putString(key: String, value: String): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_PUT_STRING,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_KEY, key)
                putString(WebIdeBridgeProvider.ARG_VALUE, value)
            }
        )
        return JSONObject().put("ok", true).put("value", b.getString("value") ?: value)
    }

    fun putBoolean(key: String, value: Boolean): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_PUT_BOOLEAN,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_KEY, key)
                putBoolean(WebIdeBridgeProvider.ARG_VALUE, value)
            }
        )
        return JSONObject().put("ok", true).put("value", b.getBoolean("value", value))
    }

    fun remove(key: String): JSONObject {
        callChecked(
            WebIdeBridgeProvider.METHOD_REMOVE,
            Bundle().apply { putString(WebIdeBridgeProvider.ARG_KEY, key) }
        )
        return JSONObject().put("ok", true).put("key", key)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean): JSONObject {        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SET_APP_ENABLED,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName)
                putBoolean(WebIdeBridgeProvider.ARG_ENABLED, enabled)
            }
        )
        return JSONObject()
            .put("ok", true)
            .put("packageName", b.getString("packageName") ?: packageName)
            .put("enabled", b.getBoolean("enabled", enabled))
            .put("approved", b.getString("approved") ?: "[]")
    }

    fun setScriptEnabled(packageName: String, scriptId: String, enabled: Boolean): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SET_SCRIPT_ENABLED,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName)
                putString(WebIdeBridgeProvider.ARG_SCRIPT_ID, scriptId)
                putBoolean(WebIdeBridgeProvider.ARG_ENABLED, enabled)
            }
        )
        return JSONObject()
            .put("ok", true)
            .put("packageName", b.getString("packageName") ?: packageName)
            .put("scriptId", b.getString("scriptId") ?: scriptId)
            .put("enabled", b.getBoolean("enabled", enabled))
    }


    fun setDebugEnabled(packageName: String, enabled: Boolean): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SET_DEBUG_ENABLED,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName)
                putBoolean(WebIdeBridgeProvider.ARG_ENABLED, enabled)
            }
        )
        return JSONObject()
            .put("ok", true)
            .put("packageName", b.getString("packageName") ?: packageName)
            .put("enabled", b.getBoolean("enabled", enabled))
            .put("sessionId", b.getString("sessionId") ?: "")
            .put("expiresAt", b.getLong("expiresAt", 0L))
    }

    fun clearAllDebugState(): JSONObject {
        val b = callChecked(WebIdeBridgeProvider.METHOD_CLEAR_DEBUG_STATE)
        return JSONObject()
            .put("ok", true)
            .put("removed", b.getInt("removed", 0))
    }

    fun debugEnabled(packageName: String): Boolean {
        val enabled = getBoolean(DebugProtocol.debugEnabledKey(packageName), false)
        if (!enabled) return false
        val b = call(
            WebIdeBridgeProvider.METHOD_GET_LONG,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_KEY, DebugProtocol.debugExpiresAtKey(packageName))
                putLong(WebIdeBridgeProvider.ARG_DEFAULT_LONG, 0L)
            }
        )
        val expiresAt = b.getLong("value", 0L)
        return expiresAt > System.currentTimeMillis()
    }

    fun getDebugBreakpoints(packageName: String): JSONObject {
        val b = call(
            WebIdeBridgeProvider.METHOD_GET_DEBUG_BREAKPOINTS,
            Bundle().apply { putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName) }
        )
        val raw = b.getString("json") ?: "{}"
        return JSONObject(raw)
    }

    fun setDebugBreakpoints(packageName: String, scriptPath: String, lines: org.json.JSONArray): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SET_DEBUG_BREAKPOINTS,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName)
                putString(WebIdeBridgeProvider.ARG_SCRIPT_PATH, scriptPath)
                putString(WebIdeBridgeProvider.ARG_LINES_JSON, lines.toString())
            }
        )
        return JSONObject(b.getString("json") ?: "{\"ok\":true}")
    }


    fun sendDebugCommand(packageName: String, processName: String, pauseId: String, command: String, expression: String = "", payload: JSONObject = JSONObject()): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SEND_DEBUG_COMMAND,
            Bundle().apply {
                putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName)
                putString(WebIdeBridgeProvider.ARG_PROCESS, processName)
                putString(WebIdeBridgeProvider.ARG_PAUSE_ID, pauseId)
                putString(WebIdeBridgeProvider.ARG_COMMAND, command)
                putString(WebIdeBridgeProvider.ARG_EXPRESSION, expression)
                putString(WebIdeBridgeProvider.ARG_PAYLOAD_JSON, payload.toString())
            }
        )
        return JSONObject()
            .put("ok", true)
            .put("packageName", b.getString("packageName") ?: packageName)
            .put("processName", b.getString("processName") ?: processName)
            .put("pauseId", b.getString("pauseId") ?: pauseId)
            .put("command", b.getString("command") ?: command)
    }

    fun syncScripts(packageName: String?): JSONObject {
        val b = callChecked(
            WebIdeBridgeProvider.METHOD_SYNC_SCRIPTS,
            Bundle().apply { putString(WebIdeBridgeProvider.ARG_PACKAGE, packageName.orEmpty()) }
        )
        return JSONObject(b.getString("json") ?: "{\"ok\":true,\"count\":0,\"scripts\":[]}")
    }

    fun appEnabled(packageName: String): Boolean = getBoolean(ScriptPrefs.appEnabledKey(packageName), false)
    fun scriptEnabled(packageName: String, scriptId: String): Boolean = getBoolean(ScriptPrefs.scriptEnabledKey(packageName, scriptId), false)

    private fun call(method: String, extras: Bundle? = null): Bundle {
        MainProcessKeepAliveService.startIfNeeded(context, MainProcessKeepAliveService.REASON_BRIDGE)
        return context.applicationContext.contentResolver.call(uri, method, null, extras) ?: Bundle().apply {
            putBoolean("ok", false)
            putString("error", "主进程桥接未返回结果")
        }
    }

    private fun callChecked(method: String, extras: Bundle? = null): Bundle {
        val b = call(method, extras)
        if (!b.getBoolean("ok", false)) {
            throw IllegalStateException(b.getString("error") ?: "主进程桥接调用失败：$method")
        }
        return b
    }
}
