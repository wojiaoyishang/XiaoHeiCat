package top.lovepikachu.XiaoHeiHook.data

import android.graphics.Bitmap

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val icon: Bitmap?,
    val isSystemApp: Boolean
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
    val sourceMode: String = "local",
    val url: String = "",
    val urlRefreshOnApply: Boolean = false
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

    fun appEnabledKey(packageName: String): String = "app_enabled_$packageName"

    fun scriptEnabledKey(packageName: String, scriptId: String): String =
        "script_enabled_${packageName}_${scriptId}"
}
