package top.lovepikachu.XiaoHeiHook.mcp

import android.content.Context
import android.os.Process
import android.util.Log
import top.lovepikachu.XiaoHeiHook.webide.HttpRequest
import top.lovepikachu.XiaoHeiHook.webide.HttpResponse
import top.lovepikachu.XiaoHeiHook.webide.JsonUtil
import top.lovepikachu.XiaoHeiHook.webide.ProcessUtil
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class McpServer(
    private val appContext: Context,
    private val host: String,
    private val port: Int,
    private val tokenEnabled: Boolean
) {
    private val handler = McpJsonRpcHandler(appContext)
    private val requestCounter = AtomicLong(0)
    private val serverSocketRef = AtomicReference<ServerSocket?>()
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "XHH-MCP-worker-${requestCounter.incrementAndGet()}").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
        }
    }

    fun startServer() {
        if (running) return
        val address = InetAddress.getByName(host)
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, port), 50)
            }
        } catch (e: BindException) {
            throw IllegalStateException("无法绑定 $host:$port，端口可能已被占用或地址不可用", e)
        } catch (e: Exception) {
            throw IllegalStateException("MCP 启动失败：${e.message}", e)
        }

        serverSocketRef.set(socket)
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "XHH-MCP-accept").apply {
            isDaemon = false
            start()
        }
        Log.i(TAG, "MCP server started at http://$host:$port/mcp pid=${Process.myPid()} process=${ProcessUtil.currentProcessName(appContext)} tokenEnabled=$tokenEnabled")
    }

    fun stopServer() {
        running = false
        runCatching { serverSocketRef.getAndSet(null)?.close() }
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        workers.shutdownNow()
        runCatching { workers.awaitTermination(700, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "MCP server stopped")
    }

    fun touch(reason: String) {
        Log.i(TAG, "MCP touch: $reason pid=${Process.myPid()} listening=http://$host:$port/mcp")
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running && !serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                client.soTimeout = 30_000
                workers.execute { handleClient(client) }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "accept socket error", e)
            } catch (e: Throwable) {
                if (running) Log.e(TAG, "accept loop failed", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        val requestId = requestCounter.incrementAndGet()
        client.use { socket ->
            try {
                val request = readRequest(socket)
                if (request == null) {
                    writeResponse(socket, HttpResponse.plain(400, "Bad Request"))
                    return
                }
                val response = route(request)
                writeResponse(socket, response.withMcpCors())
            } catch (e: Throwable) {
                Log.e(TAG, "#$requestId serve failed", e)
                runCatching {
                    writeResponse(
                        socket,
                        HttpResponse.json(500, "{\"ok\":false,\"error\":${JsonUtil.quote(e.message ?: e.javaClass.simpleName)}}").withMcpCors()
                    )
                }
            }
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return HttpResponse.json(200, "{\"ok\":true}")
        if (request.path != "/mcp") return HttpResponse.plain(404, "not found")
        if (!request.method.equals("POST", ignoreCase = true)) return HttpResponse.plain(405, "method not allowed")
        if (tokenEnabled && !isAuthorized(request)) return HttpResponse.json(401, "{\"ok\":false,\"error\":\"MCP token 无效或缺失\"}")
        val result = handler.handle(request.bodyText)
        return HttpResponse.json(200, result.toString())
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val auth = request.headers["authorization"].orEmpty()
        val bearer = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substringAfter(' ').trim() else null
        return McpSecurity.isValid(appContext, bearer)
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = input.readAsciiLine() ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readAsciiLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (contentLength > 0) input.readExactly(contentLength) else ByteArray(0)
        return HttpRequest(parts[0], parts[1], headers, body)
    }

    private fun HttpResponse.withMcpCors(): HttpResponse {
        withCors()
        headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, Mcp-Session-Id"
        return this
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val reason = statusReason(response.status)
        val headers = LinkedHashMap(response.headers)
        headers["Content-Type"] = response.mimeType
        headers["Content-Length"] = response.body.size.toString()
        headers["Connection"] = "close"
        headers["Cache-Control"] = "no-store"

        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(head)
        output.write(response.body)
        output.flush()
    }

    private fun statusReason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun BufferedInputStream.readAsciiLine(): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 32 * 1024) throw IllegalArgumentException("HTTP header line too long")
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun BufferedInputStream.readExactly(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(data, offset, length - offset)
            if (read < 0) throw IllegalArgumentException("Unexpected EOF while reading body")
            offset += read
        }
        return data
    }

    private companion object {
        const val TAG = "XiaoHeiHook-MCP"
    }
}
