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
    val sourceMode: String = "local",
    val url: String = "",
    val urlRefreshOnApply: Boolean = false,
    /** Remote JS files belonging to this script unit. For directory scripts this includes index.js and dependency .js files. */
    val files: List<ScriptFileAsset> = emptyList()
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

    fun appEnabledKey(packageName: String): String = "app_enabled_$packageName"

    fun scriptEnabledKey(packageName: String, scriptId: String): String =
        "script_enabled_${packageName}_${scriptId}"
}
