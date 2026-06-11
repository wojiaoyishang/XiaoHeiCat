package top.lovepikachu.XiaoHeiHook

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.data.RemotePrefsDiagnostics
import top.lovepikachu.XiaoHeiHook.keepalive.MainProcessKeepAliveService
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.webide.ProcessUtil
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager

class XiaoHeiApplication : Application() {

    private var resetVolatileServicesOnBind: Boolean = false

    data class ModuleState(
        val isActivated: Boolean = false,
        val frameworkName: String? = null,
        val frameworkVersion: String? = null,
        val frameworkAPIVersion: Int? = null
    )

    companion object {
        private const val TAG = "XiaoHeiHook-App"
        private val _moduleState = MutableStateFlow(ModuleState())
        val moduleState: StateFlow<ModuleState> = _moduleState.asStateFlow()

        var xposedService: XposedService? = null
            private set

        var remotePreferences: SharedPreferences? = null
            private set

        lateinit var appContext: XiaoHeiApplication
            private set

        fun getRemotePreferences(name: String = "XiaoHeiHookSetting"): SharedPreferences? {
            return xposedService?.getRemotePreferences(name)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this

        val currentProcess = ProcessUtil.currentProcessName(this)
        if (currentProcess == packageName) {
            resetVolatileServicesOnBind = true
            Log.i(TAG, "main process created; recover WebIDE if enabled and reset volatile MCP state on app start")
            WebIdeManager.ensureStartedIfEnabled(this)
            McpManager.resetOnApplicationStart(this)
            MainProcessKeepAliveService.stopIfNotNeeded(this)
        } else {
            Log.i(TAG, "non-main process created; skip volatile WebIDE/MCP reset, process=$currentProcess")
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {

            override fun onServiceBind(service: XposedService) {
                _moduleState.value = ModuleState(
                    isActivated = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    frameworkAPIVersion = service.getApiVersion()
                )

                xposedService = service
                remotePreferences = getRemotePreferences()
                ScriptRepository.applyScriptRootFromPrefs(remotePreferences)
                if (currentProcess == packageName) {
                    RemotePrefsDiagnostics.logOnAppOpen(this@XiaoHeiApplication, remotePreferences, "xposed-service-bound", force = true)
                }
                if (resetVolatileServicesOnBind) {
                    resetVolatileServicesOnBind = false
                    Log.i(TAG, "xposed service bound in main process; refresh remote MCP disabled state")
                    McpManager.resetOnApplicationStart(this@XiaoHeiApplication)
                } else {
                    McpManager.syncStatusWithSavedConfig(this@XiaoHeiApplication)
                    MainProcessKeepAliveService.startIfNeeded(this@XiaoHeiApplication, MainProcessKeepAliveService.REASON_BRIDGE)
                }
            }

            override fun onServiceDied(service: XposedService) {
                _moduleState.value = ModuleState(isActivated = false)
                xposedService = null
                remotePreferences = null
            }
        })
    }
}