package top.lovepikachu.XiaoHeiHook.data

import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ScriptRepository {
    private const val TAG = "XiaoHeiHook-Scripts"
    private const val HEADER_BEGIN = "// ==LSPosedScript=="
    private const val HEADER_END = "// ==/LSPosedScript=="

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val publicScriptsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "XiaoHeiHook"
        )

    val sampleScriptFile: File
        get() = File(publicScriptsDir, "application_on_create_log.js")

    val sampleScript: String = """
// ==LSPosedScript==
// @name         Application.onCreate 日志模板
// @id           sample.application.oncreate.log
// @version      1.0.0
// @author       XiaoHeiHook
// @description  安全模板：在已启用的目标应用中记录 Application.onCreate，不修改应用行为。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

console.log("script loaded for " + env.packageName + " / " + env.processName);

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        console.log("Application.onCreate finished: " + env.packageName);
    }
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
                .filter { file -> file.isFile && isSupportedScriptPointer(file) }
                .mapNotNull { file ->
                    val text = runCatching { file.readText(StandardCharsets.UTF_8) }
                        .onFailure { Log.e(TAG, "readPublicScripts(File): read failed ${file.absolutePath}", it) }
                        .getOrNull()
                        ?: return@mapNotNull null
                    parseScriptPointer(file, text, "file", debugPackageName)
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
            .filter { it.isNotEmpty() && (it.endsWith(".js", ignoreCase = true) || it.endsWith(".url", ignoreCase = true)) }
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
            Log.d(TAG, "readPublicScripts(root): found pointer=$path, chars=${catResult.stdout.length}")
            parseScriptPointer(File(path), catResult.stdout, "root", debugPackageName)
        }.sortedBy { it.metadata.name.lowercase() }

        Log.d(TAG, "readPublicScripts(root): done, count=${sources.size}")
        return sources
    }

    private fun parseScriptPointer(
        file: File,
        text: String,
        source: String,
        debugPackageName: String?
    ): ScriptSource? {
        return if (file.extension.equals("url", ignoreCase = true)) {
            parseUrlScriptSource(file, text, source, debugPackageName)
        } else {
            parseLocalScriptSource(file, text, source, debugPackageName)
        }
    }

    private fun parseLocalScriptSource(
        file: File,
        text: String,
        source: String,
        debugPackageName: String?
    ): ScriptSource {
        val metadata = parseMetadata(text, file.nameWithoutExtension).copy(sourceMode = "local", url = "")
        val matchInfo = debugPackageName?.let { packageName ->
            ", supports($packageName)=${metadata.supportsPackage(packageName)}"
        }.orEmpty()
        Log.d(
            TAG,
            "readPublicScripts($source): parsed file=${file.name}, id=${metadata.id}, name=${metadata.name}, targets=${metadata.targets}, processes=${metadata.processes}$matchInfo"
        )
        return ScriptSource(metadata, file, text, source)
    }

    private fun parseUrlScriptSource(
        file: File,
        pointerContent: String,
        source: String,
        debugPackageName: String?
    ): ScriptSource? {
        val pointerMetadata = parseMetadata(pointerContent, file.nameWithoutExtension.ifBlank { "url_pointer" })
        val url = extractUrlFromPointer(pointerContent, pointerMetadata.url)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "readPublicScripts($source-url): empty url pointer=${file.absolutePath}")
            return null
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "readPublicScripts($source-url): unsupported url=$url")
            return null
        }

        // URL 脚本指针应该先显示在列表里，再尝试下载正文。
        // 之前这里下载失败会直接 return null，导致“URL 已添加但脚本列表不显示”。
        // 现在下载失败时返回一个 pending URL 脚本项；同步时再明确提示下载失败。
        val stableId = pointerMetadata.id.ifBlank { file.nameWithoutExtension.ifBlank { "url_${sha1(url).take(12)}" } }
        val scriptText = runCatching { downloadUrlText(url) }
            .onFailure { Log.e(TAG, "readPublicScripts($source-url): download failed but keep pointer visible url=$url", it) }
            .getOrNull()

        val downloadedMetadata = scriptText?.let { parseMetadata(it, stableId) }
        val baseMetadata = downloadedMetadata ?: pointerMetadata
        val metadata = baseMetadata.copy(
            // URL 指针的 id 保持稳定，避免远程脚本下载成功/失败时 UI 和单脚本同步找不到同一个脚本。
            id = stableId,
            name = when {
                downloadedMetadata != null && downloadedMetadata.name != downloadedMetadata.id -> downloadedMetadata.name
                pointerMetadata.name != pointerMetadata.id -> pointerMetadata.name
                else -> "URL Script ${url.substringAfterLast('/').substringBefore('?').ifBlank { stableId }}"
            },
            author = when {
                downloadedMetadata != null && downloadedMetadata.author != "Unknown" -> downloadedMetadata.author
                pointerMetadata.author != "Unknown" -> pointerMetadata.author
                else -> "URL"
            },
            description = when {
                downloadedMetadata != null && downloadedMetadata.description.isNotBlank() -> downloadedMetadata.description
                pointerMetadata.description.isNotBlank() -> pointerMetadata.description
                else -> "Remote URL script: $url"
            },
            targets = if (downloadedMetadata?.targets?.isNotEmpty() == true) downloadedMetadata.targets else pointerMetadata.targets,
            processes = if (downloadedMetadata?.processes?.isNotEmpty() == true) downloadedMetadata.processes else pointerMetadata.processes,
            grants = if (downloadedMetadata?.grants?.isNotEmpty() == true) downloadedMetadata.grants else pointerMetadata.grants,
            remoteName = remoteNameFor(stableId),
            sourceMode = if (scriptText == null) "url-pending" else "url",
            url = url
        )
        val matchInfo = debugPackageName?.let { packageName ->
            ", supports($packageName)=${metadata.supportsPackage(packageName)}"
        }.orEmpty()
        Log.d(
            TAG,
            "readPublicScripts($source-url): parsed pointer=${file.name}, id=${metadata.id}, name=${metadata.name}, url=$url, targets=${metadata.targets}, downloaded=${scriptText != null}, chars=${scriptText?.length ?: 0}$matchInfo"
        )
        return ScriptSource(metadata, file, scriptText.orEmpty(), if (scriptText == null) "$source-url-pending" else "$source-url")
    }

    private fun resolveUrlSourceForSync(source: ScriptSource): ScriptSource {
        if (!source.metadata.sourceMode.startsWith("url")) return source
        if (source.content.isNotBlank()) return source

        val url = source.metadata.url.trim()
        if (url.isBlank()) {
            Log.e(TAG, "syncPublicScriptsToRemote: pending URL source has empty url id=${source.metadata.id}, file=${source.file.absolutePath}")
            return source
        }

        Log.d(
            TAG,
            "syncPublicScriptsToRemote: retry download pending URL id=${source.metadata.id}, name=${source.metadata.name}, url=$url"
        )
        val scriptText = runCatching { downloadUrlText(url) }
            .onFailure { Log.e(TAG, "syncPublicScriptsToRemote: retry URL download failed url=$url", it) }
            .getOrNull()
            ?: return source

        val downloadedMetadata = parseMetadata(scriptText, source.metadata.id)
        val metadata = source.metadata.copy(
            name = if (downloadedMetadata.name != downloadedMetadata.id) downloadedMetadata.name else source.metadata.name,
            author = if (downloadedMetadata.author != "Unknown") downloadedMetadata.author else source.metadata.author,
            description = downloadedMetadata.description.ifBlank { source.metadata.description },
            targets = if (downloadedMetadata.targets.isNotEmpty()) downloadedMetadata.targets else source.metadata.targets,
            processes = if (downloadedMetadata.processes.isNotEmpty()) downloadedMetadata.processes else source.metadata.processes,
            grants = if (downloadedMetadata.grants.isNotEmpty()) downloadedMetadata.grants else source.metadata.grants,
            remoteName = source.metadata.remoteName.ifBlank { remoteNameFor(source.metadata.id) },
            sourceMode = "url",
            url = url
        )
        Log.d(TAG, "syncPublicScriptsToRemote: retry URL download success id=${metadata.id}, chars=${scriptText.length}, sha1=${sha1(scriptText).take(12)}")
        return source.copy(metadata = metadata, content = scriptText, source = source.source.removeSuffix("-pending") + "-sync")
    }

    fun syncPublicScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true,
        onlyEnabledApps: Boolean = false,
        singleScriptId: String? = null
    ): Result<List<ScriptMetadata>> = runCatching {
        Log.d(
            TAG,
            "syncPublicScriptsToRemote: start, service=${service != null}, prefs=${prefs != null}, debugPackage=${debugPackageName.orEmpty()}, allowRootFallback=$allowRootFallback, onlyEnabledApps=$onlyEnabledApps, singleScriptId=${singleScriptId.orEmpty()}"
        )
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }

        val allSources = readPublicScriptSources(debugPackageName, allowRootFallback)
        val enabledPackages = enabledPackages(prefs)
        val sourcesToWrite = allSources.filter { source ->
            when {
                singleScriptId != null -> source.metadata.id == singleScriptId
                onlyEnabledApps -> enabledPackages.isNotEmpty() && source.metadata.supportsAnyPackage(enabledPackages)
                debugPackageName != null -> source.metadata.supportsPackage(debugPackageName)
                else -> true
            }
        }

        val resolvedSourcesToWrite = sourcesToWrite.map { source ->
            resolveUrlSourceForSync(source)
        }

        val failedUrlSources = resolvedSourcesToWrite.filter { source ->
            source.metadata.sourceMode.startsWith("url") && source.content.isBlank()
        }
        if (failedUrlSources.isNotEmpty()) {
            val message = "URL 脚本下载失败，未写入 Remote Files：" +
                failedUrlSources.joinToString { "${it.metadata.name} <${it.metadata.url}>" }
            Log.e(TAG, "syncPublicScriptsToRemote: $message")
            throw IllegalStateException(message)
        }

        val syncedMetadata = resolvedSourcesToWrite.map { source ->
            val remoteName = source.metadata.remoteName.ifBlank { remoteNameFor(source.metadata.id) }
            val finalMode = if (source.metadata.sourceMode == "url-pending") "url" else source.metadata.sourceMode
            Log.d(
                TAG,
                "syncPublicScriptsToRemote: write remoteName=$remoteName, file=${source.file.absolutePath}, id=${source.metadata.id}, mode=${source.metadata.sourceMode}, source=${source.source}, chars=${source.content.length}"
            )
            writeRemoteFile(service, remoteName, source.content)
            source.metadata.copy(remoteName = remoteName, sourceMode = finalMode)
        }

        val previous = parseIndex(prefs.getString(ScriptPrefs.SCRIPT_INDEX_JSON, "[]"))
        val finalIndex = when {
            singleScriptId != null -> {
                (previous.filterNot { it.id == singleScriptId } + syncedMetadata)
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() }
            }
            debugPackageName != null -> {
                (previous.filterNot { it.supportsPackage(debugPackageName) } + syncedMetadata)
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() }
            }
            else -> syncedMetadata.sortedBy { it.name.lowercase() }
        }

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, finalIndex.toJson().toString())
            .apply()

        Log.d(
            TAG,
            "syncPublicScriptsToRemote: saved index count=${finalIndex.size}, wrote=${syncedMetadata.size}, enabledPackages=${enabledPackages.joinToString()}"
        )
        finalIndex
    }

    fun syncEnabledAppsScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> {
        return syncPublicScriptsToRemote(
            service = service,
            prefs = prefs,
            allowRootFallback = allowRootFallback,
            onlyEnabledApps = true
        )
    }

    fun syncSingleScriptToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        scriptId: String,
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> {
        return syncPublicScriptsToRemote(
            service = service,
            prefs = prefs,
            debugPackageName = debugPackageName,
            allowRootFallback = allowRootFallback,
            singleScriptId = scriptId
        )
    }

    fun createUrlScriptPointer(
        url: String,
        targetPackage: String? = null,
        allowRootFallback: Boolean = true
    ): Result<File> = runCatching {
        val cleanedUrl = url.trim()
        if (!cleanedUrl.startsWith("http://") && !cleanedUrl.startsWith("https://")) {
            throw IllegalArgumentException("URL 必须以 http:// 或 https:// 开头")
        }
        ensurePublicFolderAndSample(allowRootFallback).getOrThrow()
        val urlDir = File(publicScriptsDir, "urls")
        if (!urlDir.exists() && !urlDir.mkdirs()) {
            if (allowRootFallback) {
                runRootCommand("mkdir -p ${shellQuote(urlDir.absolutePath)}", timeoutSeconds = 5)
            }
        }
        if (!urlDir.exists()) {
            throw IllegalStateException("无法创建 URL 脚本目录：${urlDir.absolutePath}")
        }
        val hash = sha1(cleanedUrl).take(12)
        val id = "url.$hash"
        val target = targetPackage?.takeIf { it.isNotBlank() } ?: "*"
        val file = File(urlDir, "url_$hash.url")
        val displayName = cleanedUrl.substringAfterLast('/').substringBefore('?').ifBlank { hash }
        val pointerText = """
// ==LSPosedScript==
// @name         URL Script $displayName
// @id           $id
// @version      1.0.0
// @author       URL
// @description  Remote URL script: $cleanedUrl
// @target       $target
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// @mode         url
// @url          $cleanedUrl
// ==/LSPosedScript==
$cleanedUrl
""".trimIndent() + "\n"
        runCatching { file.writeText(pointerText, StandardCharsets.UTF_8) }.getOrElse { error ->
            if (!allowRootFallback) throw error
            val cmd = "cat > ${shellQuote(file.absolutePath)} <<'EOF'\n$pointerText\nEOF"
            val result = runRootCommand(cmd, timeoutSeconds = 5)
            if (result.timedOut || result.exitCode != 0) {
                throw IllegalStateException("写入 URL 指针失败：${result.stderr.trim()}", error)
            }
        }
        Log.d(TAG, "createUrlScriptPointer: url=$cleanedUrl, target=$target, file=${file.absolutePath}")
        file
    }

    fun packageScriptFingerprint(
        packageName: String,
        allowRootFallback: Boolean = true
    ): String {
        val sources = readPublicScriptSources(packageName, allowRootFallback)
            .filter { it.metadata.supportsPackage(packageName) }
            .sortedBy { it.metadata.id }
        val raw = sources.joinToString("\n") { source ->
            "${source.metadata.id}:${source.metadata.sourceMode}:${source.metadata.url}:${sha1(source.content)}"
        }
        val fingerprint = sha1(raw)
        Log.d(TAG, "packageScriptFingerprint: package=$packageName, count=${sources.size}, fingerprint=$fingerprint")
        return fingerprint
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
            sourceMode = values.first("mode") ?: values.first("source") ?: "local",
            url = values.first("url") ?: ""
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
                            sourceMode = obj.optString("sourceMode", "local"),
                            url = obj.optString("url", "")
                        )
                    )
                }
            }
        }.getOrElse {
            Log.e(TAG, "解析脚本索引失败", it)
            emptyList()
        }
    }

    private fun enabledPackages(prefs: SharedPreferences): List<String> {
        return prefs.all
            .filter { (key, value) -> key.startsWith("app_enabled_") && value == true }
            .map { it.key.removePrefix("app_enabled_") }
            .filter { it.isNotBlank() }
            .sorted()
    }

    private fun isSupportedScriptPointer(file: File): Boolean {
        return file.extension.equals("js", ignoreCase = true) || file.extension.equals("url", ignoreCase = true)
    }

    private fun extractUrlFromPointer(pointerContent: String, metadataUrl: String): String? {
        cleanUrlCandidate(metadataUrl)?.let { return it }

        val direct = pointerContent.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                when {
                    line.startsWith("http://") || line.startsWith("https://") -> line
                    line.startsWith("url=", ignoreCase = true) -> line.substringAfter('=').trim()
                    line.startsWith("// @url") -> line.removePrefix("// @url").trim()
                    line.startsWith("@url") -> line.removePrefix("@url").trim()
                    else -> null
                }
            }
            .firstNotNullOfOrNull { cleanUrlCandidate(it) }
        if (direct != null) return direct

        // 兼容用户手写的 “URL Script qidian.js <http://host/qidian.js>” 这类格式。
        val inline = Regex("https?://[^\\s<>\"']+")
            .find(pointerContent)
            ?.value
            ?.let { cleanUrlCandidate(it) }
        if (inline != null) {
            Log.d(TAG, "extractUrlFromPointer: extracted inline url=$inline")
        }
        return inline
    }

    private fun cleanUrlCandidate(raw: String?): String? {
        val cleaned = raw
            ?.trim()
            ?.trim('<', '>', '"', '\'', '`', ' ', '\t', '\r', '\n')
            ?.trimEnd(',', ';', ')', ']', '}')
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun downloadUrlText(url: String): String {
        val cleanedUrl = cleanUrlCandidate(url) ?: throw IllegalArgumentException("无效 URL：$url")
        if (cleanedUrl.startsWith("http://127.0.0.1") || cleanedUrl.startsWith("http://localhost")) {
            Log.w(TAG, "downloadUrlText(OkHttp): 127.0.0.1/localhost 指向手机本机；如果服务在电脑上，请使用电脑局域网 IP，或先执行 adb reverse tcp:8000 tcp:8000")
        }
        Log.d(TAG, "downloadUrlText(OkHttp): start url=$cleanedUrl")
        val request = Request.Builder()
            .url(cleanedUrl)
            .header("User-Agent", "XiaoHeiHook/1.0")
            .header("Accept", "text/javascript, application/javascript, text/plain, */*")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type").orEmpty()
            val bodyText = response.body?.string().orEmpty()
            Log.d(
                TAG,
                "downloadUrlText(OkHttp): response code=$code, type=$contentType, finalUrl=$finalUrl, chars=${bodyText.length}"
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP $code ${bodyText.take(300)}")
            }
            if (bodyText.isBlank()) {
                throw IllegalStateException("URL 脚本内容为空：$cleanedUrl")
            }
            Log.d(TAG, "downloadUrlText(OkHttp): success url=$cleanedUrl, sha1=${sha1(bodyText).take(12)}")
            return bodyText
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
