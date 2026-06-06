package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.ScriptPrefs
import top.lovepikachu.XiaoHeiHook.debug.DebugProtocol

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

    fun setAppEnabled(packageName: String, enabled: Boolean): JSONObject {
        val b = callChecked(
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
    }

    fun debugEnabled(packageName: String): Boolean = getBoolean(DebugProtocol.debugEnabledKey(packageName), false)

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
