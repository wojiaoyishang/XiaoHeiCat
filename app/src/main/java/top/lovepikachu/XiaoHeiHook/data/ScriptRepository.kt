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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ScriptRepository {
    private const val TAG = "XiaoHeiHook-Scripts"
    private const val HEADER_BEGIN = "// ==LSPosedScript=="
    private const val HEADER_END = "// ==/LSPosedScript=="

    @Volatile
    private var configuredScriptsDir: File? = null

    @Volatile
    private var memoryScriptMetadataCache: List<ScriptMetadata>? = null

    fun cachedScriptMetadata(): List<ScriptMetadata>? = memoryScriptMetadataCache

    private fun updateMemoryScriptMetadataCache(scripts: List<ScriptMetadata>): List<ScriptMetadata> {
        memoryScriptMetadataCache = scripts
        return scripts
    }

    val publicScriptsDir: File
        get() = configuredScriptsDir ?: defaultPublicScriptsDir()

    fun defaultPublicScriptsDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "XiaoHeiHook"
    )

    fun scriptRootFromPrefs(prefs: SharedPreferences?): File {
        val raw = prefs?.getString(ScriptPrefs.SCRIPT_ROOT, null)
            ?: prefs?.getString(ScriptPrefs.SCRIPT_ROOT_LEGACY, null)
        return raw?.trim()?.takeIf { it.isNotBlank() }?.let { File(it) } ?: defaultPublicScriptsDir()
    }

    fun applyScriptRootFromPrefs(prefs: SharedPreferences?) {
        configuredScriptsDir = scriptRootFromPrefs(prefs).absoluteFile
    }

    fun setScriptRoot(prefs: SharedPreferences?, path: String): File {
        val dir = File(path.trim()).absoluteFile
        require(dir.path.isNotBlank()) { "脚本根目录不能为空" }
        configuredScriptsDir = dir
        memoryScriptMetadataCache = null
        prefs?.edit()
            ?.putString(ScriptPrefs.SCRIPT_ROOT, dir.absolutePath)
            ?.putString(ScriptPrefs.SCRIPT_ROOT_LEGACY, dir.absolutePath)
            ?.remove(ScriptPrefs.SCRIPT_METADATA_CACHE_JSON)
            ?.remove(ScriptPrefs.SCRIPT_METADATA_CACHE_UPDATED_AT)
            ?.remove(ScriptPrefs.SYNC_MANIFEST_JSON)
            ?.commit()
        return dir
    }

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

    private data class ScriptUnitFile(
        val path: String,
        val file: File,
        val content: String,
        val source: String
    )

    private data class ScriptAssetFile(
        val path: String,
        val file: File,
        val bytes: ByteArray,
        val source: String
    )

    private data class RemoteManifestFile(
        val path: String,
        val remoteName: String,
        val sha256: String
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

    fun readScriptMetadataCache(prefs: SharedPreferences?): List<ScriptMetadata> {
        applyScriptRootFromPrefs(prefs)
        val raw = prefs?.getString(ScriptPrefs.SCRIPT_METADATA_CACHE_JSON, null)
        if (raw == null) {
            Log.d(TAG, "readScriptMetadataCache: no cache")
            return emptyList()
        }
        val scripts = parseIndex(raw)
        Log.d(TAG, "readScriptMetadataCache: count=${scripts.size}, updatedAt=${prefs.getLong(ScriptPrefs.SCRIPT_METADATA_CACHE_UPDATED_AT, 0L)}")
        return updateMemoryScriptMetadataCache(scripts)
    }

    fun hasScriptMetadataCache(prefs: SharedPreferences?): Boolean {
        applyScriptRootFromPrefs(prefs)
        return prefs?.contains(ScriptPrefs.SCRIPT_METADATA_CACHE_JSON) == true
    }

    fun loadScriptMetadataCacheOrScan(
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): List<ScriptMetadata> {
        applyScriptRootFromPrefs(prefs)
        memoryScriptMetadataCache?.let { return it }
        if (hasScriptMetadataCache(prefs)) {
            return readScriptMetadataCache(prefs)
        }
        Log.d(TAG, "loadScriptMetadataCacheOrScan: cache missing, perform first scan")
        return refreshScriptMetadataCache(prefs, allowRootFallback).getOrElse { error ->
            Log.e(TAG, "loadScriptMetadataCacheOrScan: first scan failed", error)
            emptyList()
        }
    }

    /**
     * Cheap UI refresh for app detail pages. It only lists candidate script entry files
     * and compares their count with the count saved with the metadata cache. If the
     * count is unchanged, the cached metadata is reused. If the count changed, a full
     * metadata rescan is performed and the cache is updated.
     */
    fun softRefreshScriptMetadataCacheIfCountChanged(
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        applyScriptRootFromPrefs(prefs)
        if (!hasScriptMetadataCache(prefs)) {
            Log.d(TAG, "softRefreshScriptMetadataCacheIfCountChanged: cache missing, full refresh")
            return@runCatching refreshScriptMetadataCache(prefs, allowRootFallback).getOrThrow()
        }

        val cached = readScriptMetadataCache(prefs)
        val cachedFileCount = prefs?.getInt(ScriptPrefs.SCRIPT_METADATA_CACHE_FILE_COUNT, -1) ?: -1
        val expectedCount = if (cachedFileCount >= 0) cachedFileCount else cached.size
        val currentCount = countPublicScriptEntryFiles(allowRootFallback)

        Log.d(
            TAG,
            "softRefreshScriptMetadataCacheIfCountChanged: cached=${cached.size}, expectedFileCount=$expectedCount, currentFileCount=$currentCount"
        )

        if (currentCount != expectedCount) {
            Log.d(TAG, "softRefreshScriptMetadataCacheIfCountChanged: file count changed, full refresh")
            refreshScriptMetadataCache(prefs, allowRootFallback).getOrThrow()
        } else {
            if (cachedFileCount != currentCount) {
                prefs?.edit()?.putInt(ScriptPrefs.SCRIPT_METADATA_CACHE_FILE_COUNT, currentCount)?.commit()
            }
            cached
        }
    }.onFailure { error ->
        Log.w(TAG, "softRefreshScriptMetadataCacheIfCountChanged: failed", error)
    }

    fun refreshScriptMetadataCache(
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        applyScriptRootFromPrefs(prefs)
        Log.d(TAG, "refreshScriptMetadataCache: start, allowRootFallback=$allowRootFallback")
        val sources = readPublicScriptSources(
            debugPackageName = null,
            allowRootFallback = allowRootFallback,
            forceRootMerge = allowRootFallback
        )
        val scripts = sources.map { it.metadata }
        saveScriptMetadataCache(prefs, scripts, scriptFileCount = sources.size)
        Log.d(TAG, "refreshScriptMetadataCache: saved count=${scripts.size}, ids=${scripts.joinToString { it.id }}")
        updateMemoryScriptMetadataCache(scripts)
    }

    fun saveScriptMetadataCache(
        prefs: SharedPreferences?,
        scripts: List<ScriptMetadata>,
        scriptFileCount: Int = scripts.size
    ) {
        updateMemoryScriptMetadataCache(scripts)
        if (prefs == null) {
            Log.w(TAG, "saveScriptMetadataCache: prefs null, skip cache save, count=${scripts.size}")
            return
        }
        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_METADATA_CACHE_JSON, scripts.toJson().toString())
            .putLong(ScriptPrefs.SCRIPT_METADATA_CACHE_UPDATED_AT, System.currentTimeMillis())
            .putInt(ScriptPrefs.SCRIPT_METADATA_CACHE_FILE_COUNT, scriptFileCount)
            .commit()
    }

    private fun countPublicScriptEntryFiles(allowRootFallback: Boolean): Int {
        ensurePublicFolderAndSample(allowRootFallback).onFailure {
            Log.e(TAG, "countPublicScriptEntryFiles: ensure folder failed", it)
        }
        val normal = listPublicScriptEntryPathsByFileApi()
        val root = if (allowRootFallback) listPublicScriptEntryPathsByRoot() else emptyList()
        val merged = (normal + root).distinct()
        Log.d(
            TAG,
            "countPublicScriptEntryFiles: count=${merged.size}, normal=${normal.size}, root=${root.size}, allowRootFallback=$allowRootFallback"
        )
        return merged.size
    }

    private fun listPublicScriptEntryPathsByFileApi(): List<String> {
        if (!publicScriptsDir.exists() || !publicScriptsDir.isDirectory) return emptyList()
        return runCatching {
            publicScriptsDir.listFiles()
                ?.mapNotNull { entry ->
                    when {
                        entry.isFile && entry.extension.equals("js", ignoreCase = true) -> entry.absolutePath
                        entry.isDirectory -> File(entry, "index.js").takeIf { it.isFile }?.absolutePath
                        else -> null
                    }
                }
                ?.sortedBy { it.lowercase() }
                ?: emptyList()
        }.getOrElse { error ->
            Log.w(TAG, "listPublicScriptEntryPathsByFileApi failed", error)
            emptyList()
        }
    }

    private fun listPublicScriptEntryPathsByRoot(): List<String> {
        val idResult = runRootCommand("id", timeoutSeconds = 5)
        if (idResult.timedOut || idResult.exitCode != 0) {
            Log.d(TAG, "listPublicScriptEntryPathsByRoot: su unavailable or denied")
            return emptyList()
        }
        val findCommand = "find ${shellQuote(publicScriptsDir.absolutePath)} -maxdepth 2 -type f 2>/dev/null"
        val findResult = runRootCommand(findCommand, timeoutSeconds = 10)
        if (findResult.timedOut || findResult.exitCode != 0) {
            Log.w(TAG, "listPublicScriptEntryPathsByRoot: find failed")
            return emptyList()
        }
        return findResult.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(".js", ignoreCase = true) }
            .filter { isRootScriptEntryPath(it) }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    private fun readPublicScriptSources(
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true,
        forceRootMerge: Boolean = false
    ): List<ScriptSource> {
        ensurePublicFolderAndSample(allowRootFallback).onFailure {
            Log.e(TAG, "readPublicScripts: ensure folder failed; normal scan may fail; root fallback depends on allowRootFallback", it)
        }

        Log.d(
            TAG,
            "readPublicScripts: start scan dir=${publicScriptsDir.absolutePath}, rootOnly=true, debugPackage=${debugPackageName.orEmpty()}, allowRootFallback=$allowRootFallback, hasAllFilesAccess=${hasAllFilesAccess()}"
        )

        val normal = readPublicScriptSourcesByFileApi(debugPackageName)

        // 进入某个应用的脚本页时 debugPackageName 不为空。这里即使 File API 扫到了样例脚本，
        // 也继续尝试 root 合并一次，避免 scoped storage 只暴露部分文件，导致 qidian_toolbox_log.js 不出现。
        val shouldTryRoot = allowRootFallback && (forceRootMerge || normal.isEmpty() || debugPackageName != null)
        val root = if (shouldTryRoot) {
            Log.w(
                TAG,
                "readPublicScripts: try root fallback/merge. normalCount=${normal.size}, debugPackage=${debugPackageName.orEmpty()}, forceRootMerge=$forceRootMerge, dirExists=${publicScriptsDir.exists()}, canRead=${publicScriptsDir.canRead()}, listed=${safeListCount(publicScriptsDir)}"
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
            Log.d(TAG, "readPublicScripts(File): root-only scan dir=${publicScriptsDir.absolutePath}, listCount=${safeListCount(publicScriptsDir)}")
            val result = mutableListOf<ScriptSource>()
            publicScriptsDir.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { entry ->
                    when {
                        entry.isFile && entry.extension.equals("js", ignoreCase = true) -> {
                            val text = runCatching { entry.readText(StandardCharsets.UTF_8) }
                                .onFailure { Log.e(TAG, "readPublicScripts(File): read failed ${entry.absolutePath}", it) }
                                .getOrNull() ?: return@forEach
                            result += parseScriptSource(entry, text, "file", debugPackageName)
                        }
                        entry.isDirectory -> {
                            val index = File(entry, "index.js")
                            if (index.isFile) {
                                val text = runCatching { index.readText(StandardCharsets.UTF_8) }
                                    .onFailure { Log.e(TAG, "readPublicScripts(File): read failed ${index.absolutePath}", it) }
                                    .getOrNull() ?: return@forEach
                                result += parseScriptSource(index, text, "file", debugPackageName)
                            } else {
                                Log.d(TAG, "readPublicScripts(File): ignore directory without index.js ${entry.name}")
                            }
                        }
                    }
                }
            result.sortedBy { it.metadata.name.lowercase() }
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

        val findCommand = "find ${shellQuote(publicScriptsDir.absolutePath)} -maxdepth 2 -type f 2>/dev/null"
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
            .filter { isRootScriptEntryPath(it) }
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
        val relativePath = relativeScriptPath(file)
        val kind = if (relativePath.endsWith("/index.js", ignoreCase = true)) "directory" else "file"
        val rootPath = if (kind == "directory") relativePath.substringBeforeLast("/index.js") else ""
        val settingsSchema = readSettingsSchema(file, kind, rootPath, source)
        val metadata = parseMetadata(text, file.nameWithoutExtension).copy(
            path = relativePath,
            kind = kind,
            entryPath = relativePath,
            rootPath = rootPath,
            hasSettings = settingsSchema != null,
            settingsPath = if (settingsSchema != null && rootPath.isNotBlank()) "$rootPath/settings.json" else "",
            settingsSchema = settingsSchema?.toString() ?: ""
        )
        val matchInfo = debugPackageName?.let { packageName ->
            ", supports($packageName)=${metadata.supportsPackage(packageName)}"
        }.orEmpty()
        Log.d(
            TAG,
            "readPublicScripts($source): parsed file=${file.name}, path=${metadata.path}, id=${metadata.id}, name=${metadata.name}, targets=${metadata.targets}, processes=${metadata.processes}, hasSettings=${metadata.hasSettings}, settingsPath=${metadata.settingsPath}$matchInfo"
        )
        return ScriptSource(metadata, file, text, source)
    }

    private fun readSettingsSchema(entryFile: File, kind: String, rootPath: String, source: String): JSONObject? {
        if (kind != "directory" || rootPath.isBlank()) return null
        val settingsFile = File(entryFile.parentFile, "settings.json")
        val normalText = runCatching {
            if (settingsFile.isFile) settingsFile.readText(StandardCharsets.UTF_8) else ""
        }.getOrDefault("")
        val raw = if (normalText.isNotBlank()) {
            normalText
        } else if (source == "root") {
            val path = File(publicScriptsDir, "$rootPath/settings.json").absolutePath
            val cat = runRootCommand("cat ${shellQuote(path)}", timeoutSeconds = 5)
            if (!cat.timedOut && cat.exitCode == 0) cat.stdout else ""
        } else {
            ""
        }
        if (raw.isBlank()) return null
        val normalized = ScriptSettings.normalizeSchema(raw)
        if (normalized == null) {
            Log.w(TAG, "readSettingsSchema: invalid settings.json root=$rootPath")
        }
        return normalized
    }

    fun syncPublicScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        debugPackageName: String? = null,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        applyScriptRootFromPrefs(prefs)
        Log.d(TAG, "syncPublicScriptsToRemote: start, service=${service != null}, prefs=${prefs != null}, debugPackage=${debugPackageName.orEmpty()}, allowRootFallback=$allowRootFallback")
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }

        val previousManifest = parseSyncManifest(prefs.getString(ScriptPrefs.SYNC_MANIFEST_JSON, "{}"))
        val currentFiles = linkedMapOf<String, RemoteManifestFile>()
        val scripts = readPublicScriptSources(debugPackageName, allowRootFallback).map { source ->
            syncScriptUnitToRemote(service, source, previousManifest, currentFiles)
        }

        val indexJson = scripts.toJson()
        val manifestJson = buildSyncManifestJson(debugPackageName, scripts, currentFiles.values.toList())
        val staleRemoteNames = previousManifest.keys - currentFiles.keys
        val cleanup = cleanupStaleRemoteFiles(service, staleRemoteNames)

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, indexJson.toString())
            .putString(ScriptPrefs.SYNC_MANIFEST_JSON, manifestJson.toString())
            .commit()

        Log.d(TAG, "syncPublicScriptsToRemote: saved index count=${scripts.size}, files=${currentFiles.size}, skippedUnchanged=${currentFiles.values.count { previousManifest[it.remoteName] == it.sha256 }}, stale=${staleRemoteNames.size}, cleanup=$cleanup")
        scripts
    }



    fun syncEnabledAppsScriptsToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        applyScriptRootFromPrefs(prefs)
        Log.d(TAG, "syncEnabledAppsScriptsToRemote: start, service=${service != null}, prefs=${prefs != null}, allowRootFallback=$allowRootFallback")
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }

        val previousManifest = parseSyncManifest(prefs.getString(ScriptPrefs.SYNC_MANIFEST_JSON, "{}"))
        val currentFiles = linkedMapOf<String, RemoteManifestFile>()

        val enabledPackages = prefs.all
            .asSequence()
            .filter { (key, value) -> key.startsWith("app_enabled_") && value == true }
            .map { (key, _) -> key.removePrefix("app_enabled_") }
            .filter { it.isNotBlank() }
            .toList()

        if (enabledPackages.isEmpty()) {
            val cleanup = cleanupStaleRemoteFiles(service, previousManifest.keys)
            prefs.edit()
                .putString(ScriptPrefs.SCRIPT_INDEX_JSON, JSONArray().toString())
                .putString(ScriptPrefs.SYNC_MANIFEST_JSON, buildSyncManifestJson(null, emptyList(), emptyList()).toString())
                .commit()
            Log.d(TAG, "syncEnabledAppsScriptsToRemote: no enabled apps, clear remote index, cleanup=$cleanup")
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
            syncScriptUnitToRemote(service, source, previousManifest, currentFiles)
        }

        val indexJson = scripts.toJson()
        val manifestJson = buildSyncManifestJson(null, scripts, currentFiles.values.toList())
        val staleRemoteNames = previousManifest.keys - currentFiles.keys
        val cleanup = cleanupStaleRemoteFiles(service, staleRemoteNames)

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, indexJson.toString())
            .putString(ScriptPrefs.SYNC_MANIFEST_JSON, manifestJson.toString())
            .commit()

        Log.d(
            TAG,
            "syncEnabledAppsScriptsToRemote: saved index count=${scripts.size}, files=${currentFiles.size}, stale=${staleRemoteNames.size}, cleanup=$cleanup, enabledPackages=${enabledPackages.joinToString()}"
        )
        scripts
    }


    fun syncEnabledScriptsForPackageToRemote(
        service: XposedService?,
        prefs: SharedPreferences?,
        packageName: String,
        allowRootFallback: Boolean = true
    ): Result<List<ScriptMetadata>> = runCatching {
        applyScriptRootFromPrefs(prefs)
        val targetPackage = packageName.trim()
        Log.d(TAG, "syncEnabledScriptsForPackageToRemote: start, service=${service != null}, prefs=${prefs != null}, package=$targetPackage, allowRootFallback=$allowRootFallback")
        if (service == null || prefs == null) {
            throw IllegalStateException("LSPosed 服务未连接，无法同步 Remote Files")
        }
        if (targetPackage.isBlank()) {
            throw IllegalArgumentException("packageName 不能为空")
        }

        val previousManifest = parseSyncManifest(prefs.getString(ScriptPrefs.SYNC_MANIFEST_JSON, "{}"))
        val currentFiles = linkedMapOf<String, RemoteManifestFile>()

        val appEnabled = prefs.getBoolean(ScriptPrefs.appEnabledKey(targetPackage), false)
        if (!appEnabled) {
            val cleanup = cleanupStaleRemoteFiles(service, previousManifest.keys)
            prefs.edit()
                .putString(ScriptPrefs.SCRIPT_INDEX_JSON, JSONArray().toString())
                .putString(ScriptPrefs.SYNC_MANIFEST_JSON, buildSyncManifestJson(targetPackage, emptyList(), emptyList()).toString())
                .commit()
            Log.d(TAG, "syncEnabledScriptsForPackageToRemote: app disabled, clear remote index, package=$targetPackage, cleanup=$cleanup")
            return@runCatching emptyList()
        }

        val sources = readPublicScriptSources(debugPackageName = targetPackage, allowRootFallback = allowRootFallback)
        val selected = sources.filter { source ->
            val supportsPackage = source.metadata.supportsPackage(targetPackage)
            val scriptEnabled = prefs.getBoolean(ScriptPrefs.scriptEnabledKey(targetPackage, source.metadata.id), false)
            Log.d(
                TAG,
                "syncEnabledScriptsForPackageToRemote: inspect id=${source.metadata.id}, supportsPackage=$supportsPackage, scriptEnabled=$scriptEnabled"
            )
            supportsPackage && scriptEnabled
        }

        val scripts = selected.map { source ->
            syncScriptUnitToRemote(service, source, previousManifest, currentFiles)
        }

        val indexJson = scripts.toJson()
        val manifestJson = buildSyncManifestJson(targetPackage, scripts, currentFiles.values.toList())
        val staleRemoteNames = previousManifest.keys - currentFiles.keys
        val cleanup = cleanupStaleRemoteFiles(service, staleRemoteNames)

        prefs.edit()
            .putString(ScriptPrefs.SCRIPT_INDEX_JSON, indexJson.toString())
            .putString(ScriptPrefs.SYNC_MANIFEST_JSON, manifestJson.toString())
            .commit()

        Log.d(
            TAG,
            "syncEnabledScriptsForPackageToRemote: saved index count=${scripts.size}, files=${currentFiles.size}, stale=${staleRemoteNames.size}, cleanup=$cleanup, package=$targetPackage"
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
            remoteName = remoteNameFor(id)
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
                put("path", script.path)
                put("scriptPath", script.path)
                put("kind", script.kind)
                put("entryPath", script.entryPath.ifBlank { script.path })
                put("rootPath", script.rootPath)
                put("hasSettings", script.hasSettings)
                put("settingsPath", script.settingsPath)
                put("settingsSchema", if (script.settingsSchema.isNotBlank()) JSONObject(script.settingsSchema) else JSONObject.NULL)
                put("settingsDefaults", if (script.settingsSchema.isNotBlank()) ScriptSettings.defaults(JSONObject(script.settingsSchema)) else JSONObject())
                put("files", JSONArray().apply {
                    script.files.forEach { asset ->
                        put(JSONObject().apply {
                            put("path", asset.path)
                            put("remoteName", asset.remoteName)
                            put("sha256", asset.sha256)
                        })
                    }
                })
                put("assets", JSONArray().apply {
                    script.assets.forEach { asset ->
                        put(JSONObject().apply {
                            put("path", asset.path)
                            put("remoteName", asset.remoteName)
                            put("sha256", asset.sha256)
                        })
                    }
                })
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
                            path = obj.optString("path", obj.optString("scriptPath", "")),
                            kind = obj.optString("kind", if (obj.optString("path", obj.optString("scriptPath", "")).endsWith("/index.js", ignoreCase = true)) "directory" else "file"),
                            entryPath = obj.optString("entryPath", obj.optString("path", obj.optString("scriptPath", ""))),
                            rootPath = obj.optString("rootPath", ""),
                            hasSettings = obj.optBoolean("hasSettings", false),
                            settingsPath = obj.optString("settingsPath", ""),
                            settingsSchema = obj.optJSONObject("settingsSchema")?.toString() ?: obj.optString("settingsSchema", ""),
                            files = obj.optJSONArray("files").toScriptFileAssets(),
                            assets = obj.optJSONArray("assets").toScriptFileAssets()
                        )
                    )
                }
            }
        }.getOrElse {
            Log.e(TAG, "解析脚本索引失败", it)
            emptyList()
        }
    }


    private fun syncScriptUnitToRemote(
        service: XposedService,
        source: ScriptSource,
        previousManifest: Map<String, String>,
        currentFiles: MutableMap<String, RemoteManifestFile>
    ): ScriptMetadata {
        val metadata = source.metadata
        val entryRemoteName = metadata.remoteName.ifBlank { remoteNameFor(metadata.id) }
        val entryContent = source.content
        val unitFiles = collectScriptUnitFiles(source, entryContent)
        val jsFiles = mutableListOf<ScriptFileAsset>()

        unitFiles.forEach { assetFile ->
            val remoteName = if (assetFile.path == metadata.path || assetFile.path == metadata.entryPath) {
                entryRemoteName
            } else {
                remoteNameForScriptAsset(metadata.id, assetFile.path)
            }
            val sha256 = sha256(assetFile.content)
            val unchanged = previousManifest[remoteName] == sha256
            Log.d(
                TAG,
                "syncScriptUnitToRemote: ${if (unchanged) "skip" else "write"} path=${assetFile.path}, remoteName=$remoteName, sha256=$sha256, id=${metadata.id}, kind=${metadata.kind}, source=${assetFile.source}, chars=${assetFile.content.length}"
            )
            if (!unchanged) {
                writeRemoteFile(service, remoteName, assetFile.content)
            }
            currentFiles[remoteName] = RemoteManifestFile(assetFile.path, remoteName, sha256)
            jsFiles += ScriptFileAsset(assetFile.path, remoteName, sha256)
        }

        if (jsFiles.none { it.path == metadata.path }) {
            val sha256 = sha256(entryContent)
            val unchanged = previousManifest[entryRemoteName] == sha256
            Log.d(TAG, "syncScriptUnitToRemote: entry not in asset list, ${if (unchanged) "skip" else "write"} fallback path=${metadata.path}, remoteName=$entryRemoteName, sha256=$sha256")
            if (!unchanged) {
                writeRemoteFile(service, entryRemoteName, entryContent)
            }
            currentFiles[entryRemoteName] = RemoteManifestFile(metadata.path, entryRemoteName, sha256)
            jsFiles += ScriptFileAsset(metadata.path, entryRemoteName, sha256)
        }

        val runtimeAssets = mutableListOf<ScriptFileAsset>()
        collectScriptAssets(source).forEach { assetFile ->
            val remoteName = remoteNameForScriptAsset(metadata.id, assetFile.path)
            val sha256 = sha256(assetFile.bytes)
            val unchanged = previousManifest[remoteName] == sha256
            Log.d(
                TAG,
                "syncScriptAssetToRemote: ${if (unchanged) "skip" else "write"} path=${assetFile.path}, remoteName=$remoteName, sha256=$sha256, id=${metadata.id}, source=${assetFile.source}, bytes=${assetFile.bytes.size}"
            )
            if (!unchanged) {
                writeRemoteBytes(service, remoteName, assetFile.bytes)
            }
            currentFiles[remoteName] = RemoteManifestFile(assetFile.path, remoteName, sha256)
            runtimeAssets += ScriptFileAsset(assetFile.path, remoteName, sha256)
        }

        return metadata.copy(
            remoteName = entryRemoteName,
            files = jsFiles.distinctBy { it.path },
            assets = runtimeAssets.distinctBy { it.path }
        )
    }


    private fun collectScriptUnitFiles(source: ScriptSource, entryContent: String): List<ScriptUnitFile> {
        val metadata = source.metadata
        if (metadata.kind != "directory" || metadata.rootPath.isBlank()) {
            return listOf(ScriptUnitFile(metadata.path, source.file, entryContent, source.source))
        }

        val result = linkedMapOf<String, ScriptUnitFile>()
        result[metadata.path] = ScriptUnitFile(metadata.path, source.file, entryContent, source.source)

        val rootDir = File(publicScriptsDir, metadata.rootPath)
        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                .sortedBy { relativeScriptPath(it).lowercase() }
                .forEach { file ->
                    val rel = relativeScriptPath(file)
                    if (rel == metadata.path) return@forEach
                    val text = runCatching { file.readText(StandardCharsets.UTF_8) }
                        .onFailure { Log.e(TAG, "collectScriptUnitFiles(File): read failed ${file.absolutePath}", it) }
                        .getOrNull() ?: return@forEach
                    result[rel] = ScriptUnitFile(rel, file, text, "file")
                }
        }

        val settingsFile = File(rootDir, "settings.json")
        if (settingsFile.isFile) {
            val settingsText = runCatching { settingsFile.readText(StandardCharsets.UTF_8) }
                .onFailure { Log.e(TAG, "collectScriptUnitFiles(File): read failed ${settingsFile.absolutePath}", it) }
                .getOrNull()
            if (settingsText != null) {
                result["${metadata.rootPath}/settings.json"] = ScriptUnitFile("${metadata.rootPath}/settings.json", settingsFile, settingsText, "file")
            }
        }

        if (source.source == "root" || result.size <= 1 || (metadata.hasSettings && !result.containsKey("${metadata.rootPath}/settings.json"))) {
            val findCommand = "find ${shellQuote(rootDir.absolutePath)} -type f \\( -name '*.js' -o -name 'settings.json' \\) 2>/dev/null"
            val findResult = runRootCommand(findCommand, timeoutSeconds = 10)
            if (!findResult.timedOut && findResult.exitCode == 0) {
                findResult.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && (it.endsWith(".js", ignoreCase = true) || it.endsWith("/settings.json", ignoreCase = true)) }
                    .forEach { path ->
                        val file = File(path)
                        val rel = relativeScriptPath(file)
                        if (result.containsKey(rel) || rel == metadata.path) return@forEach
                        val cat = runRootCommand("cat ${shellQuote(path)}", timeoutSeconds = 10)
                        if (!cat.timedOut && cat.exitCode == 0) {
                            result[rel] = ScriptUnitFile(rel, file, cat.stdout, "root")
                        }
                    }
            }
        }

        Log.d(TAG, "collectScriptUnitFiles: id=${metadata.id}, root=${metadata.rootPath}, files=${result.keys.joinToString()}")
        return result.values.toList()
    }


    private fun collectScriptAssets(source: ScriptSource): List<ScriptAssetFile> {
        val metadata = source.metadata
        if (metadata.kind != "directory" || metadata.rootPath.isBlank()) return emptyList()

        val rootDir = File(publicScriptsDir, metadata.rootPath)
        val assetsDir = File(rootDir, "assets")
        if (!assetsDir.exists() || !assetsDir.isDirectory) {
            Log.d(TAG, "collectScriptAssets: no assets dir id=${metadata.id}, root=${metadata.rootPath}")
            return emptyList()
        }

        val canonicalAssetsDir = runCatching { assetsDir.canonicalFile }.getOrElse { assetsDir.absoluteFile }
        val result = mutableListOf<ScriptAssetFile>()
        assetsDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { relativeScriptPath(it).lowercase() }
            .forEach { file ->
                val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
                if (!isInside(canonicalAssetsDir, canonical)) {
                    Log.w(TAG, "collectScriptAssets: skip path outside assets by symlink ${file.absolutePath}")
                    return@forEach
                }
                val rel = relativeScriptPath(canonical)
                val bytes = runCatching { canonical.readBytes() }
                    .onFailure { Log.e(TAG, "collectScriptAssets: read failed ${canonical.absolutePath}", it) }
                    .getOrNull() ?: return@forEach
                result += ScriptAssetFile(rel, canonical, bytes, "file")
            }
        Log.d(TAG, "collectScriptAssets: id=${metadata.id}, root=${metadata.rootPath}, assets=${result.map { it.path }.joinToString()}")
        return result
    }

    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }


    private fun parseSyncManifest(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val files = root.optJSONArray("files") ?: JSONArray()
            buildMap {
                for (i in 0 until files.length()) {
                    val obj = files.optJSONObject(i) ?: continue
                    val remoteName = obj.optString("remoteName", "").trim()
                    val sha256 = obj.optString("sha256", "").trim()
                    if (remoteName.isNotBlank() && sha256.isNotBlank()) put(remoteName, sha256)
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "parseSyncManifest failed; treat as empty", error)
            emptyMap()
        }
    }

    private fun buildSyncManifestJson(
        packageName: String?,
        scripts: List<ScriptMetadata>,
        files: List<RemoteManifestFile>
    ): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("packageName", packageName ?: JSONObject.NULL)
            put("generatedAt", System.currentTimeMillis())
            put("fileCount", files.size)
            put("scripts", JSONArray().apply {
                scripts.forEach { script ->
                    put(JSONObject().apply {
                        put("id", script.id)
                        put("path", script.path)
                        put("entryPath", script.entryPath.ifBlank { script.path })
                        put("kind", script.kind)
                        put("rootPath", script.rootPath)
                        put("remoteName", script.remoteName.ifBlank { remoteNameFor(script.id) })
                        put("files", JSONArray().apply {
                            script.files.forEach { asset ->
                                put(JSONObject().apply {
                                    put("path", asset.path)
                                    put("remoteName", asset.remoteName)
                                    put("sha256", asset.sha256)
                                })
                            }
                        })
                        put("assets", JSONArray().apply {
                            script.assets.forEach { asset ->
                                put(JSONObject().apply {
                                    put("path", asset.path)
                                    put("remoteName", asset.remoteName)
                                    put("sha256", asset.sha256)
                                })
                            }
                        })
                    })
                }
            })
            put("files", JSONArray().apply {
                files.sortedBy { it.remoteName }.forEach { file ->
                    put(JSONObject().apply {
                        put("path", file.path)
                        put("remoteName", file.remoteName)
                        put("sha256", file.sha256)
                    })
                }
            })
        }
    }

    private fun cleanupStaleRemoteFiles(service: XposedService, staleRemoteNames: Collection<String>): JSONObject {
        val deleted = JSONArray()
        val tombstoned = JSONArray()
        val failed = JSONArray()
        staleRemoteNames.filter { it.isNotBlank() }.distinct().forEach { remoteName ->
            val deleteResult = tryDeleteRemoteFile(service, remoteName)
            if (deleteResult == true) {
                deleted.put(remoteName)
            } else {
                // Some LSPosed service versions do not expose a delete API. In that
                // case the file is no longer referenced by script_index/manifest, and
                // we overwrite it with a tiny tombstone when possible so stale code is
                // not accidentally readable as a valid script asset.
                runCatching {
                    writeRemoteFile(service, remoteName, "// XiaoHeiHook stale Remote File tombstone. This file is no longer referenced.\n")
                    tombstoned.put(remoteName)
                }.onFailure { error ->
                    failed.put(JSONObject().put("remoteName", remoteName).put("error", error.message ?: error.javaClass.simpleName))
                }
            }
        }
        return JSONObject()
            .put("deleted", deleted)
            .put("tombstoned", tombstoned)
            .put("failed", failed)
    }

    private fun tryDeleteRemoteFile(service: XposedService, remoteName: String): Boolean? {
        val names = listOf("deleteRemoteFile", "removeRemoteFile", "deleteFile", "removeFile")
        for (name in names) {
            val method = runCatching { service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java } }.getOrNull()
                ?: continue
            val result = runCatching { method.invoke(service, remoteName) }
                .onFailure { Log.w(TAG, "cleanupStaleRemoteFiles: $name failed for $remoteName", it) }
                .getOrNull()
            Log.d(TAG, "cleanupStaleRemoteFiles: invoked $name($remoteName), result=$result")
            return if (result is Boolean) result else true
        }
        return null
    }


    private fun JSONArray?.toScriptFileAssets(): List<ScriptFileAsset> {
        if (this == null) return emptyList()
        val list = mutableListOf<ScriptFileAsset>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val path = obj.optString("path", "").trim().replace('\\', '/')
            val remoteName = obj.optString("remoteName", "").trim()
            val sha256 = obj.optString("sha256", "").trim()
            if (path.isNotBlank() && remoteName.isNotBlank()) {
                list += ScriptFileAsset(path.trim('/'), remoteName, sha256)
            }
        }
        return list
    }


    private fun isRootScriptEntryPath(path: String): Boolean {
        val relative = relativeScriptPath(File(path))
        if (relative.isBlank()) return false
        if (!relative.contains('/')) {
            return relative.endsWith(".js", ignoreCase = true)
        }
        return relative.count { it == '/' } == 1 && relative.endsWith("/index.js", ignoreCase = true)
    }

    private fun relativeScriptPath(file: File): String {
        val root = runCatching { publicScriptsDir.canonicalFile }.getOrElse { publicScriptsDir.absoluteFile }
        val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        val rootPath = root.path.replace('\\', '/').trimEnd('/')
        val targetPath = target.path.replace('\\', '/')
        val relative = if (targetPath == rootPath) {
            target.name
        } else if (targetPath.startsWith("$rootPath/")) {
            targetPath.substring(rootPath.length + 1)
        } else {
            target.name
        }
        return relative.replace('\\', '/').trim('/').ifBlank { file.name }
    }

    fun remoteNameForScriptAsset(id: String, path: String): String {
        val cleanId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "unnamed" }
        val cleanPath = path.replace('\\', '/').replace(Regex("[^A-Za-z0-9_./-]"), "_").trim('/').replace('/', '_')
        val suffix = sha1("$id:$path").take(10)
        return "script_asset_${cleanId}_${cleanPath.ifBlank { "file" }}_$suffix"
    }


    fun remoteNameFor(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        val suffix = sha1(id).take(8)
        return "script_${cleaned.ifBlank { "unnamed" }}_$suffix"
    }

    private fun writeRemoteFile(service: XposedService, name: String, content: String) {
        writeRemoteBytes(service, name, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeRemoteBytes(service: XposedService, name: String, bytes: ByteArray) {
        val pfd: ParcelFileDescriptor = service.openRemoteFile(name)
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            out.channel.truncate(0)
            out.write(bytes)
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

    fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

    fun sha256(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
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
