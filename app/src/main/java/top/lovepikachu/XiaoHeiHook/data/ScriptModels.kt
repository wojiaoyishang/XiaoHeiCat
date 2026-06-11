package top.lovepikachu.XiaoHeiHook.data

import android.graphics.Bitmap

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val icon: Bitmap?,
    val isSystemApp: Boolean
)

data class ScriptFileAsset(
    val path: String,
    val remoteName: String,
    /** SHA-256 of the UTF-8 content that was synchronized into Remote Files. */
    val sha256: String = ""
)

data class ScriptMetadata(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "Unknown",
    val description: String = "",
    val targets: List<String> = emptyList(),
    val processes: List<String> = emptyList(),
    val runAt: String = "package-loaded",
    val grants: List<String> = emptyList(),
    val remoteName: String = "",
    /**
     * Relative path under Documents/XiaoHeiHook, e.g. qidian.js or folder/demo.js.
     * This is the canonical key used by WebIDE, Rhino sourceName, and line breakpoints.
     */
    val path: String = "",
    /** Script unit kind: file for root *.js, directory for root folder/index.js. */
    val kind: String = "file",
    /** Entry file relative path. Equal to path for script units, e.g. qidian.js or toolbox/index.js. */
    val entryPath: String = "",
    /** Root folder for directory scripts, otherwise empty. */
    val rootPath: String = "",
    /** Whether this directory script declares settings.json. Single-file scripts do not support settings. */
    val hasSettings: Boolean = false,
    /** Relative path of settings.json under Documents/XiaoHeiHook. */
    val settingsPath: String = "",
    /** Normalized settings schema JSON. Empty when hasSettings=false. */
    val settingsSchema: String = "",
    /** Remote JS/files belonging to this script unit. For directory scripts this includes index.js, dependencies and settings.json. */
    val files: List<ScriptFileAsset> = emptyList(),
    /** Remote files for current script assets/. These are not require-able JS modules. */
    val assets: List<ScriptFileAsset> = emptyList()
) {
    fun supportsPackage(packageName: String): Boolean {
        return targets.isEmpty() || targets.any { it == "*" || it == packageName }
    }

    fun supportsAnyPackage(packageNames: Collection<String>): Boolean {
        return packageNames.any { supportsPackage(it) }
    }
}

object ScriptPrefs {
    const val GROUP = "XiaoHeiHookSetting"
    const val SCRIPT_INDEX_JSON = "script_index_json"
    const val SYNC_MANIFEST_JSON = "script_sync_manifest_json"
    /**
     * Dedicated Remote Preferences hash index used by target processes to verify
     * Remote Files and target private cache even when a compact runtime index omits hashes.
     */
    const val SCRIPT_HASH_CONFIG_JSON = "script_hash_config_json"
    /**
     * UI-only metadata cache for all discovered scripts.
     *
     * Do not use this key for runtime execution. SCRIPT_INDEX_JSON intentionally contains only
     * scripts synchronized into LSPosed Remote Files, while this cache keeps every discovered
     * script so mobile UI lists do not shrink after syncing only enabled scripts.
     */
    const val SCRIPT_METADATA_CACHE_JSON = "script_metadata_cache_json"
    const val SCRIPT_METADATA_CACHE_UPDATED_AT = "script_metadata_cache_updated_at"
    /**
     * Number of discovered script units when SCRIPT_METADATA_CACHE_JSON was last saved.
     * Used by the app detail page for a cheap soft refresh: if the current file count
     * differs, rebuild the metadata cache; if it is unchanged, keep the cached metadata.
     */
    const val SCRIPT_METADATA_CACHE_FILE_COUNT = "script_metadata_cache_file_count"
    const val DISABLE_FILE_LOGGING = "disableFileLogging"
    const val USE_ROOT_SCRIPT_CACHE_SYNC = "useRootScriptCacheSync"
    const val SCRIPT_ROOT = "scriptRoot"
    const val SCRIPT_ROOT_LEGACY = "script.root"
    const val DEFAULT_TARGET_SCRIPT_CACHE_DIR = ".xhh_scripts"

    fun packageScopedKey(packageName: String, key: String): String = "${packageName.trim()}_$key"

    fun scriptIndexJsonKey(packageName: String): String =
        packageScopedKey(packageName, SCRIPT_INDEX_JSON)

    fun syncManifestJsonKey(packageName: String): String =
        packageScopedKey(packageName, SYNC_MANIFEST_JSON)

    fun scriptHashConfigJsonKey(packageName: String): String =
        packageScopedKey(packageName, SCRIPT_HASH_CONFIG_JSON)

    fun isPackageScopedKey(key: String, baseKey: String): Boolean =
        key.endsWith("_$baseKey") && key != baseKey

    fun packageNameFromScopedKey(key: String, baseKey: String): String? {
        val suffix = "_$baseKey"
        return key.takeIf { isPackageScopedKey(it, baseKey) }
            ?.removeSuffix(suffix)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun appEnabledKey(packageName: String): String = "app_enabled_$packageName"

    fun cacheScriptsToPrivateDirKey(packageName: String): String =
        "cache_scripts_to_private_dir_$packageName"

    fun targetScriptCacheDirKey(packageName: String): String =
        "target_script_cache_dir_$packageName"

    fun targetScriptCacheCleanupRequestedKey(packageName: String): String =
        "target_script_cache_cleanup_requested_$packageName"

    fun normalizeTargetScriptCacheDir(raw: String?): String {
        var value = (raw ?: "").trim().replace("\\", "/")
        while (value.startsWith("/")) value = value.removePrefix("/")
        while (value.contains("//")) value = value.replace("//", "/")
        value = value.trim('/')
        if (value.isBlank()) return DEFAULT_TARGET_SCRIPT_CACHE_DIR
        if (value == DEFAULT_TARGET_SCRIPT_CACHE_DIR) return DEFAULT_TARGET_SCRIPT_CACHE_DIR

        val rawParts = value.split('/').filter { it.isNotBlank() }
        if (rawParts.isEmpty() || rawParts.any { it == "." || it == ".." }) {
            return DEFAULT_TARGET_SCRIPT_CACHE_DIR
        }
        // Only the built-in default uses a hidden dot-prefixed directory. Custom folders are
        // normalized to visible directory names even if the user types a leading dot.
        val parts = rawParts.map { it.trimStart('.') }
        if (parts.any { it.isBlank() }) return DEFAULT_TARGET_SCRIPT_CACHE_DIR
        val safePart = Regex("^[A-Za-z0-9._-]{1,80}$")
        if (parts.any { !safePart.matches(it) }) {
            return DEFAULT_TARGET_SCRIPT_CACHE_DIR
        }
        return parts.joinToString("/")
    }

    fun scriptEnabledKey(packageName: String, scriptId: String): String =
        "script_enabled_${packageName}_${scriptId}"

    fun scriptSettingsKey(packageName: String, scriptId: String): String =
        ScriptSettings.settingsKey(packageName, scriptId)
}
