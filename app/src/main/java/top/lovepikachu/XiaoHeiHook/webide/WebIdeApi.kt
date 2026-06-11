package top.lovepikachu.XiaoHeiHook.webide

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.AppControl
import top.lovepikachu.XiaoHeiHook.data.AppLogRepository
import top.lovepikachu.XiaoHeiHook.data.AppRepository
import top.lovepikachu.XiaoHeiHook.data.InstalledAppInfo
import top.lovepikachu.XiaoHeiHook.data.LogReceiver
import top.lovepikachu.XiaoHeiHook.data.ScriptMetadata
import top.lovepikachu.XiaoHeiHook.data.ScriptPrefs
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.data.ScriptSettings
import top.lovepikachu.XiaoHeiHook.debug.DebugEventRepository
import top.lovepikachu.XiaoHeiHook.debug.DebugProtocol
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebIdeApi(private val context: Context) {
    private val bridge = WebIdeBridgeClient(context.applicationContext)

    fun serve(request: HttpRequest): HttpResponse {
        return try {
            when (request.path) {
                "/api/status" -> status()
                "/api/logs" -> logs(request)
                "/api/settings/logging" -> loggingSettings(request)

                "/api/debug/events" -> debugEvents(request)
                "/api/debug/enabled" -> debugEnabled(request)
                "/api/debug/start" -> debugStart(request)
                "/api/debug/stop" -> debugStop(request)
                "/api/debug/breakpoints" -> debugBreakpoints(request)
                "/api/debug/continue" -> debugCommand(request, DebugProtocol.COMMAND_CONTINUE)
                "/api/debug/abort" -> debugCommand(request, DebugProtocol.COMMAND_ABORT)
                "/api/debug/set-variable" -> debugCommand(request, DebugProtocol.COMMAND_SET_VARIABLE)
                "/api/debug/eval" -> debugCommand(request, DebugProtocol.COMMAND_EVAL)
                "/api/debug/step-into" -> debugCommand(request, DebugProtocol.COMMAND_STEP_INTO)
                "/api/debug/step-over" -> debugCommand(request, DebugProtocol.COMMAND_STEP_OVER)
                "/api/debug/step-out" -> debugCommand(request, DebugProtocol.COMMAND_STEP_OUT)
                "/api/debug/clear" -> debugClear()

                "/api/apps" -> apps(request)
                "/api/apps/restart" -> restartApp(request)
                "/api/apps/launch" -> launchApp(request)

                "/api/scripts" -> scripts(request)
                "/api/scripts/tree" -> scriptsTree(request)
                "/api/scripts/read" -> readScript(request)
                "/api/scripts/save" -> saveScript(request)
                "/api/scripts/create" -> createScript(request)
                "/api/scripts/delete" -> deleteScript(request)
                "/api/scripts/rename" -> renameScript(request)
                "/api/scripts/sync" -> syncScripts(request)

                "/api/files/list" -> listFiles(request)
                "/api/files/create-file" -> createFile(request)
                "/api/files/create-folder" -> createFolder(request)
                "/api/files/delete" -> deleteFileOrFolder(request)
                "/api/files/rename" -> renameFileOrFolder(request)

                "/api/hook-settings" -> hookSettings(request)
                "/api/hook-settings/app-enabled" -> setAppEnabled(request)
                "/api/hook-settings/script-enabled" -> setScriptEnabled(request)
                "/api/hook-settings/cache-private-dir" -> setCachePrivateDir(request)
                "/api/hook-settings/sync" -> syncHookSettings(request)

                "/api/script-settings" -> scriptSettings(request)
                "/api/script-settings/save" -> saveScriptSettings(request)
                "/api/script-settings/reset" -> resetScriptSettings(request)

                else -> json(404, error("Unsupported API: ${request.path}"))
            }
        } catch (e: Throwable) {
            json(500, error(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun status(): HttpResponse {
        val dir = ScriptRepository.ensurePublicFolderAndSample().getOrNull() ?: ScriptRepository.publicScriptsDir
        val status = WebIdeManager.status.value
        val bridgeStatus = bridge.status()
        return json(
            JSONObject()
                .put("ok", true)
                .put("server", "xhh-webide-debug-isolated-root-scripts")
                .put("running", status.running)
                .put("host", status.host)
                .put("port", status.port)
                .put("baseUrl", status.baseUrl)
                .put("process", ProcessUtil.currentProcessName(context))
                .put("pid", Process.myPid())
                .put("scriptDir", dir.absolutePath)
                .put("hasAllFilesAccess", ScriptRepository.hasAllFilesAccess())
                .put("xposedService", bridgeStatus.xposedServiceReady)
                .put("remotePreferences", bridgeStatus.remotePreferencesReady)
                .put("mainProcess", bridgeStatus.process)
                .put("bridgeOk", bridgeStatus.ok)
                .put("webIdeToken", WebIdeSecurity.token(context))
        )
    }

    private fun loggingSettings(request: HttpRequest): HttpResponse {
        if (request.method.equals("POST", ignoreCase = true)) {
            val body = request.jsonBody()
            val disabled = body.optBoolean("disableFileLogging", false)
            bridge.putBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, disabled)
            return json(
                JSONObject()
                    .put("ok", true)
                    .put("disableFileLogging", disabled)
            )
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("disableFileLogging", bridge.getBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, false))
        )
    }

    fun streamLogs(request: HttpRequest, output: OutputStream, isRunning: () -> Boolean) {
        val packageName = request.param("packageName").orEmpty().trim()
        val out = java.io.BufferedOutputStream(output)
        val liveQueue = LinkedBlockingQueue<String>()
        var receiverRegistered = false
        val liveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != LogReceiver.ACTION_LOG_EVENT_LIVE) return
                val eventPackage = intent.getStringExtra(LogReceiver.EXTRA_PACKAGE_NAME).orEmpty()
                if (eventPackage != packageName) return
                val line = intent.getStringExtra(LogReceiver.EXTRA_LINE).orEmpty()
                if (line.isNotBlank()) liveQueue.offer(line)
            }
        }

        fun writeRaw(text: String) {
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        fun writeEvent(event: String, obj: JSONObject) {
            val payload = buildString {
                append("event: ").append(event).append("\n")
                append("data: ").append(obj.toString()).append("\n\n")
            }
            writeRaw(payload)
        }

        writeRaw(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "\r\n"
        )

        if (packageName.isBlank()) {
            writeEvent("error", JSONObject().put("ok", false).put("error", "packageName 不能为空"))
            return
        }

        val filter = IntentFilter(LogReceiver.ACTION_LOG_EVENT_LIVE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(liveReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(liveReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { error ->
            writeEvent("error", JSONObject().put("ok", false).put("error", "实时日志接收器注册失败：${error.message ?: error.javaClass.simpleName}"))
        }

        try {
            val initial = AppLogRepository.readLog(context, packageName, maxLines = 200).getOrDefault("")
            initial.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    writeEvent("log", JSONObject().put("ok", true).put("packageName", packageName).put("line", line))
                }

            var tick = 0
            while (isRunning()) {
                val firstLine = liveQueue.poll(1, TimeUnit.SECONDS)
                if (firstLine != null) {
                    writeEvent("log", JSONObject().put("ok", true).put("packageName", packageName).put("line", firstLine))
                    while (true) {
                        val line = liveQueue.poll() ?: break
                        writeEvent("log", JSONObject().put("ok", true).put("packageName", packageName).put("line", line))
                    }
                }

                tick++
                if (tick % 15 == 0) {
                    writeRaw(": keep-alive\n\n")
                }
            }
        } finally {
            if (receiverRegistered) {
                runCatching { context.unregisterReceiver(liveReceiver) }
            }
        }
    }

    fun streamDebug(request: HttpRequest, output: OutputStream, isRunning: () -> Boolean) {
        val packageName = request.param("packageName").orEmpty().trim().ifBlank { null }
        val out = java.io.BufferedOutputStream(output)
        fun writeRaw(text: String) {
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        fun writeEvent(name: String, obj: JSONObject) {
            writeRaw("event: $name\n" + "data: ${obj.toString()}\n\n")
        }

        writeRaw(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "\r\n"
        )

        // On a fresh page load / SSE reconnect, only replay currently active pauses.
        // Replaying the full history would resurrect already-continued paused events
        // as "ghost breakpoints" in the WebIDE.
        DebugEventRepository.readActivePaused(context, packageName = packageName).forEach { obj ->
            writeEvent(obj.optString("type", "debug"), obj)
        }

        val file = DebugEventRepository.eventFile(context)
        var offset = if (file.exists()) file.length() else 0L
        var tick = 0
        while (isRunning()) {
            if (file.exists()) {
                val length = file.length()
                if (length < offset) offset = 0L
                if (length > offset) {
                    val bytesToRead = (length - offset).coerceAtMost(256L * 1024L).toInt()
                    val bytes = ByteArray(bytesToRead)
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset)
                        raf.readFully(bytes)
                    }
                    offset += bytesToRead
                    String(bytes, StandardCharsets.UTF_8)
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                        .filter { obj -> packageName.isNullOrBlank() || obj.optString("packageName") == packageName }
                        .forEach { obj -> writeEvent(obj.optString("type", "debug"), obj) }
                }
            }
            tick++
            if (tick % 15 == 0) writeRaw(": keep-alive\n\n")
            Thread.sleep(1000)
        }
    }

    private fun debugEvents(request: HttpRequest): HttpResponse {
        val packageName = request.param("packageName").orEmpty().trim().ifBlank { null }
        val maxLines = request.param("maxLines")?.toIntOrNull()?.coerceIn(1, 2000) ?: 300
        val activeOnly = request.param("active")?.toBooleanStrictOrNullCompat() ?: true
        val events = if (activeOnly) {
            DebugEventRepository.readActivePaused(context, packageName = packageName)
        } else {
            DebugEventRepository.readRecent(context, maxLines, packageName)
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("activeOnly", activeOnly)
                .put("events", JSONArray(events))
                .put("activePaused", DebugEventRepository.activePausedArray(context, packageName))
                .put("count", events.size)
        )
    }

    private fun debugEnabled(request: HttpRequest): HttpResponse {
        if (request.method == "GET") {
            val packageName = request.param("packageName").orEmpty().trim()
            require(packageName.isNotBlank()) { "packageName 不能为空" }
            return json(
                JSONObject()
                    .put("ok", true)
                    .put("packageName", packageName)
                    .put("enabled", bridge.debugEnabled(packageName))
            )
        }
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val enabled = body.optBoolean("enabled", false)
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        return json(bridge.setDebugEnabled(packageName, enabled))
    }


    private fun debugStart(request: HttpRequest): HttpResponse {
        val body = request.jsonBody(required = false)
        val packageName = body.optString("packageName").trim()
        val restart = body.optBoolean("restart", true)
        val launch = body.optBoolean("launch", true)
        require(packageName.isNotBlank()) { "packageName 不能为空" }

        val enabled = bridge.setDebugEnabled(packageName, true)
        val sync = bridge.syncScripts(packageName)
        val extra = JSONObject().put("debugEnabled", enabled)
        if (restart) {
            val restartResult = AppControl.restartPackage(context, packageName, launch = launch, appendLog = true)
            extra.put("forceStop", if (restartResult.forceStopOk) "ok" else restartResult.forceStopMessage.orEmpty())
            if (launch) {
                extra.put("launch", if (restartResult.launchOk == true) "ok" else restartResult.launchMessage.orEmpty())
            }
            extra.put("restart", restartResultJson(restartResult))
        }
        return json(JSONObject()
            .put("ok", true)
            .put("packageName", packageName)
            .put("debug", true)
            .put("sync", sync)
            .put("extra", extra))
    }

    private fun debugStop(request: HttpRequest): HttpResponse {
        val body = request.jsonBody(required = false)
        val packageName = body.optString("packageName").trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val disabled = bridge.setDebugEnabled(packageName, false)
        DebugEventRepository.clear(context)
        val terminateResult = AppControl.terminatePackage(context, packageName, appendLog = true)
        return json(JSONObject()
            .put("ok", true)
            .put("packageName", packageName)
            .put("debug", false)
            .put("debugDisabled", disabled)
            .put("terminated", terminateResult.forceStopOk)
            .put("terminate", restartResultJson(terminateResult)))
    }

    private fun debugBreakpoints(request: HttpRequest): HttpResponse {
        if (request.method == "GET") {
            val packageName = request.param("packageName").orEmpty().trim()
            val scriptPath = request.param("scriptPath").orEmpty().trim()
            require(packageName.isNotBlank()) { "packageName 不能为空" }
            val root = bridge.getDebugBreakpoints(packageName)
            val lines = if (scriptPath.isNotBlank()) root.optJSONArray(scriptPath) ?: JSONArray() else JSONArray()
            return json(
                JSONObject()
                    .put("ok", true)
                    .put("packageName", packageName)
                    .put("scriptPath", if (scriptPath.isBlank()) JSONObject.NULL else scriptPath)
                    .put("lines", lines)
                    .put("breakpoints", root)
            )
        }
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val scriptPath = body.optString("scriptPath").trim()
        val lines = body.optJSONArray("lines") ?: JSONArray()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        require(scriptPath.isNotBlank()) { "scriptPath 不能为空" }
        return json(bridge.setDebugBreakpoints(packageName, scriptPath, lines))
    }

    private fun debugCommand(request: HttpRequest, command: String): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val pauseId = body.optString("pauseId").trim()
        val processName = body.optString("processName").trim()
        val expression = body.optString("expression").trim()
        val payload = body.optJSONObject("payload") ?: JSONObject()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        require(pauseId.isNotBlank()) { "pauseId 不能为空" }

        // Remove the paused item from the active table immediately after the
        // WebIDE accepts Continue/Abort. The target process will later emit the
        // final continued/aborted event, but a browser refresh must not show the
        // old paused event again while that round trip is in progress.
        val lifecycleEvent = if (command == DebugProtocol.COMMAND_CONTINUE
            || command == DebugProtocol.COMMAND_ABORT
            || command == DebugProtocol.COMMAND_STEP_INTO
            || command == DebugProtocol.COMMAND_STEP_OVER
            || command == DebugProtocol.COMMAND_STEP_OUT
        ) {
            DebugEventRepository.markCommanded(context, packageName, processName, pauseId, command)
        } else null

        var bridgeOk = false
        var bridgeError: String? = null
        runCatching {
            bridge.sendDebugCommand(packageName, processName, pauseId, command, expression, payload)
            bridgeOk = true
        }.onFailure { bridgeError = it.message ?: it.javaClass.simpleName }

        // Do not broadcast debug commands into the target process.
        // Target processes poll LSPosed Remote Preferences by pauseId.
        // This avoids dynamic receiver registration / package identity issues on newer Android.

        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("processName", processName)
                .put("pauseId", pauseId)
                .put("command", command)
                .put("bridgeCommand", bridgeOk)
                .put("broadcastFallback", false)
                .put("commandTransport", "remote-preferences")
                .put("payload", payload)
                .put("bridgeError", bridgeError ?: JSONObject.NULL)
                .put("lifecycleEvent", lifecycleEvent ?: JSONObject.NULL)
        )
    }

    private fun debugClear(): HttpResponse {
        DebugEventRepository.clear(context)
        return json(JSONObject().put("ok", true))
    }

    private fun logs(request: HttpRequest): HttpResponse {
        val packageName = request.param("packageName").orEmpty().trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val maxLines = request.param("maxLines")?.toIntOrNull()?.coerceIn(1, 2000) ?: 800
        val text = AppLogRepository.readLog(context, packageName, maxLines).getOrThrow()
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("disableFileLogging", bridge.getBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, false))
                .put("text", text)
                .put("lines", JSONArray(text.lineSequence().toList()))
        )
    }

    private fun apps(request: HttpRequest): HttpResponse {
        val query = request.param("query").orEmpty().trim()
        val showSystem = request.param("showSystem")?.toBooleanStrictOrNullCompat() ?: false
        val force = request.param("force")?.toBooleanStrictOrNullCompat() ?: false
        val enabledCache = HashMap<String, Boolean>()
        fun enabledOf(pkg: String): Boolean = enabledCache.getOrPut(pkg) { bridge.appEnabled(pkg) }
        val apps = AppRepository.loadInstalledApps(context, forceRefresh = force)
            .asSequence()
            .filter { showSystem || !it.isSystemApp }
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> { enabledOf(it.packageName) }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.label.lowercase() }
            )
            .map { app -> app.toJson(enabledOf(app.packageName)) }
            .toList()
        return json(JSONObject().put("ok", true).put("apps", JSONArray(apps)).put("count", apps.size))
    }

    private fun hookSettings(request: HttpRequest): HttpResponse {
        val packageName = request.param("packageName").orEmpty().trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val app = AppRepository.loadInstalledApps(context)
            .firstOrNull { it.packageName == packageName }
        val matchedScripts = loadScriptEntries(packageName)
        val bridgeStatus = bridge.status()
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("label", app?.label ?: packageName)
                .put("system", app?.isSystemApp ?: false)
                .put("appEnabled", bridge.appEnabled(packageName))
                .put("cacheScriptsToPrivateDir", bridge.getBoolean(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName), true))
                .put("targetScriptCacheDir", ScriptPrefs.normalizeTargetScriptCacheDir(bridge.getString(ScriptPrefs.targetScriptCacheDirKey(packageName), ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR)))
                .put("defaultTargetScriptCacheDir", ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR)
                .put("serviceReady", bridgeStatus.xposedServiceReady)
                .put("remotePrefsReady", bridgeStatus.remotePreferencesReady)
                .put("scripts", JSONArray(matchedScripts.map { entry ->
                    metadataJson(entry.metadata)
                        .put("path", entry.path)
                        .put("enabled", bridge.scriptEnabled(packageName, entry.metadata.id))
                }))
        )
    }

    private fun setAppEnabled(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val enabled = body.optBoolean("enabled")
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        return json(bridge.setAppEnabled(packageName, enabled))
    }

    private fun setScriptEnabled(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val scriptId = body.optString("scriptId").trim()
        val enabled = body.optBoolean("enabled")
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        require(scriptId.isNotBlank()) { "scriptId 不能为空" }
        return json(bridge.setScriptEnabled(packageName, scriptId, enabled))
    }

    private fun setCachePrivateDir(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val enabled = body.optBoolean("enabled")
        val targetDir = ScriptPrefs.normalizeTargetScriptCacheDir(
            body.optString("targetScriptCacheDir", ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR)
        )
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        bridge.putBoolean(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName), enabled)
        bridge.putString(ScriptPrefs.targetScriptCacheDirKey(packageName), targetDir)
        val syncResult = runCatching { bridge.syncScripts(packageName) }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("cacheScriptsToPrivateDir", enabled)
                .put("targetScriptCacheDir", targetDir)
                .put("sync", syncResult)
        )
    }

    private fun syncHookSettings(request: HttpRequest): HttpResponse {
        val body = request.jsonBody(required = false)
        val packageName = body.optString("packageName").ifBlank { null }
        val restart = body.optBoolean("restart", false)
        val launch = body.optBoolean("launch", false)
        val debug = body.optBoolean("debug", false)
        val extra = JSONObject()
        if (!packageName.isNullOrBlank() && !debug) {
            // 普通同步/运行必须关闭 WebIDE 调试模式，避免手机端或普通运行进入 Rhino 行调试慢路径。
            extra.put("debugDisabled", bridge.setDebugEnabled(packageName, false))
        }
        val obj = bridge.syncScripts(packageName)
        if (!packageName.isNullOrBlank() && restart) {
            val restartResult = AppControl.restartPackage(context, packageName, launch = launch, appendLog = true)
            extra.put("forceStop", if (restartResult.forceStopOk) "ok" else restartResult.forceStopMessage.orEmpty())
            if (launch) {
                extra.put("launch", if (restartResult.launchOk == true) "ok" else restartResult.launchMessage.orEmpty())
            }
            extra.put("restart", restartResultJson(restartResult))
        }
        obj.put("extra", extra)
        return json(obj)
    }


    private fun scriptSettings(request: HttpRequest): HttpResponse {
        val packageName = request.param("packageName").orEmpty().trim()
        val scriptId = request.param("scriptId").orEmpty().trim()
        val scriptPath = request.param("scriptPath").orEmpty().trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val entry = findSettingsScript(scriptId, scriptPath)
        val schema = settingsSchemaOf(entry)
        val key = ScriptPrefs.scriptSettingsKey(packageName, entry.metadata.id)
        val savedRaw = bridge.getString(key, "{}")
        val merged = ScriptSettings.merge(schema, savedRaw)
        val saved = runCatching { JSONObject(savedRaw) }.getOrElse { JSONObject() }
        val savedValues = saved.optJSONObject("values") ?: JSONObject()
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("scriptId", entry.metadata.id)
                .put("scriptPath", entry.path)
                .put("storageKey", key)
                .put("schema", schema)
                .put("values", savedValues)
                .put("mergedValues", merged)
        )
    }

    private fun saveScriptSettings(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val scriptId = body.optString("scriptId").trim()
        val scriptPath = body.optString("scriptPath").trim()
        val values = body.optJSONObject("values") ?: JSONObject()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val entry = findSettingsScript(scriptId, scriptPath)
        val schema = settingsSchemaOf(entry)
        val cleanValues = ScriptSettings.normalizeValues(schema, values, strict = true)
        val doc = ScriptSettings.savedDocument(packageName, entry.metadata.id, entry.path, schema, cleanValues)
        val key = ScriptPrefs.scriptSettingsKey(packageName, entry.metadata.id)
        bridge.putString(key, doc.toString())
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("scriptId", entry.metadata.id)
                .put("scriptPath", entry.path)
                .put("storageKey", key)
                .put("values", cleanValues)
                .put("mergedValues", ScriptSettings.merge(schema, doc.toString()))
        )
    }

    private fun resetScriptSettings(request: HttpRequest): HttpResponse {
        val body = request.jsonBody(required = false)
        val packageName = body.optString("packageName").ifBlank { request.param("packageName").orEmpty() }.trim()
        val scriptId = body.optString("scriptId").ifBlank { request.param("scriptId").orEmpty() }.trim()
        val scriptPath = body.optString("scriptPath").ifBlank { request.param("scriptPath").orEmpty() }.trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val entry = findSettingsScript(scriptId, scriptPath)
        val schema = settingsSchemaOf(entry)
        val key = ScriptPrefs.scriptSettingsKey(packageName, entry.metadata.id)
        bridge.remove(key)
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("scriptId", entry.metadata.id)
                .put("scriptPath", entry.path)
                .put("storageKey", key)
                .put("mergedValues", ScriptSettings.defaults(schema))
        )
    }

    private fun restartApp(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        val launch = body.optBoolean("launch", true)
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        val restartResult = AppControl.restartPackage(context, packageName, launch = launch, appendLog = true)
        return json(
            JSONObject()
                .put("ok", true)
                .put("packageName", packageName)
                .put("launch", launch)
                .put("restart", restartResultJson(restartResult))
        )
    }

    private fun restartResultJson(result: AppControl.RestartResult): JSONObject {
        return JSONObject()
            .put("forceStopOk", result.forceStopOk)
            .put("forceStopMessage", result.forceStopMessage ?: "")
            .put("needsManualRestart", result.needsManualRestart)
            .put("launchRequested", result.launchRequested)
            .put("launchOk", result.launchOk ?: JSONObject.NULL)
            .put("launchMessage", result.launchMessage ?: "")
            .put("launchMode", result.launchMode ?: JSONObject.NULL)
            .put("rootLaunchOk", result.rootLaunchOk ?: JSONObject.NULL)
            .put("rootLaunchMessage", result.rootLaunchMessage ?: "")
    }

    private fun launchApp(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val packageName = body.optString("packageName").trim()
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        return AppControl.launchPackage(context, packageName).fold(
            onSuccess = { json(JSONObject().put("ok", true).put("packageName", packageName)) },
            onFailure = { json(500, error(it.message ?: it.javaClass.simpleName)) }
        )
    }

    private fun scripts(request: HttpRequest): HttpResponse {
        val packageName = request.param("packageName").orEmpty().trim().ifBlank { null }
        val entries = loadScriptEntries(packageName)
        val array = entries.map { entry ->
            metadataJson(entry.metadata)
                .put("path", entry.path)
                .put("size", entry.file.length())
                .put("lastModified", entry.file.lastModified())
                .apply {
                    if (!packageName.isNullOrBlank()) {
                        put("enabled", bridge.scriptEnabled(packageName, entry.metadata.id))
                    }
                }
        }
        return json(JSONObject().put("ok", true).put("scripts", JSONArray(array)).put("count", array.size))
    }


    private fun scriptsTree(request: HttpRequest): HttpResponse {
        ScriptRepository.ensurePublicFolderAndSample()
        val root = ScriptRepository.publicScriptsDir
        fun jsFileNode(file: File): JSONObject {
            val path = relativePath(file)
            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrDefault("")
            val metadata = if (file.name.equals("index.js", ignoreCase = true)) {
                val rootPath = path.substringBeforeLast("/index.js", "")
                val schema = loadSettingsSchema(rootPath)
                ScriptRepository.parseMetadata(text, file.nameWithoutExtension).withWebIdePath(path).copy(
                    hasSettings = schema != null,
                    settingsPath = if (schema != null && rootPath.isNotBlank()) "$rootPath/settings.json" else "",
                    settingsSchema = schema?.toString() ?: ""
                )
            } else {
                null
            }
            return JSONObject()
                .put("type", "file")
                .put("name", file.name)
                .put("path", path)
                .put("script", path.endsWith("/index.js", ignoreCase = true) || file.parentFile?.canonicalFile == root.canonicalFile)
                .put("entry", path.endsWith("/index.js", ignoreCase = true))
                .apply { if (metadata != null) put("metadata", metadataJson(metadata)) }
        }

        fun directoryNode(dir: File): JSONObject {
            val children = JSONArray()
            dir.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { child ->
                    if (child.isDirectory) {
                        val nested = directoryNode(child)
                        if (nested.optJSONArray("children")?.length() ?: 0 > 0) children.put(nested)
                    } else if (child.isFile && child.extension.equals("js", ignoreCase = true)) {
                        children.put(jsFileNode(child))
                    }
                }
            return JSONObject()
                .put("type", "directory")
                .put("name", dir.name)
                .put("path", relativePath(dir))
                .put("script", File(dir, "index.js").isFile)
                .put("children", children)
        }

        val children = JSONArray()
        if (root.exists()) {
            root.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { entry ->
                    if (entry.isDirectory) {
                        val node = directoryNode(entry)
                        if (node.optJSONArray("children")?.length() ?: 0 > 0) children.put(node)
                    } else if (entry.isFile && entry.extension.equals("js", ignoreCase = true)) {
                        children.put(jsFileNode(entry))
                    }
                }
        }
        return json(JSONObject().put("ok", true).put("root", JSONObject().put("type", "directory").put("name", "XiaoHeiHook").put("path", "").put("children", children)))
    }

    private fun readScript(request: HttpRequest): HttpResponse {
        val file = resolveScriptFile(request.param("path"), mustExist = true)
        val content = file.readText(StandardCharsets.UTF_8)
        val path = relativePath(file)
        val kind = if (path.endsWith("/index.js", ignoreCase = true)) "directory" else "file"
        val rootPath = if (kind == "directory") path.substringBeforeLast("/index.js") else ""
        val schema = loadSettingsSchema(rootPath)
        val metadata = ScriptRepository.parseMetadata(content, file.nameWithoutExtension).withWebIdePath(path).copy(
            hasSettings = schema != null,
            settingsPath = if (schema != null && rootPath.isNotBlank()) "$rootPath/settings.json" else "",
            settingsSchema = schema?.toString() ?: ""
        )
        return json(
            JSONObject()
                .put("ok", true)
                .put("path", path)
                .put("metadata", metadataJson(metadata))
                .put("content", content)
        )
    }

    private fun saveScript(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val file = resolveScriptFile(body.optString("path"), mustExist = false)
        val content = body.optString("content", "")
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        val path = relativePath(file)
        val kind = if (path.endsWith("/index.js", ignoreCase = true)) "directory" else "file"
        val rootPath = if (kind == "directory") path.substringBeforeLast("/index.js") else ""
        val schema = loadSettingsSchema(rootPath)
        val metadata = ScriptRepository.parseMetadata(content, file.nameWithoutExtension).withWebIdePath(path).copy(
            hasSettings = schema != null,
            settingsPath = if (schema != null && rootPath.isNotBlank()) "$rootPath/settings.json" else "",
            settingsSchema = schema?.toString() ?: ""
        )
        return json(
            JSONObject()
                .put("ok", true)
                .put("path", path)
                .put("size", file.length())
                .put("metadata", metadataJson(metadata))
        )
    }

    private fun createScript(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val rawPath = body.optString("path").ifBlank { "new_script.js" }
        val target = body.optString("target").trim().ifBlank { "*" }
        val file = resolveScriptFile(ensureJsExtension(rawPath), mustExist = false)
        if (file.exists()) {
            return json(409, error("脚本已存在：${relativePath(file)}"))
        }
        file.parentFile?.mkdirs()
        val name = file.nameWithoutExtension.ifBlank { "new_script" }
        val template = body.optString("content").ifBlank { defaultScriptTemplate(name, target) }
        file.writeText(template, StandardCharsets.UTF_8)
        return json(JSONObject().put("ok", true).put("path", relativePath(file)).put("content", template))
    }

    private fun deleteScript(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val file = resolveScriptFile(body.optString("path"), mustExist = true)
        if (!file.delete()) {
            return json(500, error("删除失败：${relativePath(file)}"))
        }
        return json(JSONObject().put("ok", true))
    }

    private fun renameScript(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val from = resolveScriptFile(body.optString("from"), mustExist = true)
        val to = resolveScriptFile(ensureJsExtension(body.optString("to")), mustExist = false)
        if (to.exists()) {
            return json(409, error("目标已存在：${relativePath(to)}"))
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            return json(500, error("重命名失败"))
        }
        return json(JSONObject().put("ok", true).put("path", relativePath(to)))
    }

    private fun syncScripts(request: HttpRequest): HttpResponse {
        val body = request.jsonBody(required = false)
        val packageName = body.optString("packageName").ifBlank { null }
        return json(bridge.syncScripts(packageName))
    }


    private fun listFiles(request: HttpRequest): HttpResponse {
        ScriptRepository.ensurePublicFolderAndSample()
        val dirPath = request.param("dir").orEmpty()
        val root = ScriptRepository.publicScriptsDir.canonicalFile
        val dir = if (dirPath.isBlank()) root else SafeScriptPath.resolve(root, dirPath, mustExist = true, expectDirectory = true)
        require(dir.isDirectory) { "目录不存在：$dirPath" }
        val entries = JSONArray()
        dir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { file -> entries.put(fileEntryJson(root, file)) }
        val relative = if (dir == root) "" else dir.relativeTo(root).path.replace(File.separatorChar, '/')
        val parent = relative.substringBeforeLast('/', "")
        return json(
            JSONObject()
                .put("ok", true)
                .put("dir", relative)
                .put("parent", parent)
                .put("entries", entries)
        )
    }

    private fun createFile(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val root = ScriptRepository.ensurePublicFolderAndSample().getOrThrow().canonicalFile
        val path = ensureAllowedFileExtension(body.optString("path"))
        val file = SafeScriptPath.resolve(root, path, mustExist = false, expectDirectory = false)
        if (file.exists()) return json(409, error("文件已存在：${relativePath(file)}"))
        file.parentFile?.mkdirs()
        file.writeText(body.optString("content", ""), StandardCharsets.UTF_8)
        return json(JSONObject().put("ok", true).put("path", relativePath(file)).put("entry", fileEntryJson(root, file)))
    }

    private fun createFolder(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val root = ScriptRepository.ensurePublicFolderAndSample().getOrThrow().canonicalFile
        val dir = SafeScriptPath.resolve(root, body.optString("path"), mustExist = false, expectDirectory = true)
        if (dir.exists()) return json(409, error("文件夹已存在：${relativePath(dir)}"))
        if (!dir.mkdirs()) return json(500, error("创建文件夹失败：${relativePath(dir)}"))
        return json(JSONObject().put("ok", true).put("path", relativePath(dir)).put("entry", fileEntryJson(root, dir)))
    }

    private fun deleteFileOrFolder(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val root = ScriptRepository.ensurePublicFolderAndSample().getOrThrow().canonicalFile
        val target = SafeScriptPath.resolve(root, body.optString("path"), mustExist = true)
        val recursive = body.optBoolean("recursive", false)
        if (target.isDirectory) {
            if (!recursive && target.listFiles()?.isNotEmpty() == true) {
                return json(409, error("文件夹非空，需要 recursive=true"))
            }
            if (!target.deleteRecursively()) return json(500, error("删除文件夹失败：${relativePath(target)}"))
        } else if (!target.delete()) {
            return json(500, error("删除文件失败：${relativePath(target)}"))
        }
        return json(JSONObject().put("ok", true))
    }

    private fun renameFileOrFolder(request: HttpRequest): HttpResponse {
        val body = request.jsonBody()
        val root = ScriptRepository.ensurePublicFolderAndSample().getOrThrow().canonicalFile
        val from = SafeScriptPath.resolve(root, body.optString("from"), mustExist = true)
        val to = SafeScriptPath.resolve(root, body.optString("to"), mustExist = false, expectDirectory = from.isDirectory)
        if (to.exists()) return json(409, error("目标已存在：${relativePath(to)}"))
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) return json(500, error("重命名失败"))
        return json(JSONObject().put("ok", true).put("path", relativePath(to)).put("type", if (to.isDirectory) "directory" else "file"))
    }

    private fun fileEntryJson(root: File, file: File): JSONObject {
        val path = if (file == root) "" else file.relativeTo(root).path.replace(File.separatorChar, '/')
        val isDir = file.isDirectory
        val ext = if (isDir) "" else file.extension.lowercase()
        val role = when {
            isDir -> ""
            !ext.equals("js", ignoreCase = true) -> ""
            path.endsWith("/index.js", ignoreCase = true) -> "entry"
            !path.contains("/") -> "script"
            else -> "dependency"
        }
        return JSONObject()
            .put("type", if (isDir) "directory" else "file")
            .put("name", file.name)
            .put("path", path)
            .put("extension", ext)
            .put("size", if (isDir) JSONObject.NULL else file.length())
            .put("modifiedAt", file.lastModified())
            .put("scriptRole", role)
            .put("hasIndex", if (isDir) File(file, "index.js").isFile else false)
    }

    private fun ensureAllowedFileExtension(path: String): String {
        val clean = path.trim()
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "$clean.js"
        require(ext in setOf("js", "json", "md", "txt")) { "不允许的文件类型：.$ext" }
        return clean
    }


    private fun findSettingsScript(scriptId: String, scriptPath: String): ScriptEntry {
        val entries = loadScriptEntries(null)
        val entry = when {
            scriptId.isNotBlank() -> entries.firstOrNull { it.metadata.id == scriptId }
            scriptPath.isNotBlank() -> entries.firstOrNull { it.path == scriptPath }
            else -> null
        } ?: throw IllegalArgumentException("找不到脚本设置项：${scriptId.ifBlank { scriptPath }}")
        require(entry.metadata.hasSettings) { "该脚本没有 settings.json：${entry.path}" }
        return entry
    }

    private fun settingsSchemaOf(entry: ScriptEntry): JSONObject {
        return ScriptSettings.normalizeSchema(entry.metadata.settingsSchema)
            ?: throw IllegalArgumentException("settings.json 无效或未声明 fields：${entry.path}")
    }

    private fun loadSettingsSchema(rootPath: String): JSONObject? {
        if (rootPath.isBlank()) return null
        val file = File(ScriptRepository.publicScriptsDir, "$rootPath/settings.json")
        val raw = runCatching { if (file.isFile) file.readText(StandardCharsets.UTF_8) else "" }.getOrDefault("")
        if (raw.isBlank()) return null
        return ScriptSettings.normalizeSchema(raw)
    }

    private data class ScriptEntry(
        val path: String,
        val file: File,
        val metadata: ScriptMetadata
    )

    private fun loadScriptEntries(packageName: String?): List<ScriptEntry> {
        ScriptRepository.ensurePublicFolderAndSample()
        val dir = ScriptRepository.publicScriptsDir
        if (!dir.exists()) return emptyList()
        val result = mutableListOf<ScriptEntry>()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { entry ->
            val file = when {
                entry.isFile && entry.extension.equals("js", ignoreCase = true) -> entry
                entry.isDirectory && File(entry, "index.js").isFile -> File(entry, "index.js")
                else -> null
            } ?: return@forEach
            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
            val path = file.relativeTo(dir).path.replace(File.separatorChar, '/')
            val kind = if (path.endsWith("/index.js", ignoreCase = true)) "directory" else "file"
            val rootPath = if (kind == "directory") path.substringBeforeLast("/index.js") else ""
            val settingsSchema = loadSettingsSchema(rootPath)
            val metadata = ScriptRepository.parseMetadata(text, file.nameWithoutExtension).copy(
                path = path,
                kind = kind,
                entryPath = path,
                rootPath = rootPath,
                hasSettings = settingsSchema != null,
                settingsPath = if (settingsSchema != null && rootPath.isNotBlank()) "$rootPath/settings.json" else "",
                settingsSchema = settingsSchema?.toString() ?: ""
            )
            if (!packageName.isNullOrBlank() && !metadata.supportsPackage(packageName)) return@forEach
            result += ScriptEntry(path, file, metadata)
        }
        return result.sortedWith(compareBy<ScriptEntry> { it.metadata.name.lowercase() }.thenBy { it.path.lowercase() })
    }

    private fun InstalledAppInfo.toJson(enabled: Boolean): JSONObject {
        return JSONObject()
            .put("label", label)
            .put("packageName", packageName)
            .put("system", isSystemApp)
            .put("enabled", enabled)
    }

    private fun metadataJson(metadata: ScriptMetadata): JSONObject {
        return JSONObject()
            .put("id", metadata.id)
            .put("name", metadata.name)
            .put("version", metadata.version)
            .put("author", metadata.author)
            .put("description", metadata.description)
            .put("targets", JSONArray(metadata.targets))
            .put("processes", JSONArray(metadata.processes))
            .put("runAt", metadata.runAt)
            .put("grants", JSONArray(metadata.grants))
            .put("remoteName", metadata.remoteName)
            .put("path", metadata.path)
            .put("scriptPath", metadata.path)
            .put("kind", metadata.kind)
            .put("entryPath", metadata.entryPath.ifBlank { metadata.path })
            .put("rootPath", metadata.rootPath)
            .put("hasSettings", metadata.hasSettings)
            .put("settingsPath", metadata.settingsPath)
            .put("settingsSchema", if (metadata.settingsSchema.isNotBlank()) JSONObject(metadata.settingsSchema) else JSONObject.NULL)
    }


    private fun ScriptMetadata.withWebIdePath(path: String): ScriptMetadata {
        val kind = if (path.endsWith("/index.js", ignoreCase = true)) "directory" else "file"
        val rootPath = if (kind == "directory") path.substringBeforeLast("/index.js") else ""
        return copy(path = path, kind = kind, entryPath = path, rootPath = rootPath)
    }

    private fun resolveScriptFile(rawPath: String?, mustExist: Boolean): File {
        val decoded = decode(rawPath.orEmpty()).trim().replace('\\', '/')
        require(decoded.isNotBlank()) { "path 不能为空" }
        require(!decoded.startsWith("/") && !decoded.contains("..")) { "非法脚本路径：$decoded" }
        require(decoded.endsWith(".js", ignoreCase = true)) { "只允许访问 .js 脚本" }

        val root = ScriptRepository.ensurePublicFolderAndSample().getOrThrow().canonicalFile
        val target = File(root, decoded).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "脚本路径越界：$decoded"
        }
        if (mustExist) {
            require(target.exists() && target.isFile) { "脚本不存在：$decoded" }
        }
        return target
    }

    private fun relativePath(file: File): String {
        return file.relativeTo(ScriptRepository.publicScriptsDir).path.replace(File.separatorChar, '/')
    }

    private fun ensureJsExtension(path: String): String {
        val trimmed = path.trim().ifBlank { "new_script" }
        return if (trimmed.endsWith(".js", ignoreCase = true)) trimmed else "$trimmed.js"
    }

    private fun defaultScriptTemplate(name: String, targetPackage: String): String {
        val id = name.replace(Regex("[^A-Za-z0-9_.-]"), ".").trim('.').ifBlank { "new.script" }
        return """
// ==LSPosedScript==
// @name         $name
// @id           $id
// @version      1.0.0
// @author       XiaoHeiHook
// @description  WebIDE 创建的脚本
// @target       $targetPackage
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "$name";

xposed.onPackageLoaded(function (param) {
  console.log("loaded", param.getPackageName(), env.processName);
});
""".trimIndent()
    }

    private fun HttpRequest.jsonBody(required: Boolean = true): JSONObject {
        val text = bodyText.trim()
        if (text.isBlank()) {
            if (required) throw IllegalArgumentException("请求体不能为空")
            return JSONObject()
        }
        return JSONObject(text)
    }

    private fun json(obj: JSONObject): HttpResponse = json(200, obj)
    private fun json(status: Int, obj: JSONObject): HttpResponse = HttpResponse.json(status, obj.toString())
    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun String.toBooleanStrictOrNullCompat(): Boolean? = when (trim().lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }
}

data class HttpRequest(
    val method: String,
    val rawTarget: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val path: String
    val query: Map<String, List<String>>
    val bodyText: String get() = body.toString(StandardCharsets.UTF_8)

    init {
        val idx = rawTarget.indexOf('?')
        path = if (idx >= 0) rawTarget.substring(0, idx) else rawTarget
        val rawQuery = if (idx >= 0) rawTarget.substring(idx + 1) else ""
        query = parseQuery(rawQuery)
    }

    fun param(name: String): String? = query[name]?.firstOrNull()

    private fun parseQuery(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        val map = linkedMapOf<String, MutableList<String>>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            val decodedKey = URLDecoder.decode(key, "UTF-8")
            val decodedValue = URLDecoder.decode(value, "UTF-8")
            map.getOrPut(decodedKey) { mutableListOf() }.add(decodedValue)
        }
        return map
    }
}

data class HttpResponse(
    val status: Int,
    val mimeType: String,
    val body: ByteArray,
    val headers: MutableMap<String, String> = linkedMapOf()
) {
    fun withCors(): HttpResponse {
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-XiaoHeiHook-Token, Mcp-Session-Id"
        return this
    }

    companion object {
        fun json(status: Int, body: String): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

        fun plain(status: Int, body: String): HttpResponse =
            HttpResponse(status, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))
    }
}

object JsonUtil {
    fun quote(value: String): String = JSONObject.quote(value)
}

object ProcessUtil {
    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return android.app.Application.getProcessName()
        }
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val name = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return name ?: context.packageName
    }
}
