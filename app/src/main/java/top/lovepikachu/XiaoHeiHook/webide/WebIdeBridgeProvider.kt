package top.lovepikachu.XiaoHeiHook.webide

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.ScriptPrefs
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.debug.DebugProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * Runs in the main app process and bridges WebIDE(:webide) requests to LSPosed
 * Remote Preferences / XposedService.  The WebIDE HTTP server lives in an
 * isolated foreground-service process to avoid UI background freezes, but the
 * LSPosed service connection is owned by the normal app process.
 */
class WebIdeBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            when (method) {
                METHOD_STATUS -> status()
                METHOD_GET_BOOLEAN -> getBoolean(extras)
                METHOD_GET_LONG -> getLong(extras)
                METHOD_GET_STRING -> getString(extras)
                METHOD_PUT_STRING -> putString(extras)
                METHOD_REMOVE -> remove(extras)
                METHOD_PUT_BOOLEAN -> putBoolean(extras)
                METHOD_SET_APP_ENABLED -> setAppEnabled(extras)
                METHOD_SET_SCRIPT_ENABLED -> setScriptEnabled(extras)
                METHOD_SYNC_SCRIPTS -> syncScripts(extras)
                METHOD_SET_DEBUG_ENABLED -> setDebugEnabled(extras)
                METHOD_CLEAR_DEBUG_STATE -> clearDebugState()
                METHOD_SEND_DEBUG_COMMAND -> sendDebugCommand(extras)
                METHOD_GET_DEBUG_BREAKPOINTS -> getDebugBreakpoints(extras)
                METHOD_SET_DEBUG_BREAKPOINTS -> setDebugBreakpoints(extras)
                else -> errorBundle("Unsupported bridge method: $method")
            }
        } catch (e: Throwable) {
            errorBundle(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun status(): Bundle {
        awaitRemotePreferences(800)
        return okBundle()
            .putBool("xposedServiceReady", XiaoHeiApplication.xposedService != null)
            .putBool("remotePreferencesReady", XiaoHeiApplication.remotePreferences != null)
            .putStringValue("process", context?.let { ProcessUtil.currentProcessName(it) }.orEmpty())
    }

    private fun getBoolean(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        val defValue = extras?.getBoolean(ARG_DEFAULT, false) ?: false
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(1200)
        val value = prefs?.getBoolean(key, defValue) ?: defValue
        return okBundle()
            .putBool("value", value)
            .putBool("remotePreferencesReady", prefs != null)
    }

    private fun getLong(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        val defValue = extras?.getLong(ARG_DEFAULT_LONG, 0L) ?: 0L
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(1200)
        val value = prefs?.getLong(key, defValue) ?: defValue
        return okBundle()
            .putLongValue("value", value)
            .putBool("remotePreferencesReady", prefs != null)
    }

    private fun getString(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        val defValue = extras?.getString(ARG_DEFAULT_STRING).orEmpty()
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(1200)
        val value = prefs?.getString(key, defValue) ?: defValue
        return okBundle()
            .putStringValue("value", value)
            .putBool("remotePreferencesReady", prefs != null)
    }

    private fun putString(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        val value = extras?.getString(ARG_VALUE).orEmpty()
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        prefs.edit().putString(key, value).commit()
        return okBundle().putStringValue("value", value)
    }

    private fun remove(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        prefs.edit().remove(key).commit()
        return okBundle().putStringValue("key", key)
    }

    private fun putBoolean(extras: Bundle?): Bundle {
        val key = extras?.getString(ARG_KEY).orEmpty()
        val value = extras?.getBoolean(ARG_VALUE, false) ?: false
        if (key.isBlank()) return errorBundle("key 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        prefs.edit().putBoolean(key, value).commit()
        return okBundle().putBool("value", value)
    }

    private fun setScriptEnabled(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        val scriptId = extras?.getString(ARG_SCRIPT_ID).orEmpty().trim()
        val enabled = extras?.getBoolean(ARG_ENABLED, false) ?: false
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")
        if (scriptId.isBlank()) return errorBundle("scriptId 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        prefs.edit().putBoolean(ScriptPrefs.scriptEnabledKey(packageName, scriptId), enabled).commit()
        return okBundle()
            .putStringValue("packageName", packageName)
            .putStringValue("scriptId", scriptId)
            .putBool("enabled", enabled)
    }

    private fun setAppEnabled(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        val enabled = extras?.getBoolean(ARG_ENABLED, false) ?: false
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")

        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val service = awaitXposedService(2000)

        if (!enabled) {
            runCatching { service?.removeScope(listOf(packageName)) }
            prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), false).commit()
            return okBundle()
                .putStringValue("packageName", packageName)
                .putBool("enabled", false)
        }

        if (service == null) return errorBundle("LSPosed 服务未连接")

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<String?>(null)
        val approvedRef = AtomicReference<List<String>>(emptyList())
        service.requestScope(
            listOf(packageName),
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: MutableList<String>) {
                    approvedRef.set(approved.toList())
                    latch.countDown()
                }

                override fun onScopeRequestFailed(message: String) {
                    errorRef.set(message.ifBlank { "Scope 请求失败" })
                    latch.countDown()
                }
            }
        )

        val completed = latch.await(30, TimeUnit.SECONDS)
        val error = if (completed) errorRef.get() else "Scope 请求超时，请检查 LSPosed 授权弹窗"
        if (error != null) {
            prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), false).commit()
            return errorBundle(error)
        }

        prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), true).commit()
        return okBundle()
            .putStringValue("packageName", packageName)
            .putBool("enabled", true)
            .putStringValue("approved", JSONArray(approvedRef.get()).toString())
    }

    private fun syncScripts(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE)?.trim()?.ifBlank { null }
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val service = awaitXposedService(2000) ?: return errorBundle("LSPosed 服务未连接")
        val result = ScriptRepository.syncPublicScriptsToRemote(
            service = service,
            prefs = prefs,
            debugPackageName = packageName
        )
        return result.fold(
            onSuccess = { scripts ->
                val obj = JSONObject()
                    .put("ok", true)
                    .put("packageName", packageName ?: JSONObject.NULL)
                    .put("count", scripts.size)
                    .put("scripts", JSONArray(scripts.map { it.id }))
                okBundle().putStringValue("json", obj.toString())
            },
            onFailure = { error -> errorBundle(error.message ?: error.javaClass.simpleName) }
        )
    }


    private fun setDebugEnabled(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        val enabled = extras?.getBoolean(ARG_ENABLED, false) ?: false
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val editor = prefs.edit()
        val sessionId: String
        val expiresAt: Long
        if (enabled) {
            sessionId = UUID.randomUUID().toString()
            expiresAt = System.currentTimeMillis() + DebugProtocol.DEFAULT_DEBUG_SESSION_MILLIS
            editor.putBoolean(DebugProtocol.debugEnabledKey(packageName), true)
                .putString(DebugProtocol.debugSessionKey(packageName), sessionId)
                .putLong(DebugProtocol.debugExpiresAtKey(packageName), expiresAt)
                .commit()
        } else {
            sessionId = ""
            expiresAt = 0L
            editor.putBoolean(DebugProtocol.debugEnabledKey(packageName), false)
                .remove(DebugProtocol.debugSessionKey(packageName))
                .remove(DebugProtocol.debugExpiresAtKey(packageName))
                .commit()
        }
        return okBundle()
            .putStringValue("packageName", packageName)
            .putBool("enabled", enabled)
            .putStringValue("sessionId", sessionId)
            .putLongValue("expiresAt", expiresAt)
    }

    private fun clearDebugState(): Bundle {
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val editor = prefs.edit()
        var removed = 0
        prefs.all.keys.forEach { key ->
            if (key.startsWith(DebugProtocol.DEBUG_ENABLED_PREFIX)
                || key.startsWith(DebugProtocol.DEBUG_SESSION_PREFIX)
                || key.startsWith(DebugProtocol.DEBUG_EXPIRES_AT_PREFIX)
                || key.startsWith(DebugProtocol.DEBUG_COMMAND_PREFIX)
            ) {
                editor.remove(key)
                removed++
            }
        }
        editor.commit()
        return okBundle().putIntValue("removed", removed)
    }

    private fun sendDebugCommand(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        val processName = extras?.getString(ARG_PROCESS).orEmpty().trim()
        val pauseId = extras?.getString(ARG_PAUSE_ID).orEmpty().trim()
        val command = extras?.getString(ARG_COMMAND).orEmpty().trim().ifBlank { DebugProtocol.COMMAND_CONTINUE }
        val expression = extras?.getString(ARG_EXPRESSION).orEmpty()
        val payloadJson = extras?.getString(ARG_PAYLOAD_JSON).orEmpty()
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")
        if (pauseId.isBlank()) return errorBundle("pauseId 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val json = JSONObject()
            .put("packageName", packageName)
            .put("processName", processName)
            .put("pauseId", pauseId)
            .put("command", command)
            .put("expression", expression)
            .put("payload", if (payloadJson.isBlank()) JSONObject() else JSONObject(payloadJson))
            .put("time", System.currentTimeMillis())
            .toString()
        prefs.edit().putString(DebugProtocol.debugCommandKey(pauseId), json).commit()
        return okBundle()
            .putStringValue("packageName", packageName)
            .putStringValue("processName", processName)
            .putStringValue("pauseId", pauseId)
            .putStringValue("command", command)
    }


    private fun getDebugBreakpoints(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")
        val prefs = awaitRemotePreferences(1200)
        val raw = prefs?.getString(DebugProtocol.debugBreakpointsKey(packageName), "{}") ?: "{}"
        return okBundle()
            .putStringValue("packageName", packageName)
            .putStringValue("json", raw.ifBlank { "{}" })
            .putBool("remotePreferencesReady", prefs != null)
    }

    private fun setDebugBreakpoints(extras: Bundle?): Bundle {
        val packageName = extras?.getString(ARG_PACKAGE).orEmpty().trim()
        val scriptPath = extras?.getString(ARG_SCRIPT_PATH).orEmpty().trim()
        val linesRaw = extras?.getString(ARG_LINES_JSON).orEmpty()
        if (packageName.isBlank()) return errorBundle("packageName 不能为空")
        if (scriptPath.isBlank()) return errorBundle("scriptPath 不能为空")
        val prefs = awaitRemotePreferences(2000) ?: return errorBundle("LSPosed Remote Preferences 未连接")
        val root = runCatching { JSONObject(prefs.getString(DebugProtocol.debugBreakpointsKey(packageName), "{}") ?: "{}") }.getOrElse { JSONObject() }
        val lines = runCatching { JSONArray(linesRaw.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        val clean = JSONArray()
        val seen = LinkedHashSet<Int>()
        for (i in 0 until lines.length()) {
            val line = lines.optInt(i, -1)
            if (line > 0 && seen.add(line)) clean.put(line)
        }
        root.put(scriptPath, clean)
        prefs.edit().putString(DebugProtocol.debugBreakpointsKey(packageName), root.toString()).commit()
        val out = JSONObject()
            .put("ok", true)
            .put("packageName", packageName)
            .put("scriptPath", scriptPath)
            .put("lines", clean)
            .put("breakpoints", root)
        return okBundle().putStringValue("json", out.toString())
    }

    private fun awaitRemotePreferences(timeoutMs: Long): android.content.SharedPreferences? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0)
        while (XiaoHeiApplication.remotePreferences == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        return XiaoHeiApplication.remotePreferences
    }

    private fun awaitXposedService(timeoutMs: Long): XposedService? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0)
        while (XiaoHeiApplication.xposedService == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        return XiaoHeiApplication.xposedService
    }

    private fun okBundle(): Bundle = Bundle().apply { putBoolean("ok", true) }
    private fun errorBundle(message: String): Bundle = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun Bundle.putBool(key: String, value: Boolean): Bundle = apply { putBoolean(key, value) }
    private fun Bundle.putStringValue(key: String, value: String): Bundle = apply { putString(key, value) }
    private fun Bundle.putLongValue(key: String, value: Long): Bundle = apply { putLong(key, value) }
    private fun Bundle.putIntValue(key: String, value: Int): Bundle = apply { putInt(key, value) }

    companion object {
        const val AUTHORITY = "top.lovepikachu.XiaoHeiHook.webide.bridge"
        const val METHOD_STATUS = "status"
        const val METHOD_GET_BOOLEAN = "getBoolean"
        const val METHOD_GET_LONG = "getLong"
        const val METHOD_GET_STRING = "getString"
        const val METHOD_PUT_STRING = "putString"
        const val METHOD_REMOVE = "remove"
        const val METHOD_PUT_BOOLEAN = "putBoolean"
        const val METHOD_SET_APP_ENABLED = "setAppEnabled"
        const val METHOD_SET_SCRIPT_ENABLED = "setScriptEnabled"
        const val METHOD_SYNC_SCRIPTS = "syncScripts"
        const val METHOD_SET_DEBUG_ENABLED = "setDebugEnabled"
        const val METHOD_CLEAR_DEBUG_STATE = "clearDebugState"
        const val METHOD_SEND_DEBUG_COMMAND = "sendDebugCommand"
        const val METHOD_GET_DEBUG_BREAKPOINTS = "getDebugBreakpoints"
        const val METHOD_SET_DEBUG_BREAKPOINTS = "setDebugBreakpoints"
        const val ARG_KEY = "key"
        const val ARG_DEFAULT = "default"
        const val ARG_DEFAULT_LONG = "defaultLong"
        const val ARG_DEFAULT_STRING = "defaultString"
        const val ARG_VALUE = "value"
        const val ARG_PACKAGE = "packageName"
        const val ARG_SCRIPT_ID = "scriptId"
        const val ARG_ENABLED = "enabled"
        const val ARG_PROCESS = "processName"
        const val ARG_PAUSE_ID = "pauseId"
        const val ARG_COMMAND = "command"
        const val ARG_EXPRESSION = "expression"
        const val ARG_PAYLOAD_JSON = "payloadJson"
        const val ARG_SCRIPT_PATH = "scriptPath"
        const val ARG_LINES_JSON = "linesJson"
    }
}
