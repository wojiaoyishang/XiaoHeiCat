package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

object RemotePrefsDiagnostics {
    private const val TAG = "XiaoHeiHook-PrefsDiag"
    private const val MIN_INTERVAL_MS = 3_000L
    private const val MAX_IDS_PER_LINE = 18
    private val lastLogAt = AtomicLong(0L)

    fun logOnAppOpen(context: Context?, prefs: SharedPreferences?, reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastLogAt.get()
            if (now - last < MIN_INTERVAL_MS) return
            if (!lastLogAt.compareAndSet(last, now)) return
        } else {
            lastLogAt.set(now)
        }

        if (prefs == null) {
            Log.w(TAG, "[$reason] LSPosed Remote Preferences not bound yet; wait for xposed service bind")
            return
        }

        runCatching {
            val all = prefs.all
            val enabledPackages = all
                .asSequence()
                .filter { (key, value) -> key.startsWith("app_enabled_") && value == true }
                .map { (key, _) -> key.removePrefix("app_enabled_") }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()

            val legacyIndexRaw = prefs.getString(ScriptPrefs.SCRIPT_INDEX_JSON, null)
            val legacyHashRaw = prefs.getString(ScriptPrefs.SCRIPT_HASH_CONFIG_JSON, null)
            val legacyManifestRaw = prefs.getString(ScriptPrefs.SYNC_MANIFEST_JSON, null)
            val metadataRaw = prefs.getString(ScriptPrefs.SCRIPT_METADATA_CACHE_JSON, null)
            val legacyRuntimeIndex = ScriptRepository.parseIndex(legacyIndexRaw)
            val packageIndexKeys = all.keys.filter { ScriptPrefs.isPackageScopedKey(it, ScriptPrefs.SCRIPT_INDEX_JSON) }.sorted()
            val packageHashKeys = all.keys.filter { ScriptPrefs.isPackageScopedKey(it, ScriptPrefs.SCRIPT_HASH_CONFIG_JSON) }.sorted()
            val packageManifestKeys = all.keys.filter { ScriptPrefs.isPackageScopedKey(it, ScriptPrefs.SYNC_MANIFEST_JSON) }.sorted()
            val legacyHashFiles = parseObjectOrNull(legacyHashRaw)?.optJSONArray("files") ?: JSONArray()
            val legacyManifestFiles = parseObjectOrNull(legacyManifestRaw)?.optJSONArray("files") ?: JSONArray()
            val metadataItems = parseArrayOrNull(metadataRaw) ?: JSONArray()

            Log.i(
                TAG,
                "[$reason] RemotePrefs summary: keys=${all.size}, enabledApps=${enabledPackages.size}, " +
                    "legacyScriptIndexLength=${legacyIndexRaw?.length ?: 0}, legacyScriptIndexSha256=${sha256Short(legacyIndexRaw)}, legacyScriptIndexCount=${legacyRuntimeIndex.size}, " +
                    "packageScriptIndexKeys=${packageIndexKeys.size}, packageHashKeys=${packageHashKeys.size}, packageManifestKeys=${packageManifestKeys.size}, " +
                    "legacyHashConfigLength=${legacyHashRaw?.length ?: 0}, legacyHashFileCount=${legacyHashFiles.length()}, legacyManifestFileCount=${legacyManifestFiles.length()}, " +
                    "metadataCacheCount=${metadataItems.length()}, metadataUpdatedAt=${prefs.getLong(ScriptPrefs.SCRIPT_METADATA_CACHE_UPDATED_AT, 0L)}, " +
                    "scriptRoot=${prefs.getString(ScriptPrefs.SCRIPT_ROOT, prefs.getString(ScriptPrefs.SCRIPT_ROOT_LEGACY, ""))}, " +
                    "disableFileLogging=${prefs.getBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, false)}, " +
                    "useRootScriptCacheSync=${prefs.getBoolean(ScriptPrefs.USE_ROOT_SCRIPT_CACHE_SYNC, true)}"
            )

            if (enabledPackages.isEmpty()) {
                Log.w(TAG, "[$reason] RemotePrefs enabled apps empty; no target app can execute scripts after reboot")
                return
            }

            enabledPackages.forEach { packageName ->
                logPackage(
                    context = context,
                    prefs = prefs,
                    all = all,
                    packageName = packageName,
                    reason = reason
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "[$reason] dump Remote Preferences failed", error)
        }
    }

    private fun logPackage(
        context: Context?,
        prefs: SharedPreferences,
        all: Map<String, *>,
        packageName: String,
        reason: String
    ) {
        val appKey = ScriptPrefs.appEnabledKey(packageName)
        val cacheKey = ScriptPrefs.cacheScriptsToPrivateDirKey(packageName)
        val dirKey = ScriptPrefs.targetScriptCacheDirKey(packageName)
        val cleanupKey = ScriptPrefs.targetScriptCacheCleanupRequestedKey(packageName)
        val selectedIds = all
            .asSequence()
            .filter { (key, value) -> key.startsWith("script_enabled_${packageName}_") && value == true }
            .map { (key, _) -> key.removePrefix("script_enabled_${packageName}_") }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
        val packageIndexKey = ScriptPrefs.scriptIndexJsonKey(packageName)
        val packageHashKey = ScriptPrefs.scriptHashConfigJsonKey(packageName)
        val packageManifestKey = ScriptPrefs.syncManifestJsonKey(packageName)
        val indexRaw = prefs.getString(packageIndexKey, null)
        val runtimeIndex = ScriptRepository.parseIndex(indexRaw)
        val runtimeById = runtimeIndex.associateBy { it.id }
        val hashRaw = prefs.getString(packageHashKey, null)
        val hashRoot = parseObjectOrNull(hashRaw)
        val manifestRaw = prefs.getString(packageManifestKey, null)
        val manifestFiles = parseObjectOrNull(manifestRaw)?.optJSONArray("files") ?: JSONArray()
        val selectedInIndex = selectedIds.filter { runtimeById[it]?.supportsPackage(packageName) == true }
        val selectedMissingFromIndex = selectedIds - selectedInIndex.toSet()
        val supportedInIndex = runtimeIndex.filter { it.supportsPackage(packageName) }.map { it.id }.distinct().sorted()
        val disabledButInIndex = supportedInIndex.filter { !selectedIds.contains(it) }
        val hashEntriesForPackage = countHashEntriesForPackage(hashRoot, packageName)
        val appInfo = packageInfoSummary(context, packageName)

        Log.i(
            TAG,
            "[$reason][$packageName] appEnabled=${prefs.getBoolean(appKey, false)} containsAppKey=${prefs.contains(appKey)}, " +
                "packageIndexKey=$packageIndexKey containsPackageIndex=${prefs.contains(packageIndexKey)} packageIndexLength=${indexRaw?.length ?: 0} packageIndexSha256=${sha256Short(indexRaw)}, " +
                "packageHashKey=$packageHashKey containsPackageHash=${prefs.contains(packageHashKey)} packageHashLength=${hashRaw?.length ?: 0} packageManifestFiles=${manifestFiles.length()}, " +
                "cacheToPrivateDir=${prefs.getBoolean(cacheKey, true)} containsCacheKey=${prefs.contains(cacheKey)}, " +
                "targetCacheDirRaw=${prefs.getString(dirKey, "<missing>")}, targetCacheDirNormalized=${ScriptPrefs.normalizeTargetScriptCacheDir(prefs.getString(dirKey, ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR))}, containsDirKey=${prefs.contains(dirKey)}, " +
                "cleanupRequested=${prefs.getBoolean(cleanupKey, false)} containsCleanupKey=${prefs.contains(cleanupKey)}, " +
                "selectedScripts=${selectedIds.size}, selectedInPackageIndex=${selectedInIndex.size}, supportedPackageIndex=${supportedInIndex.size}, hashEntriesForPackage=$hashEntriesForPackage, $appInfo"
        )
        logIds(reason, packageName, "selectedScriptIds", selectedIds)
        logIds(reason, packageName, "selectedMissingFromPackageIndex", selectedMissingFromIndex)
        logIds(reason, packageName, "supportedPackageIndexScriptIds", supportedInIndex)
        if (disabledButInIndex.isNotEmpty()) {
            logIds(reason, packageName, "packageIndexButDisabledByPrefs", disabledButInIndex)
        }

        selectedIds.forEach { scriptId ->
            val script = runtimeById[scriptId]
            val enabled = prefs.getBoolean(ScriptPrefs.scriptEnabledKey(packageName, scriptId), false)
            if (script == null) {
                Log.w(TAG, "[$reason][$packageName][$scriptId] enabled=$enabled but missing from package script_index_json: key=$packageIndexKey")
            } else {
                Log.i(
                    TAG,
                    "[$reason][$packageName][$scriptId] enabled=$enabled, supportsPackage=${script.supportsPackage(packageName)}, " +
                        "remoteName=${script.remoteName}, path=${script.path}, files=${script.files.size}, assets=${script.assets.size}"
                )
            }
        }
    }

    private fun countHashEntriesForPackage(hashRoot: JSONObject?, packageName: String): Int {
        val pkg = hashRoot
            ?.optJSONObject("byPackage")
            ?.optJSONObject(packageName)
            ?: return 0
        val byRemoteName = pkg.optJSONObject("byRemoteName")
        val byPath = pkg.optJSONObject("byPath")
        return (byRemoteName?.length() ?: 0).coerceAtLeast(byPath?.length() ?: 0)
    }

    private fun logIds(reason: String, packageName: String, label: String, ids: List<String>) {
        if (ids.isEmpty()) {
            Log.i(TAG, "[$reason][$packageName] $label=empty")
            return
        }
        ids.chunked(MAX_IDS_PER_LINE).forEachIndexed { index, chunk ->
            Log.i(TAG, "[$reason][$packageName] $label[$index]=${chunk.joinToString(",")}")
        }
    }

    private fun packageInfoSummary(context: Context?, packageName: String): String {
        if (context == null) return "pm=unavailable"
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            "uid=${info.uid}, dataDir=${info.dataDir}, enabled=${info.enabled}"
        }.getOrElse { error ->
            "pmError=${error.javaClass.simpleName}:${error.message.orEmpty()}"
        }
    }

    private fun parseObjectOrNull(raw: String?): JSONObject? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || text == "null") return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseArrayOrNull(raw: String?): JSONArray? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || text == "null") return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    private fun sha256Short(raw: String?): String {
        if (raw.isNullOrEmpty()) return "<empty>"
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(12)
        }.getOrDefault("<error>")
    }
}
