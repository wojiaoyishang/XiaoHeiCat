package top.lovepikachu.XiaoHeiHook.data

import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.service.XposedService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ScriptRepository {
    private const val TAG = "XiaoHeiHook-Scripts"
    private const val HEADER_BEGIN = "// ==LSPosedScript=="
    private const val HEADER_END = "// ==/LSPosedScript=="

    val publicScriptsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "XiaoHeiHook"
        )

    val sampleScriptFile: File
        get() = File(publicScriptsDir, "application_on_create_log.js")

    val sampleScript: String = """
// ==LSPosedScript==
// @name         Application.onCreate 现代链式日志模板
// @id           sample.application.oncreate.modern.log
// @version      2.0.0
// @author       XiaoHeiHook
// @description  使用 libxposed 风格链式 API 记录 Application.onCreate，不修改应用行为。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        xposed.raw
// ==/LSPosedScript==

const TAG = "XHH-Sample";

xposed.onPackageLoaded(function (param) {
    xposed.i(TAG, "loaded package=" + param.getPackageName() + ", process=" + env.processName);

    const appClass = Java.use("android.app.Application");
    const onCreate = appClass.getDeclaredMethod("onCreate");
    onCreate.setAccessible(true);

    xposed
        .hook(onCreate)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
            xposed.d(TAG, "Application.onCreate before: " + param.getPackageName());
            const result = chain.proceed();
            xposed.d(TAG, "Application.onCreate after: " + param.getPackageName());
            return result;
        });
});
""".trimIndent()

    private data class ScriptSource(
        val metadata: ScriptMetadata,
        val file: File,
        val content: String,
        val source: String
    )

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    }

    fun ensurePublicFolderAndSample(allowRootFallback: Boolean = true): Result<File> = runCatching {
        val managerState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "isExternalStorageManager=${Environment.isExternalStorageManager()}"
        } else {
            "legacyExternalStorage"
        }
        Log.d(
            TAG,
            "ensurePublicFolderAndSample: dir=${publicScriptsDir.absolutePath}, exists=${publicScriptsDir.exists()}, allowRootFallback=$allowRootFallback, $managerState"
        )
        if (!publicScriptsDir.exists() && !publicScriptsDir.mkdirs()) {
            if (allowRootFallback) {
                Log.w(TAG, "ensurePublicFolderAndSample: normal mkdir failed, try root mkdir")
                runRootCommand("mkdir -p ${shellQuote(publicScriptsDir.absolutePath)}", timeoutSeconds = 5)
            } else {
                Log.w(TAG, "ensurePublicFolderAndSample: normal mkdir failed, root fallback disabled")
            }
            if (!publicScriptsDir.exists()) {
                throw IllegalStateException("无法创建公共脚本目录：${publicScriptsDir.absolutePath}，请授予管理所有文件权限或启用 root fallback")
            }
        }
        if (!sampleScriptFile.exists()) {
            Log.d(TAG, "ensurePublicFolderAndSample: create sample=${sampleScriptFile.absolutePath}")
            runCatching { sampleScriptFile.writeText(sampleScript, StandardCharsets.UTF_8) }
                .onFailure { Log.w(TAG, "ensurePublicFolderAndSample: create sample with File API failed; ignore", it) }
        }
        Log.d(
            TAG,
            "ensurePublicFolderAndSample: ready, canRead=${publicScriptsDir.canRead()}, canWrite=${publicScriptsDir.canWrite()}, listCount=${safeListCount(publicScriptsDir)}"
        )
        publicScriptsDir
    }

    fun readPublicScripts(
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true
    ): List<Pair<ScriptMetadata, File>> {
        return readPublicScriptSources(debugPackageName, allowRootFallback).map { it.metadata to it.file }
    }

    private fun readPublicScriptSources(
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true
    ): List<ScriptSource> {
        ensurePublicFolderAndSample(allowRootFallback).onFailure {
            Log.e(TAG, "readPublicScripts: ensure folder failed; normal scan may fail; root fallback depends on allowRootFallback", it)
        }

        Log.d(
            TAG,
            "readPublicScripts: start scan dir=${publicScriptsDir.absolutePath}, recursive=true, debugPackage=${debugPackageName.orEmpty()}, allowRootFallback=$allowRootFallback, hasAllFilesAccess=${hasAllFilesAccess()}"
        )

        val normal = readPublicScriptSourcesByFileApi(debugPackageName)

        // 进入某个应用的脚本页时 debugPackageName 不为空。这里即使 File API 扫到了样例脚本，
        // 也继续尝试 root 合并一次，避免 scoped storage 只暴露部分文件，导致 qidian_toolbox_log.js 不出现。
        val shouldTryRoot = allowRootFallback && (normal.isEmpty() || debugPackageName != null)
        val root = if (shouldTryRoot) {
            Log.w(
                TAG,
                "readPublicScripts: try root fallback/merge. normalCount=${normal.size}, debugPackage=${debugPackageName.orEmpty()}, dirExists=${publicScriptsDir.exists()}, canRead=${publicScriptsDir.canRead()}, listed=${safeListCount(publicScriptsDir)}"
            )
            readPublicScriptSourcesByRoot(debugPackageName)
        } else {
            if (!allowRootFallback) {
                Log.d(TAG, "readPublicScripts: skip root fallback because user has not returned from all-files settings yet")
            }
            emptyList()
        }

        val merged = mergeScriptSources(normal, root)
        if (merged.isNotEmpty()) {
            Log.d(TAG, "readPublicScripts: final merged count=${merged.size}, normal=${normal.size}, root=${root.size}, ids=${merged.joinToString { it.metadata.id + "/" + it.source }}")
            return merged
        }

        Log.w(TAG, "readPublicScripts: no scripts found by File API or root. Check MANAGE_EXTERNAL_STORAGE grant or su availability.")
        return emptyList()
    }

    private fun mergeScriptSources(normal: List<ScriptSource>, root: List<ScriptSource>): List<ScriptSource> {
        val byPath = linkedMapOf<String, ScriptSource>()
        normal.forEach { byPath[it.file.absolutePath] = it }
        // root 放后面：同一路径优先使用 root 读到的内容，避免普通 File API 能列出但无法完整读取。
        root.forEach { byPath[it.file.absolutePath] = it }
        return byPath.values.sortedBy { it.metadata.name.lowercase() }
    }

    private fun readPublicScriptSourcesByFileApi(debugPackageName: String?): List<ScriptSource> {
        if (!publicScriptsDir.exists() || !publicScriptsDir.isDirectory) {
            Log.d(TAG, "readPublicScripts(File): dir missing or not directory")
            return emptyList()
        }

        return runCatching {
            publicScriptsDir
                .walkTopDown()
                .onEnter { dir ->
                    Log.d(TAG, "readPublicScripts(File): enter dir=${dir.absolutePath}, canRead=${dir.canRead()}, listCount=${safeListCount(dir)}")
                    true
                }
                .filter { file -> file.isFile && file.extension.equals("js", ignoreCase = true) }
                .mapNotNull { file ->
                    val text = runCatching { file.readText(StandardCharsets.UTF_8) }
                        .onFailure { Log.e(TAG, "readPublicScripts(File): read failed ${file.absolutePath}", it) }
                        .getOrNull()
                        ?: return@mapNotNull null
                    parseScriptSource(file, text, "file", debugPackageName)
                }
                .sortedBy { it.metadata.name.lowercase() }
                .toList()
        }.getOrElse { error ->
            Log.e(TAG, "readPublicScripts(File): scan crashed", error)
            emptyList()
        }
    }

    private fun readPublicScriptSourcesByRoot(debugPackageName: String?): List<ScriptSource> {
        val idResult = runRootCommand("id", timeoutSeconds = 5)
        Log.d(
            TAG,
            "readPublicScripts(root): su id exit=${idResult.exitCode}, timeout=${idResult.timedOut}, stdout=${idResult.stdout.trim()}, stderr=${idResult.stderr.trim()}"
        )
        if (idResult.timedOut || idResult.exitCode != 0) {
            Log.w(TAG, "readPublicScripts(root): su unavailable or denied")
            return emptyList()
        }

        val findCommand = "find ${shellQuote(publicScriptsDir.absolutePath)} -type f 2>/dev/null"
        val findResult = runRootCommand(findCommand, timeoutSeconds = 10)
        Log.d(
            TAG,
            "readPublicScripts(root): find exit=${findResult.exitCode}, timeout=${findResult.timedOut}, stderr=${findResult.stderr.trim()}, rawCount=${findResult.stdout.lineSequence().count { it.isNotBlank() }}"
        )
        if (findResult.timedOut || findResult.exitCode != 0) {
            Log.w(TAG, "readPublicScripts(root): find failed")
            return emptyList()
        }

        val paths = findResult.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(".js", ignoreCase = true) }
            .distinct()
            .toList()

        Log.d(TAG, "readPublicScripts(root): js paths count=${paths.size}, paths=${paths.joinToString()}")

        val sources = paths.mapNotNull { path ->
            val catResult = runRootCommand("cat ${shellQuote(path)}", timeoutSeconds = 10)
            if (catResult.timedOut || catResult.exitCode != 0) {
                Log.e(
                    TAG,
                    "readPublicScripts(root): read failed path=$path, exit=${catResult.exitCode}, timeout=${catResult.timedOut}, stderr=${catResult.stderr.trim()}"
                )
                return@mapNotNull null
            }
            Log.d(TAG, "readPublicScripts(root): found js=$path, chars=${catResult.stdout.length}")
            parseScriptSource(File(path), catResult.stdout, "root", debugPackageName)
        }.sortedBy { it.metadata.name.lowercase() }

        Log.d(TAG, "readPublicScripts(root): done, count=${sources.size}")
        return sources
    }

    private fun parseScriptSource(
        file: File,
        text: String,
        source: String,
        debugPackageName: String?
    ): ScriptSource {
        val metadata = parseMetadata(text, file.nameWithoutExtension)
        val matchInfo = debugPackageName?.let { packageName ->
            ", supports($packageName)=${metadata.supportsPackage(packageName)}"
        }.orEmpty()
        Log.d(
            TAG,
            "readPublicScripts($source): parsed file=${file.name}, id=${metadata.id}, name=${metadata.name}, targets=${metadata.targets}, processes=${metadata.processes}, url=${metadata.url}, urlRefresh=${metadata.urlRefreshOnApply}$matchInfo"
        )
        return ScriptSource(metadata, file, text, source)
    }

    fun syncPublicScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        Log.d(TAG, "syncPublicScriptsToRemote: start, service=${service != null}, prefs=${prefs != null}, debugPackage=${debugPackageName.orEmpty()}, allowRootFallback=$allowRootFallback")
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }

        val scripts = readPublicScriptSources(debugPackageName, allowRootFallback).map { source ->
            val remoteName = source.metadata.remoteName.ifBlank { remoteNameFor(source.metadata.id) }
            val contentToWrite = resolveScriptContentForApply(source)
            Log.d(
                TAG,
                "syncPublicScriptsToRemote: write remoteName=$remoteName, file=${source.file.absolutePath}, id=${source.metadata.id}, source=${source.source}, mode=${source.metadata.sourceMode}, urlRefresh=${source.metadata.urlRefreshOnApply}, chars=${contentToWrite.length}"
            )
            writeRemoteFile(service, remoteName, contentToWrite)
            source.metadata.copy(remoteName = remoteName)
        }

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, scripts.toJson().toString())
            .apply()

        Log.d(TAG, "syncPublicScriptsToRemote: saved index count=${scripts.size}")
        scripts
    }



    fun syncEnabledAppsScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        Log.d(TAG, "syncEnabledAppsScriptsToRemote: start, service=${service != null}, prefs=${prefs != null}, allowRootFallback=$allowRootFallback")
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }

        val enabledPackages = prefs.all
            .asSequence()
            .filter { (key, value) -> key.startsWith("app_enabled_") && value == true }
            .map { (key, _) -> key.removePrefix("app_enabled_") }
            .filter { it.isNotBlank() }
            .toList()

        if (enabledPackages.isEmpty()) {
            prefs.edit().putString(ScriptPrefs.SCRIPT_INDEX_JSON, JSONArray().toString()).apply()
            Log.d(TAG, "syncEnabledAppsScriptsToRemote: no enabled apps, clear remote index")
            return@runCatching emptyList()
        }

        val enabledPackageSet = enabledPackages.toSet()
        val sources = readPublicScriptSources(debugPackageName = null, allowRootFallback = allowRootFallback)
        val selected = sources.filter { source ->
            val matchedPackages = enabledPackageSet.filter { packageName -> source.metadata.supportsPackage(packageName) }
            val enabledForAnyPackage = matchedPackages.any { packageName ->
                prefs.getBoolean(ScriptPrefs.scriptEnabledKey(packageName, source.metadata.id), false)
            }
            Log.d(
                TAG,
                "syncEnabledAppsScriptsToRemote: inspect id=${source.metadata.id}, matchedPackages=$matchedPackages, enabledForAnyPackage=$enabledForAnyPackage"
            )
            enabledForAnyPackage
        }

        val scripts = selected.map { source ->
            val remoteName = source.metadata.remoteName.ifBlank { remoteNameFor(source.metadata.id) }
            val contentToWrite = resolveScriptContentForApply(source)
            Log.d(
                TAG,
                "syncEnabledAppsScriptsToRemote: write remoteName=$remoteName, file=${source.file.absolutePath}, id=${source.metadata.id}, source=${source.source}, mode=${source.metadata.sourceMode}, urlRefresh=${source.metadata.urlRefreshOnApply}, chars=${contentToWrite.length}"
            )
            writeRemoteFile(service, remoteName, contentToWrite)
            source.metadata.copy(remoteName = remoteName)
        }

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, scripts.toJson().toString())
            .apply()

        Log.d(
            TAG,
            "syncEnabledAppsScriptsToRemote: saved index count=${scripts.size}, enabledPackages=${enabledPackages.joinToString()}"
        )
        scripts
    }

    fun parseMetadata(script: String, fallbackId: String): ScriptMetadata {
        val values = linkedMapOf<String, MutableList<String>>()
        var inHeader = false

        script.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line == HEADER_BEGIN -> inHeader = true
                line == HEADER_END -> inHeader = false
                inHeader && line.startsWith("// @") -> {
                    val body = line.removePrefix("// @")
                    val firstSpace = body.indexOfFirst { it.isWhitespace() }
                    if (firstSpace > 0) {
                        val key = body.substring(0, firstSpace).trim().lowercase()
                        val value = body.substring(firstSpace).trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            values.getOrPut(key) { mutableListOf() }.add(value)
                        }
                    }
                }
            }
        }

        val id = values.first("id") ?: fallbackId
        val url = values.first("url").orEmpty().trim()
        val refreshOnApply = values.first("url-refresh")
            ?: values.first("url-refresh-on-apply")
            ?: values.first("refresh-url")
            ?: values.first("remote-refresh")

        return ScriptMetadata(
            id = id,
            name = values.first("name") ?: id,
            version = values.first("version") ?: "1.0.0",
            author = values.first("author") ?: "Unknown",
            description = values.first("description") ?: "",
            targets = values["target"].orEmpty().flatMap { it.split(',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() },
            processes = values["process"].orEmpty().flatMap { it.split(',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() },
            runAt = values.first("run-at") ?: "package-loaded",
            grants = values["grant"].orEmpty().flatMap { it.split(',', ' ') }.map { it.trim() }.filter { it.isNotEmpty() },
            remoteName = remoteNameFor(id),
            sourceMode = if (url.isNotBlank()) "url-meta" else "local",
            url = url,
            urlRefreshOnApply = parseBooleanLike(refreshOnApply)
        )
    }

    fun List<ScriptMetadata>.toJson(): JSONArray {
        val array = JSONArray()
        forEach { script ->
            array.put(JSONObject().apply {
                put("id", script.id)
                put("name", script.name)
                put("version", script.version)
                put("author", script.author)
                put("description", script.description)
                put("targets", JSONArray(script.targets))
                put("processes", JSONArray(script.processes))
                put("runAt", script.runAt)
                put("grants", JSONArray(script.grants))
                put("remoteName", script.remoteName.ifBlank { remoteNameFor(script.id) })
                put("sourceMode", script.sourceMode)
                put("url", script.url)
                put("urlRefreshOnApply", script.urlRefreshOnApply)
            })
        }
        return array
    }

    fun parseIndex(json: String?): List<ScriptMetadata> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        ScriptMetadata(
                            id = obj.optString("id"),
                            name = obj.optString("name", obj.optString("id")),
                            version = obj.optString("version", "1.0.0"),
                            author = obj.optString("author", "Unknown"),
                            description = obj.optString("description", ""),
                            targets = obj.optJSONArray("targets").toStringList(),
                            processes = obj.optJSONArray("processes").toStringList(),
                            runAt = obj.optString("runAt", "package-loaded"),
                            grants = obj.optJSONArray("grants").toStringList(),
                            remoteName = obj.optString("remoteName", remoteNameFor(obj.optString("id"))),
                            sourceMode = obj.optString("sourceMode", if (obj.optString("url").isNotBlank()) "url-meta" else "local"),
                            url = obj.optString("url", ""),
                            urlRefreshOnApply = obj.optBoolean("urlRefreshOnApply", false)
                        )
                    )
                }
            }
        }.getOrElse {
            Log.e(TAG, "解析脚本索引失败", it)
            emptyList()
        }
    }


    private fun resolveScriptContentForApply(source: ScriptSource): String {
        val metadata = source.metadata
        if (metadata.url.isBlank() || !metadata.urlRefreshOnApply) {
            return source.content
        }

        Log.d(TAG, "resolveScriptContentForApply: refresh URL before apply, id=${metadata.id}, url=${metadata.url}")
        return downloadUrlText(metadata.url).getOrElse { error ->
            Log.e(TAG, "resolveScriptContentForApply: URL refresh failed, id=${metadata.id}, url=${metadata.url}", error)
            throw IllegalStateException("URL 脚本拉取失败：${metadata.name} <${metadata.url}>", error)
        }
    }

    private fun downloadUrlText(url: String): Result<String> = runCatching {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持 http/https URL：$url")
        }
        if (url.contains("127.0.0.1") || url.contains("localhost", ignoreCase = true)) {
            Log.w(TAG, "downloadUrlText: 127.0.0.1/localhost 指向手机本机；电脑服务请使用 adb reverse 或电脑局域网 IP")
        }

        Log.d(TAG, "downloadUrlText: start url=$url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "XiaoHeiHook/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.d(TAG, "downloadUrlText: code=${response.code}, finalUrl=${response.request.url}, chars=${body.length}")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $url")
            }
            if (body.isBlank()) {
                throw IllegalStateException("远端脚本为空：$url")
            }
            body
        }
    }

    private fun parseBooleanLike(value: String?): Boolean {
        return when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "y", "on", "always", "apply", "sync" -> true
            else -> false
        }
    }

    fun remoteNameFor(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        val suffix = sha1(id).take(8)
        return "script_${cleaned.ifBlank { "unnamed" }}_$suffix"
    }

    private fun writeRemoteFile(service: XposedService, name: String, content: String) {
        val pfd: ParcelFileDescriptor = service.openRemoteFile(name)
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            out.channel.truncate(0)
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun safeListCount(dir: File): Int {
        return runCatching { dir.listFiles()?.size ?: -1 }.getOrDefault(-2)
    }

    private fun runRootCommand(command: String, timeoutSeconds: Long): RootCommandResult {
        return runCatching {
            Log.d(TAG, "root exec: su -c $command")
            val process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@runCatching RootCommandResult(-1, "", "timeout", timedOut = true)
            }
            val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            RootCommandResult(process.exitValue(), stdout, stderr)
        }.getOrElse { error ->
            Log.w(TAG, "root exec failed: $command", error)
            RootCommandResult(-1, "", error.message.orEmpty())
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Map<String, MutableList<String>>.first(key: String): String? = this[key]?.firstOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
