package top.lovepikachu.XiaoHeiHook.mcp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import top.lovepikachu.XiaoHeiHook.webide.ProcessUtil

class McpBridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MCP IPC bridge service created pid=${android.os.Process.myPid()} process=${ProcessUtil.currentProcessName(this)} runtimeActive=${McpForegroundService.isRuntimeActive()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!McpForegroundService.isRuntimeActive()) {
            Log.w(TAG, "MCP IPC bridge service start ignored because MCP runtime is not active")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Log.i(TAG, "MCP IPC bridge service started")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        val active = McpForegroundService.isRuntimeActive()
        Log.i(TAG, "MCP IPC bridge onBind action=${intent?.action} runtimeActive=$active pid=${android.os.Process.myPid()} process=${ProcessUtil.currentProcessName(this)}")
        if (!active) {
            Log.w(TAG, "MCP IPC bridge bind ignored because MCP runtime is not active")
            stopSelf()
            return null
        }
        Log.i(TAG, "MCP IPC bridge bound")
        return createBinder(applicationContext)
    }

    companion object {
        private const val TAG = "XiaoHeiHook-MCP-IPC"
        const val ACTION_START = "top.lovepikachu.XiaoHeiHook.MCP_BRIDGE_START"
        const val ACTION_BIND = "top.lovepikachu.XiaoHeiHook.MCP_BRIDGE_BIND"
        const val BRIDGE_PACKAGE = "top.lovepikachu.XiaoHeiHook"
        const val BRIDGE_CLASS = "top.lovepikachu.XiaoHeiHook.mcp.McpBridgeService"

        fun createBinder(context: Context): IBinder {
            val appContext = context.applicationContext
            return object : IMcpBridgeService.Stub() {
                override fun openSession(hello: Bundle?, callback: IMcpRuntimeCallback?): Bundle {
                    if (!McpForegroundService.isRuntimeActive()) {
                        Log.w(TAG, "reject IPC openSession because MCP runtime is not active")
                        return McpBridgeIpc.resultBundle(false, reason = McpBridgeProtocol.ERROR_MCP_DISABLED)
                    }
                    if (callback == null) {
                        Log.w(TAG, "reject IPC openSession with null callback")
                        return McpBridgeIpc.resultBundle(false, reason = "CALLBACK_REQUIRED")
                    }
                    val json = McpBridgeIpc.bundleToJson(hello)
                    val sessionId = json.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "").trim()
                    val packageName = json.optString(McpBridgeProtocol.EXTRA_PACKAGE_NAME, "").trim()
                    val processName = json.optString(McpBridgeProtocol.EXTRA_PROCESS_NAME, "").trim()
                    val bridgeToken = json.optString(McpBridgeProtocol.EXTRA_BRIDGE_TOKEN, "").trim()
                    if (sessionId.isBlank() || packageName.isBlank() || processName.isBlank()) {
                        Log.w(TAG, "reject invalid IPC hello session=$sessionId package=$packageName process=$processName")
                        return McpBridgeIpc.resultBundle(false, reason = "INVALID_HELLO")
                    }
                    if (!McpSecurity.isValid(appContext, bridgeToken)) {
                        Log.w(TAG, "reject IPC hello with invalid token package=$packageName process=$processName session=$sessionId")
                        return McpBridgeIpc.resultBundle(false, reason = "INVALID_TOKEN")
                    }
                    val connection = BinderRuntimeConnection(sessionId, packageName, processName, callback)
                    McpMethodRegistry.attachSession(sessionId, packageName, processName, connection)
                    runCatching {
                        callback.asBinder().linkToDeath({
                            Log.i(TAG, "MCP IPC binder died package=$packageName process=$processName session=$sessionId")
                            McpMethodRegistry.detachSession(sessionId, "binder-death")
                        }, 0)
                    }.onFailure { error ->
                        Log.w(TAG, "linkToDeath failed session=$sessionId", error)
                    }
                    Log.i(TAG, "MCP IPC session online package=$packageName process=$processName session=$sessionId")
                    return McpBridgeIpc.resultBundle(true, action = "opened")
                }

                override fun registerMethod(sessionId: String?, method: Bundle?): Bundle {
                    if (!McpForegroundService.isRuntimeActive()) {
                        Log.w(TAG, "reject IPC register because MCP runtime is not active")
                        return McpBridgeIpc.resultBundle(false, reason = McpBridgeProtocol.ERROR_MCP_DISABLED)
                    }
                    val json = McpBridgeIpc.bundleToJson(method)
                    val cleanSessionId = sessionId.orEmpty().ifBlank { json.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "") }
                    val packageName = json.optString(McpBridgeProtocol.EXTRA_PACKAGE_NAME, "").trim()
                    val processName = json.optString(McpBridgeProtocol.EXTRA_PROCESS_NAME, "").trim()
                    val methodName = json.optString(McpBridgeProtocol.EXTRA_METHOD_NAME, "").trim()
                    val handlerId = json.optString(McpBridgeProtocol.EXTRA_HANDLER_ID, "").trim()
                    if (cleanSessionId.isBlank() || packageName.isBlank() || processName.isBlank() || methodName.isBlank() || handlerId.isBlank()) {
                        Log.w(TAG, "ignore invalid IPC register session=$cleanSessionId package=$packageName process=$processName method=$methodName handler=$handlerId")
                        return McpBridgeIpc.resultBundle(false, reason = "INVALID_REGISTER")
                    }
                    val now = System.currentTimeMillis()
                    val methodInfo = RegisteredMcpMethod(
                        packageName = packageName,
                        processName = processName,
                        sessionId = cleanSessionId,
                        methodName = methodName,
                        handlerId = handlerId,
                        scriptName = json.optString(McpBridgeProtocol.EXTRA_SCRIPT_NAME, ""),
                        scriptPath = json.optString(McpBridgeProtocol.EXTRA_SCRIPT_PATH, ""),
                        description = json.optString(McpBridgeProtocol.EXTRA_DESCRIPTION, ""),
                        paramsSchemaJson = json.optString(McpBridgeProtocol.EXTRA_PARAMS_SCHEMA_JSON, ""),
                        timeoutMs = json.optLong(McpBridgeProtocol.EXTRA_TIMEOUT_MS, 5_000L).coerceIn(100L, 30_000L),
                        concurrency = json.optString(McpBridgeProtocol.EXTRA_CONCURRENCY, "parallel").ifBlank { "parallel" },
                        registeredAt = now,
                        lastSeenAt = now
                    )
                    val conflict = json.optString(McpBridgeProtocol.EXTRA_CONFLICT, McpBridgeProtocol.CONFLICT_OVERWRITE)
                    val result = McpMethodRegistry.register(methodInfo, conflict)
                    Log.i(TAG, "register IPC method result=$result package=$packageName process=$processName session=$cleanSessionId method=$methodName handler=$handlerId")
                    return McpBridgeIpc.jsonToBundle(result)
                }

                override fun unregisterMethod(sessionId: String?, methodName: String?) {
                    val sid = sessionId.orEmpty()
                    if (sid.isBlank()) return
                    val current = McpMethodRegistry.sessionInfo(sid) ?: return
                    Log.i(TAG, "unregister IPC method package=${current.packageName} process=${current.processName} session=$sid method=${methodName ?: "<all>"}")
                    McpMethodRegistry.unregister(current.packageName, current.processName, sid, methodName)
                }

                override fun unregisterAllMethods(sessionId: String?) {
                    val sid = sessionId.orEmpty()
                    if (sid.isBlank()) return
                    val current = McpMethodRegistry.sessionInfo(sid) ?: return
                    Log.i(TAG, "unregister all IPC methods package=${current.packageName} process=${current.processName} session=$sid")
                    McpMethodRegistry.unregister(current.packageName, current.processName, sid, null)
                }

                override fun heartbeat(sessionId: String?) {
                    val sid = sessionId.orEmpty()
                    val current = McpMethodRegistry.sessionInfo(sid) ?: return
                    McpMethodRegistry.heartbeat(current.packageName, current.processName, sid)
                }

                override fun sendResult(sessionId: String?, requestId: String?, result: Bundle?) {
                    val sid = sessionId.orEmpty()
                    val rid = requestId.orEmpty()
                    val json = McpBridgeIpc.bundleToJson(result)
                    if (sid.isNotBlank()) json.put(McpBridgeProtocol.EXTRA_SESSION_ID, sid)
                    if (rid.isNotBlank()) json.put(McpBridgeProtocol.EXTRA_REQUEST_ID, rid)
                    Log.i(TAG, "receive MCP IPC result request=$rid ok=${json.optBoolean(McpBridgeProtocol.EXTRA_OK, false)} session=$sid")
                    McpMethodRegistry.completeResult(rid, json)
                }
            }
        }

        private class BinderRuntimeConnection(
            private val sessionId: String,
            private val packageName: String,
            private val processName: String,
            private val callback: IMcpRuntimeCallback
        ) : McpRuntimeConnection {
            override fun send(message: org.json.JSONObject): Boolean {
                return try {
                    callback.invoke(McpBridgeIpc.jsonToBundle(message))
                    true
                } catch (dead: RemoteException) {
                    Log.w(TAG, "send IPC invoke failed session=$sessionId", dead)
                    McpMethodRegistry.detachSession(sessionId, "binder-send-failed")
                    false
                } catch (t: Throwable) {
                    Log.w(TAG, "send IPC invoke failed session=$sessionId", t)
                    McpMethodRegistry.detachSession(sessionId, "binder-send-failed")
                    false
                }
            }

            override fun close(reason: String) {
                Log.i(TAG, "close IPC runtime connection session=$sessionId package=$packageName process=$processName reason=$reason")
                McpMethodRegistry.detachSession(sessionId, reason)
            }
        }
    }
}
