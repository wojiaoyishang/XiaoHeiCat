package top.lovepikachu.XiaoHeiHook.data

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication

object ScopeController {
    fun setAppEnabled(
        packageName: String,
        enabled: Boolean,
        onApproved: (List<String>) -> Unit = {},
        onFailed: (String) -> Unit = {}
    ) {
        val service = XiaoHeiApplication.xposedService
        val prefs = XiaoHeiApplication.remotePreferences

        if (service == null || prefs == null) {
            onFailed("LSPosed 服务未连接")
            return
        }

        if (enabled) {
            service.requestScope(
                listOf(packageName),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        val editor = prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), true)
                        if (!prefs.contains(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName))) {
                            editor.putBoolean(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName), true)
                        }
                        if (!prefs.contains(ScriptPrefs.targetScriptCacheDirKey(packageName))) {
                            editor.putString(ScriptPrefs.targetScriptCacheDirKey(packageName), ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR)
                        }
                        editor.apply()
                        main { onApproved(approved) }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), false).apply()
                        main { onFailed(message) }
                    }
                }
            )
        } else {
            service.removeScope(listOf(packageName))
            prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), false).apply()
            onApproved(emptyList())
        }
    }

    fun isAppEnabled(prefs: SharedPreferences?, packageName: String): Boolean {
        return prefs?.getBoolean(ScriptPrefs.appEnabledKey(packageName), false) ?: false
    }

    fun setScriptEnabled(packageName: String, scriptId: String, enabled: Boolean) {
        XiaoHeiApplication.remotePreferences
            ?.edit()
            ?.putBoolean(ScriptPrefs.scriptEnabledKey(packageName, scriptId), enabled)
            ?.apply()
    }

    fun isScriptEnabled(packageName: String, scriptId: String): Boolean {
        return XiaoHeiApplication.remotePreferences
            ?.getBoolean(ScriptPrefs.scriptEnabledKey(packageName, scriptId), false)
            ?: false
    }

    fun setCacheScriptsToPrivateDir(packageName: String, enabled: Boolean) {
        XiaoHeiApplication.remotePreferences
            ?.edit()
            ?.putBoolean(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName), enabled)
            ?.putBoolean(ScriptPrefs.targetScriptCacheCleanupRequestedKey(packageName), !enabled)
            ?.apply()
        if (!enabled) {
            val dir = targetScriptCacheDir(packageName)
            Thread { ScriptRepository.clearTargetScriptCacheByRoot(packageName, dir) }.start()
        }
    }

    fun isCacheScriptsToPrivateDirEnabled(packageName: String): Boolean {
        return XiaoHeiApplication.remotePreferences
            ?.getBoolean(ScriptPrefs.cacheScriptsToPrivateDirKey(packageName), true)
            ?: true
    }

    fun setTargetScriptCacheDir(packageName: String, path: String): String {
        val normalized = ScriptPrefs.normalizeTargetScriptCacheDir(path)
        XiaoHeiApplication.remotePreferences
            ?.edit()
            ?.putString(ScriptPrefs.targetScriptCacheDirKey(packageName), normalized)
            ?.apply()
        return normalized
    }

    fun targetScriptCacheDir(packageName: String): String {
        val raw = XiaoHeiApplication.remotePreferences
            ?.getString(ScriptPrefs.targetScriptCacheDirKey(packageName), ScriptPrefs.DEFAULT_TARGET_SCRIPT_CACHE_DIR)
        return ScriptPrefs.normalizeTargetScriptCacheDir(raw)
    }

    fun setFileLoggingDisabled(disabled: Boolean) {
        XiaoHeiApplication.remotePreferences
            ?.edit()
            ?.putBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, disabled)
            ?.apply()
    }

    fun isFileLoggingDisabled(): Boolean {
        return XiaoHeiApplication.remotePreferences
            ?.getBoolean(ScriptPrefs.DISABLE_FILE_LOGGING, false)
            ?: false
    }

    private fun main(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
