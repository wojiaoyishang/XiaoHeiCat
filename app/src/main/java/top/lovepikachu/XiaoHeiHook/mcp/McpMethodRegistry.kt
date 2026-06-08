package top.lovepikachu.XiaoHeiHook.mcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object McpMethodRegistry {
    private const val TAG = "XiaoHeiHook-MCP-Bridge"
    private const val MAX_TIMEOUT_MS = 30_000L
    private val methods = ConcurrentHashMap<String, RegisteredMcpMethod>()
    private val sessions = ConcurrentHashMap<String, McpRuntimeConnection>()
    private val targetSessions = ConcurrentHashMap<String, String>()
    private val sessionDebug = ConcurrentHashMap<String, Boolean>()
    private val pending = ConcurrentHashMap<String, PendingMcpCall>()

    fun attachSession(sessionId: String, packageName: String, processName: String, connection: McpRuntimeConnection) {
        attachSession(sessionId, packageName, processName, connection, false)
    }

    fun attachSession(sessionId: String, packageName: String, processName: String, connection: McpRuntimeConnection, debugLogging: Boolean) {
        val cleanSessionId = sessionId.trim()
        if (cleanSessionId.isBlank()) return
        val targetKey = targetKey(packageName, processName)
        val previousSession = targetSessions.put(targetKey, cleanSessionId)
        if (!previousSession.isNullOrBlank() && previousSession != cleanSessionId) {
            detachSession(previousSession, "replaced-by-new-session")
        }
        sessions[cleanSessionId] = connection
        sessionDebug[cleanSessionId] = debugLogging
        if (debugLogging) Log.i(TAG, "attach session package=$packageName process=$processName session=$cleanSessionId previous=${previousSession.orEmpty()}")
        heartbeat(packageName, processName, cleanSessionId)
    }

    fun isDebugLoggingEnabled(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        if (sessionDebug[sessionId] == true) return true
        return methods.values.any { it.sessionId == sessionId && it.debugLogging }
    }

    private fun isAnyDebugLoggingEnabled(): Boolean {
        return sessionDebug.values.any { it } || methods.values.any { it.debugLogging }
    }

    fun sessionInfo(sessionId: String): McpRuntimeSessionInfo? {
        val cleanSessionId = sessionId.trim()
        if (cleanSessionId.isBlank() || !sessions.containsKey(cleanSessionId)) return null
        val method = methods.values.firstOrNull { it.sessionId == cleanSessionId }
        if (method != null) return McpRuntimeSessionInfo(method.packageName, method.processName)
        val targetEntry = targetSessions.entries.firstOrNull { it.value == cleanSessionId } ?: return null
        val parts = targetEntry.key.split("\u0000", limit = 2)
        return McpRuntimeSessionInfo(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    fun detachSession(sessionId: String, reason: String = "disconnected") {
        val cleanSessionId = sessionId.trim()
        if (cleanSessionId.isBlank()) return
        val debugLogging = isDebugLoggingEnabled(cleanSessionId)
        sessions.remove(cleanSessionId)
        val affectedMethods = methods.values.filter { it.sessionId == cleanSessionId }
        val removedMethods = affectedMethods.size
        methods.entries.removeIf { it.value.sessionId == cleanSessionId }
        targetSessions.entries.removeIf { it.value == cleanSessionId }
        sessionDebug.remove(cleanSessionId)
        val offline = error(null, McpBridgeProtocol.ERROR_TARGET_OFFLINE, "Target session closed: $reason")
        var cancelledPending = 0
        pending.entries.removeIf { entry ->
            if (entry.value.sessionId == cleanSessionId) {
                entry.value.future.complete(offline)
                cancelledPending++
                true
            } else {
                false
            }
        }
        if (debugLogging) Log.i(TAG, "detach session=$cleanSessionId reason=$reason removedMethods=$removedMethods cancelledPending=$cancelledPending")
    }

    fun register(method: RegisteredMcpMethod, conflict: String): JSONObject {
        val cleanConflict = when (conflict.lowercase()) {
            McpBridgeProtocol.CONFLICT_IGNORE -> McpBridgeProtocol.CONFLICT_IGNORE
            McpBridgeProtocol.CONFLICT_ERROR -> McpBridgeProtocol.CONFLICT_ERROR
            else -> McpBridgeProtocol.CONFLICT_OVERWRITE
        }
        val key = method.key
        val previous = methods[key]
        if (previous != null) {
            when (cleanConflict) {
                McpBridgeProtocol.CONFLICT_IGNORE -> {
                    previous.lastSeenAt = System.currentTimeMillis()
                    if (method.debugLogging || previous.debugLogging) Log.i(TAG, "register ignored package=${method.packageName} process=${method.processName} method=${method.methodName} session=${method.sessionId}")
                    return JSONObject()
                        .put("ok", true)
                        .put("methodName", method.methodName)
                        .put("action", "ignored")
                }
                McpBridgeProtocol.CONFLICT_ERROR -> {
                    return JSONObject()
                        .put("ok", false)
                        .put("methodName", method.methodName)
                        .put("action", "error")
                        .put("error", JSONObject()
                            .put("code", McpBridgeProtocol.ERROR_REGISTER_CONFLICT)
                            .put("message", "Method already registered: ${method.methodName}"))
                }
                else -> {
                    methods[key] = method
                    if (method.debugLogging) Log.i(TAG, "register overwritten package=${method.packageName} process=${method.processName} method=${method.methodName} session=${method.sessionId}")
                    return JSONObject()
                        .put("ok", true)
                        .put("methodName", method.methodName)
                        .put("action", "overwritten")
                }
            }
        }
        methods[key] = method
        if (method.debugLogging) Log.i(TAG, "register method package=${method.packageName} process=${method.processName} method=${method.methodName} session=${method.sessionId}")
        return JSONObject()
            .put("ok", true)
            .put("methodName", method.methodName)
            .put("action", "registered")
    }

    fun unregister(packageName: String, processName: String, sessionId: String, methodName: String?) {
        if (methodName.isNullOrBlank()) {
            val before = methods.size
            methods.entries.removeIf { it.value.packageName == packageName && it.value.processName == processName && it.value.sessionId == sessionId }
            if (isDebugLoggingEnabled(sessionId)) Log.i(TAG, "unregister all package=$packageName process=$processName session=$sessionId removed=${before - methods.size}")
        } else {
            val key = RegisteredMcpMethod.methodKey(packageName, processName, methodName)
            val current = methods[key]
            if (current != null && current.sessionId == sessionId) {
                val removedDebug = current.debugLogging
                methods.remove(key)
                if (removedDebug) Log.i(TAG, "unregister method package=$packageName process=$processName session=$sessionId method=$methodName")
            } else {
                if (isDebugLoggingEnabled(sessionId)) Log.i(TAG, "unregister ignored package=$packageName process=$processName session=$sessionId method=$methodName")
            }
        }
    }

    fun heartbeat(packageName: String, processName: String, sessionId: String) {
        val now = System.currentTimeMillis()
        methods.values.forEach { method ->
            if (method.packageName == packageName && method.processName == processName && method.sessionId == sessionId) {
                method.lastSeenAt = now
            }
        }
    }

    fun listMethods(packageName: String, processName: String?): JSONArray {
        purgeDisconnected()
        val processFilter = processName.orEmpty().trim()
        val out = JSONArray()
        val currentSize = methods.size
        val processLabel = processFilter.ifBlank { "<any>" }
        if (isAnyDebugLoggingEnabled()) Log.i(TAG, "list methods package=$packageName process=$processLabel activeMethods=$currentSize activeSessions=${sessions.size}")
        methods.values
            .filter { it.packageName == packageName && (processFilter.isEmpty() || it.processName == processFilter) }
            .sortedWith(compareBy<RegisteredMcpMethod> { it.processName }.thenBy { it.methodName })
            .forEach { method ->
                val item = JSONObject()
                    .put("packageName", method.packageName)
                    .put("processName", method.processName)
                    .put("methodName", method.methodName)
                    .put("description", method.description)
                    .put("timeoutMs", method.timeoutMs)
                    .put("concurrency", method.concurrency)
                    .put("scriptName", method.scriptName)
                    .put("scriptPath", method.scriptPath)
                    .put("registeredAt", method.registeredAt)
                    .put("lastSeenAt", method.lastSeenAt)
                    .put("transport", "tcp")
                if (method.paramsSchemaJson.isNotBlank()) {
                    item.put("paramsSchema", runCatching { JSONObject(method.paramsSchemaJson) }.getOrElse { method.paramsSchemaJson })
                }
                out.put(item)
            }
        return out
    }

    fun invoke(context: Context, packageName: String, processName: String?, methodName: String, params: Any?, timeoutMs: Long?): JSONObject {
        purgeDisconnected()
        val matches = methods.values.filter { method ->
            method.packageName == packageName &&
                method.methodName == methodName &&
                (processName.isNullOrBlank() || method.processName == processName)
        }.sortedBy { it.processName }
        val method = matches.firstOrNull()
            ?: run {
                val processLabel = processName.orEmpty().ifBlank { "<any>" }
                Log.w(TAG, "invoke method not found package=$packageName process=$processLabel method=$methodName activeMethods=${methods.size} activeSessions=${sessions.size}")
                return error(null, McpBridgeProtocol.ERROR_METHOD_NOT_FOUND, "No registered method: $methodName")
            }
        val connection = sessions[method.sessionId]
            ?: run {
                detachSession(method.sessionId, "ipc-connection-not-found")
                return error(null, McpBridgeProtocol.ERROR_TARGET_OFFLINE, "Target session is offline: ${method.processName}")
            }
        val requestTimeout = (timeoutMs ?: method.timeoutMs).coerceIn(100L, MAX_TIMEOUT_MS)
        val requestId = UUID.randomUUID().toString()
        val future = CompletableFuture<JSONObject>()
        pending[requestId] = PendingMcpCall(method.sessionId, future)
        if (method.debugLogging) Log.i(TAG, "invoke dispatch package=${method.packageName} process=${method.processName} method=${method.methodName} request=$requestId session=${method.sessionId} timeout=$requestTimeout")
        return try {
            val message = JSONObject()
                .put("type", "invoke")
                .put(McpBridgeProtocol.EXTRA_PACKAGE_NAME, method.packageName)
                .put(McpBridgeProtocol.EXTRA_PROCESS_NAME, method.processName)
                .put(McpBridgeProtocol.EXTRA_SESSION_ID, method.sessionId)
                .put(McpBridgeProtocol.EXTRA_REQUEST_ID, requestId)
                .put(McpBridgeProtocol.EXTRA_METHOD_NAME, method.methodName)
                .put(McpBridgeProtocol.EXTRA_HANDLER_ID, method.handlerId)
                .put(McpBridgeProtocol.EXTRA_PARAMS_JSON, normalizeParamsJson(params))
                .put(McpBridgeProtocol.EXTRA_TIMEOUT_MS, requestTimeout)
            if (!connection.send(message)) {
                Log.w(TAG, "invoke send failed request=$requestId session=${method.sessionId}")
                detachSession(method.sessionId, "send-failed")
                error(requestId, McpBridgeProtocol.ERROR_TARGET_OFFLINE, "Target session is offline: ${method.processName}")
            } else {
                val result = future.get(requestTimeout, TimeUnit.MILLISECONDS)
                val ok = result.optBoolean("ok", false)
                if (method.debugLogging) Log.i(TAG, "invoke completed request=$requestId ok=$ok")
                result
            }
        } catch (e: TimeoutException) {
            Log.w(TAG, "invoke timeout request=$requestId timeout=$requestTimeout")
            error(requestId, McpBridgeProtocol.ERROR_TIMEOUT, "Invoke timeout after ${requestTimeout}ms")
        } catch (t: Throwable) {
            error(requestId, McpBridgeProtocol.ERROR_INVOKE_FAILED, t.message ?: t.javaClass.simpleName)
        } finally {
            pending.remove(requestId)
        }
    }

    fun completeResult(requestId: String, result: JSONObject) {
        if (requestId.isBlank()) return
        val normalized = normalizeResult(result)
        val pendingCall = pending.remove(requestId)
        val completed = pendingCall?.future?.complete(normalized) == true
        val ok = normalized.optBoolean("ok", false)
        if (completed && pendingCall != null && isDebugLoggingEnabled(pendingCall.sessionId)) {
            Log.i(TAG, "complete result request=$requestId completed=$completed ok=$ok")
        }
    }

    fun clearAll() {
        methods.clear()
        sessions.values.forEach { it.close("clear-all") }
        sessions.clear()
        targetSessions.clear()
        sessionDebug.clear()
        pending.values.forEach { it.future.cancel(true) }
        pending.clear()
    }

    fun purgeStale() {
        purgeDisconnected()
    }

    private fun purgeDisconnected() {
        val activeSessions = sessions.keys.toSet()
        methods.entries.removeIf { !activeSessions.contains(it.value.sessionId) }
    }

    private fun normalizeParamsJson(params: Any?): String {
        return when (params) {
            null -> JSONObject().toString()
            is JSONObject -> params.toString()
            is JSONArray -> params.toString()
            else -> JSONObject.wrap(params)?.toString() ?: JSONObject().toString()
        }
    }

    private fun normalizeResult(message: JSONObject): JSONObject {
        val requestId = message.optString(McpBridgeProtocol.EXTRA_REQUEST_ID, "")
        val ok = message.optBoolean(McpBridgeProtocol.EXTRA_OK, false)
        val out = JSONObject()
            .put("ok", ok)
            .put("requestId", requestId)
        if (ok) {
            if (message.has("result")) {
                out.put("result", message.opt("result"))
            } else {
                val raw = message.optString(McpBridgeProtocol.EXTRA_RESULT_JSON, "")
                val value: Any? = if (raw.isBlank()) JSONObject.NULL else parseJsonValue(raw)
                out.put("result", value ?: JSONObject.NULL)
            }
        } else {
            val error = message.optJSONObject("error") ?: JSONObject()
                .put("code", message.optString(McpBridgeProtocol.EXTRA_ERROR_CODE, McpBridgeProtocol.ERROR_INVOKE_FAILED))
                .put("message", message.optString(McpBridgeProtocol.EXTRA_ERROR_MESSAGE, ""))
            out.put("error", error)
        }
        return out
    }

    private fun parseJsonValue(raw: String): Any? {
        val text = raw.trim()
        if (text.startsWith("{")) return JSONObject(text)
        if (text.startsWith("[")) return JSONArray(text)
        if (text == "null") return JSONObject.NULL
        if (text == "true" || text == "false") return text.toBoolean()
        return text.toDoubleOrNull() ?: text
    }

    private fun error(requestId: String?, code: String, message: String): JSONObject {
        val out = JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
        if (!requestId.isNullOrBlank()) out.put("requestId", requestId)
        return out
    }

    private fun targetKey(packageName: String, processName: String): String = packageName.trim() + "\u0000" + processName.trim()

    private data class PendingMcpCall(
        val sessionId: String,
        val future: CompletableFuture<JSONObject>
    )
}
