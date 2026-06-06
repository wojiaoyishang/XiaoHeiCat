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
                        prefs.edit().putBoolean(ScriptPrefs.appEnabledKey(packageName), true).apply()
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

    private fun main(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
