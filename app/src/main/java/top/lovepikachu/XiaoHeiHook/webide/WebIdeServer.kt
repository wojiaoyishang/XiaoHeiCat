package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.FileNotFoundException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WebIDE HTTP server.
 *
 * The WebIDE server keeps the dedicated :webide foreground-service process and the direct
 * ServerSocket implementation, then expands the API/static layer for the formal
 * Monaco-based IDE page.
 */
class WebIdeServer(
    private val appContext: Context,
    private val host: String,
    private val port: Int
) {
    private val api = WebIdeApi(appContext)
    private val requestCounter = AtomicLong(0)
    private val serverSocketRef = AtomicReference<ServerSocket?>()
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "XHH-WebIDE-worker-${requestCounter.incrementAndGet()}").apply {
            isDaemon = false
            priority = Thread.MAX_PRIORITY
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
            throw IllegalStateException("WebIDE 启动失败：${e.message}", e)
        }

        serverSocketRef.set(socket)
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "XHH-WebIDE-accept").apply {
            isDaemon = false
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "WebIDE soft-debug server started at http://$host:$port/ pid=${Process.myPid()} process=${ProcessUtil.currentProcessName(appContext)}")
    }

    fun stopServer() {
        running = false
        runCatching { serverSocketRef.getAndSet(null)?.close() }
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        workers.shutdownNow()
        runCatching { workers.awaitTermination(700, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "WebIDE soft-debug server stopped")
    }

    fun touch(reason: String) {
        Log.i(TAG, "WebIDE touch: $reason pid=${Process.myPid()} process=${ProcessUtil.currentProcessName(appContext)} listening=http://$host:$port/")
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        setForegroundThreadPriority("accept")
        Log.i(TAG, "accept loop started, local=${serverSocket.localSocketAddress}, thread=${Thread.currentThread().name}")
        while (running && !serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                client.soTimeout = 15_000
                workers.execute { handleClient(client) }
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "accept socket error", e)
            } catch (e: Throwable) {
                if (running) Log.e(TAG, "accept loop failed", e)
            }
        }
        Log.i(TAG, "accept loop ended")
    }

    private fun handleClient(client: Socket) {
        setForegroundThreadPriority("worker")
        val requestId = requestCounter.incrementAndGet()
        val begin = System.currentTimeMillis()
        client.use { socket ->
            try {
                val request = readRequest(socket)
                if (request == null) {
                    writeResponse(socket, HttpResponse.plain(400, "Bad Request"))
                    return
                }
                Log.d(TAG, "#$requestId <= ${request.method} ${request.rawTarget}")
                if (request.path == "/api/logs/stream") {
                    api.streamLogs(request, socket.getOutputStream()) { running && !socket.isClosed }
                    Log.d(TAG, "#$requestId => ${request.method} ${request.rawTarget} stream closed ${System.currentTimeMillis() - begin}ms")
                    return
                }
                if (request.path == "/api/debug/stream") {
                    api.streamDebug(request, socket.getOutputStream()) { running && !socket.isClosed }
                    Log.d(TAG, "#$requestId => ${request.method} ${request.rawTarget} debug stream closed ${System.currentTimeMillis() - begin}ms")
                    return
                }
                val response = route(request)
                writeResponse(socket, response.withCors())
                Log.d(TAG, "#$requestId => ${request.method} ${request.rawTarget} ${System.currentTimeMillis() - begin}ms")
            } catch (e: SocketException) {
                if (isClientDisconnect(e)) {
                    Log.i(TAG, "#$requestId client disconnected: ${e.message ?: e.javaClass.simpleName}")
                } else {
                    Log.w(TAG, "#$requestId socket error", e)
                    runCatching {
                        writeResponse(
                            socket,
                            HttpResponse.json(500, "{\"ok\":false,\"error\":${JsonUtil.quote(e.message ?: e.javaClass.simpleName)}}").withCors()
                        )
                    }
                }
            } catch (e: IOException) {
                if (isClientDisconnect(e)) {
                    Log.i(TAG, "#$requestId client disconnected: ${e.message ?: e.javaClass.simpleName}")
                } else {
                    Log.w(TAG, "#$requestId I/O error", e)
                    runCatching {
                        writeResponse(
                            socket,
                            HttpResponse.json(500, "{\"ok\":false,\"error\":${JsonUtil.quote(e.message ?: e.javaClass.simpleName)}}").withCors()
                        )
                    }
                }
            } catch (e: Throwable) {
                if (isClientDisconnect(e)) {
                    Log.i(TAG, "#$requestId client disconnected: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                Log.e(TAG, "#$requestId serve failed", e)
                runCatching {
                    writeResponse(
                        socket,
                        HttpResponse.json(500, "{\"ok\":false,\"error\":${JsonUtil.quote(e.message ?: e.javaClass.simpleName)}}").withCors()
                    )
                }
            }
        }
    }


    private fun isClientDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is EOFException) return true
            val message = current.message.orEmpty().lowercase(Locale.US)
            val className = current.javaClass.name.lowercase(Locale.US)
            if (
                current is SocketException ||
                current is IOException ||
                className.contains("connectionreset")
            ) {
                if (
                    message.contains("broken pipe") ||
                    message.contains("connection reset") ||
                    message.contains("reset by peer") ||
                    message.contains("socket closed") ||
                    message.contains("socket is closed") ||
                    message.contains("connection abort") ||
                    message.contains("connection aborted") ||
                    message.contains("unexpected end of stream") ||
                    className.contains("connectionreset")
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            return HttpResponse.json(200, "{\"ok\":true}")
        }

        val path = request.path
        return when {
            path.startsWith("/api/") -> {
                if (requiresToken(request) && !isAuthorized(request)) {
                    HttpResponse.json(401, "{\"ok\":false,\"error\":\"WebIDE token 无效或缺失\"}")
                } else {
                    api.serve(request)
                }
            }

            // Vite/Monaco production build emits hashed files under /assets/*.
            // The old route only served /app.js and /app.css, which made
            // /assets/vendor-xxxx.js return 404 even when the file existed in
            // app/src/main/assets/webide/assets/.
            path == "/" || path == "/index.html" || path == "/terminal" || path == "/terminal/" || path == "/logs" || path == "/logs/" -> serveWebIdeAsset("index.html")
            path.startsWith("/assets/") -> serveWebIdeAsset(path.removePrefix("/"))

            // Keep compatibility with the pre-Vite fallback page.
            path == "/app.js" || path == "/webide/app.js" -> serveWebIdeAsset("app.js")
            path == "/app.css" || path == "/webide/app.css" -> serveWebIdeAsset("app.css")
            path == "/favicon.ico" -> HttpResponse.plain(404, "")

            // Allow direct access to any static file inside assets/webide while
            // preventing ../ path traversal. This also helps future Monaco worker
            // chunks or sourcemap-like assets without touching router code again.
            else -> serveWebIdeAsset(path.removePrefix("/"))
        }
    }

    private fun requiresToken(request: HttpRequest): Boolean {
        return !request.method.equals("GET", ignoreCase = true)
            && !request.method.equals("HEAD", ignoreCase = true)
            && !request.method.equals("OPTIONS", ignoreCase = true)
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val header = request.headers["x-xiaoheihook-token"]
        val queryToken = request.param("token")
        return WebIdeSecurity.isValid(appContext, header) || WebIdeSecurity.isValid(appContext, queryToken)
    }

    private fun serveWebIdeAsset(relativePath: String): HttpResponse {
        val safePath = normalizeAssetPath(relativePath)
            ?: return HttpResponse.plain(400, "invalid asset path")
        return try {
            appContext.assets.open("webide/$safePath").use { input ->
                HttpResponse(200, mimeForAsset(safePath), input.readBytes())
            }
        } catch (e: FileNotFoundException) {
            HttpResponse.plain(404, "asset not found: webide/$safePath")
        }
    }

    private fun normalizeAssetPath(path: String): String? {
        val cleaned = path.replace('\\', '/').trimStart('/')
        if (cleaned.isBlank()) return "index.html"
        if (cleaned.split('/').any { it == ".." }) return null
        return cleaned
    }

    private fun mimeForAsset(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html" -> MIME_HTML
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
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
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun setForegroundThreadPriority(kind: String) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }
            .onFailure { Log.w(TAG, "set $kind thread priority failed", it) }
    }

    private fun BufferedInputStream.readAsciiLine(): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = read()
            if (b == -1) {
                return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            }
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
        const val TAG = "XiaoHeiHook-WebIDE"
        const val MIME_HTML = "text/html; charset=utf-8"
    }
}
