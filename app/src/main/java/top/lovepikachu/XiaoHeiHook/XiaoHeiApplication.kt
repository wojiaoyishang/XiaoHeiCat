package top.lovepikachu.XiaoHeiHook

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class XiaoHeiApplication : Application() {

    data class ModuleState(
        val isActivated: Boolean = false,
        val frameworkName: String? = null,
        val frameworkVersion: String? = null,
        val frameworkAPIVersion: Int? = null
    )

    companion object {
        private val _moduleState = MutableStateFlow(ModuleState())
        val moduleState: StateFlow<ModuleState> = _moduleState.asStateFlow()

        var xposedService: XposedService? = null
            private set

        var remotePreferences: SharedPreferences? = null
            private set

        fun getRemotePreferences(name: String = "XiaoHeiHookSetting"): SharedPreferences? {
            return xposedService?.getRemotePreferences(name)
        }
    }

    override fun onCreate() {
        super.onCreate()

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
            }

            override fun onServiceDied(service: XposedService) {
                _moduleState.value = ModuleState(isActivated = false)
                xposedService = null
                remotePreferences = null
            }
        })
    }
}