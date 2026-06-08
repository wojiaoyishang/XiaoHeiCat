package top.lovepikachu.XiaoHeiHook.mcp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class McpBridgeSocketServer(
    context: Context,
    private val requestedBridgePort: Int = 0
) {
    @Volatile
    var actualPort: Int = 0
        private set
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = ConcurrentHashMap<String, BridgeConnection>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val bindPort = if (requestedBridgePort in 1024..65535) requestedBridgePort else 0
        val address = InetSocketAddress(InetAddress.getByName(BRIDGE_HOST), bindPort)
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(address)
            actualPort = localPort
        }
        acceptThread = Thread({ acceptLoop() }, "XHH-MCP-bridge-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "MCP bridge TCP socket started: $BRIDGE_HOST:$actualPort requestedPort=$requestedBridgePort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        actualPort = 0
        connections.values.forEach { it.close("server-stop") }
        connections.clear()
        McpMethodRegistry.clearAll()
        Log.i(TAG, "MCP bridge TCP socket stopped")
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                socket.tcpNoDelay = true
                BridgeConnection(socket).start()
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept MCP bridge TCP connection failed", t)
            }
        }
    }

    private fun onHello(connection: BridgeConnection, message: JSONObject) {
        val sessionId = message.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "").trim()
        val packageName = message.optString(McpBridgeProtocol.EXTRA_PACKAGE_NAME, "").trim()
        val processName = message.optString(McpBridgeProtocol.EXTRA_PROCESS_NAME, "").trim()
        val bridgeToken = message.optString(McpBridgeProtocol.EXTRA_BRIDGE_TOKEN, "").trim()
        if (sessionId.isBlank() || packageName.isBlank() || processName.isBlank()) {
            Log.w(TAG, "invalid MCP bridge hello session=$sessionId package=$packageName process=$processName")
            connection.close("invalid-hello")
            return
        }
        if (!McpSecurity.isValid(appContext, bridgeToken)) {
            Log.w(TAG, "reject MCP bridge hello with invalid token package=$packageName process=$processName session=$sessionId")
            connection.close("invalid-token")
            return
        }
        connection.sessionId = sessionId
        connection.packageName = packageName
        connection.processName = processName
        connection.debugLogging = message.optBoolean(McpBridgeProtocol.EXTRA_DEBUG_LOG, false)

        connections[sessionId]?.takeIf { it !== connection }?.close("replaced-by-new-session")
        connections[sessionId] = connection
        McpMethodRegistry.attachSession(sessionId, packageName, processName, connection, connection.debugLogging)
        if (connection.debugLogging) Log.i(TAG, "MCP bridge session online package=$packageName process=$processName session=$sessionId")
    }

    private fun onRegister(connection: BridgeConnection, message: JSONObject) {
        val now = System.currentTimeMillis()
        val sessionId = connection.sessionId.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "") }
        val packageName = connection.packageName.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_PACKAGE_NAME, "") }
        val processName = connection.processName.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_PROCESS_NAME, "") }
        val methodName = message.optString(McpBridgeProtocol.EXTRA_METHOD_NAME, "").trim()
        val handlerId = message.optString(McpBridgeProtocol.EXTRA_HANDLER_ID, "").trim()
        if (sessionId.isBlank() || packageName.isBlank() || processName.isBlank() || methodName.isBlank() || handlerId.isBlank()) {
            Log.w(TAG, "ignore invalid MCP register session=$sessionId package=$packageName process=$processName method=$methodName handler=$handlerId")
            return
        }
        val method = RegisteredMcpMethod(
            packageName = packageName,
            processName = processName,
            sessionId = sessionId,
            methodName = methodName,
            handlerId = handlerId,
            scriptName = message.optString(McpBridgeProtocol.EXTRA_SCRIPT_NAME, ""),
            scriptPath = message.optString(McpBridgeProtocol.EXTRA_SCRIPT_PATH, ""),
            description = message.optString(McpBridgeProtocol.EXTRA_DESCRIPTION, ""),
            paramsSchemaJson = message.optString(McpBridgeProtocol.EXTRA_PARAMS_SCHEMA_JSON, ""),
            timeoutMs = message.optLong(McpBridgeProtocol.EXTRA_TIMEOUT_MS, 5_000L).coerceIn(100L, 30_000L),
            concurrency = message.optString(McpBridgeProtocol.EXTRA_CONCURRENCY, "parallel").ifBlank { "parallel" },
            debugLogging = message.optBoolean(McpBridgeProtocol.EXTRA_DEBUG_LOG, connection.debugLogging),
            registeredAt = now,
            lastSeenAt = now
        )
        val conflict = message.optString(McpBridgeProtocol.EXTRA_CONFLICT, McpBridgeProtocol.CONFLICT_OVERWRITE)
        val result = McpMethodRegistry.register(method, conflict)
        val ackSent = connection.send(JSONObject().put("type", "register_result").put("methodName", methodName).put("result", result))
        if (method.debugLogging) Log.i(TAG, "register socket method ackSent=$ackSent result=$result package=$packageName process=$processName session=$sessionId method=$methodName handler=$handlerId")
    }

    private fun onUnregister(connection: BridgeConnection, message: JSONObject) {
        val packageName = connection.packageName.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_PACKAGE_NAME, "") }
        val processName = connection.processName.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_PROCESS_NAME, "") }
        val sessionId = connection.sessionId.orEmpty().ifBlank { message.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "") }
        val methodName = message.optString(McpBridgeProtocol.EXTRA_METHOD_NAME, "").ifBlank { null }
        val label = methodName ?: "<all>"
        if (connection.debugLogging) Log.i(TAG, "unregister socket method package=$packageName process=$processName session=$sessionId method=$label")
        McpMethodRegistry.unregister(
            packageName = packageName,
            processName = processName,
            sessionId = sessionId,
            methodName = methodName
        )
    }

    private fun onResult(message: JSONObject) {
        val requestId = message.optString(McpBridgeProtocol.EXTRA_REQUEST_ID, "")
        val ok = message.optBoolean(McpBridgeProtocol.EXTRA_OK, false)
        val sessionId = message.optString(McpBridgeProtocol.EXTRA_SESSION_ID, "")
        if (McpMethodRegistry.isDebugLoggingEnabled(sessionId)) Log.i(TAG, "receive MCP result request=$requestId ok=$ok session=$sessionId")
        McpMethodRegistry.completeResult(requestId, message)
    }

    private fun onDisconnect(connection: BridgeConnection, reason: String) {
        val sessionId = connection.sessionId
        if (!sessionId.isNullOrBlank()) {
            val debugLogging = McpMethodRegistry.isDebugLoggingEnabled(sessionId) || connection.debugLogging
            connections.remove(sessionId, connection)
            McpMethodRegistry.detachSession(sessionId, reason)
            if (debugLogging) Log.i(TAG, "MCP bridge session offline session=$sessionId reason=$reason")
        }
    }

    inner class BridgeConnection(private val socket: Socket) : McpRuntimeConnection {
        @Volatile var sessionId: String? = null
        @Volatile var packageName: String? = null
        @Volatile var processName: String? = null
        @Volatile var debugLogging: Boolean = false
        private val closed = AtomicBoolean(false)
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        private val writeLock = Any()

        fun start() {
            Thread({ readLoop() }, "XHH-MCP-bridge-client").apply {
                isDaemon = true
                start()
            }
        }

        private fun readLoop() {
            var closeReason = "eof"
            try {
                while (running.get() && !closed.get()) {
                    val message = readFrame(input)
                    when (message.optString("type")) {
                        "hello" -> onHello(this, message)
                        "register" -> onRegister(this, message)
                        "unregister" -> onUnregister(this, message)
                        "ping" -> {
                            if (debugLogging) Log.d(TAG, "MCP bridge ping session=${sessionId.orEmpty()} package=${packageName.orEmpty()} process=${processName.orEmpty()}")
                            McpMethodRegistry.heartbeat(
                                packageName.orEmpty(),
                                processName.orEmpty(),
                                sessionId.orEmpty()
                            )
                        }
                        "result" -> onResult(message)
                        "bye" -> {
                            closeReason = "client-bye"
                            break
                        }
                    }
                }
            } catch (e: EOFException) {
                closeReason = "eof"
            } catch (t: Throwable) {
                closeReason = t.message ?: t.javaClass.simpleName
                if (running.get() && !closed.get()) Log.w(TAG, "MCP bridge connection failed", t)
            } finally {
                close(closeReason)
            }
        }

        override fun send(message: JSONObject): Boolean {
            if (closed.get()) return false
            return try {
                synchronized(writeLock) {
                    writeFrame(output, message)
                    output.flush()
                }
                true
            } catch (t: Throwable) {
                close(t.message ?: t.javaClass.simpleName)
                false
            }
        }

        override fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            onDisconnect(this, reason)
        }
    }

    companion object {
        const val BRIDGE_HOST = "127.0.0.1"
        private const val TAG = "XiaoHeiHook-MCP-Bridge"
        private const val MAX_FRAME_BYTES = 1024 * 1024

        fun readFrame(input: DataInputStream): JSONObject {
            val length = input.readInt()
            if (length <= 0 || length > MAX_FRAME_BYTES) throw IllegalArgumentException("invalid MCP bridge frame length: $length")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return JSONObject(String(bytes, StandardCharsets.UTF_8))
        }

        fun writeFrame(output: DataOutputStream, message: JSONObject) {
            val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FRAME_BYTES) throw IllegalArgumentException("MCP bridge frame too large: ${bytes.size}")
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }
}

