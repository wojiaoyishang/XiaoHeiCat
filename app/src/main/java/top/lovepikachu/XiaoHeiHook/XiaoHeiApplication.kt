package top.lovepikachu.XiaoHeiHook

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class XiaoHeiApplication : Application() {

    companion object {
        // 模块激活状态标志位（供 UI 及其他组件读取）
        var isModuleActivated: Boolean = false
            private set

        var xposedService: XposedService? = null
            private set

        var frameworkName: String? = null
            private set

        var frameworkVersion: String? = null
            private set

        var frameworkAPIVersion: Int? = null
            private set

        var remotePreferences: SharedPreferences? = null
            private set

        fun getRemotePreferences(name: String = "XiaoHeiHookSetting"): SharedPreferences? {
            return xposedService?.getRemotePreferences(name)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 注册 Xposed 服务监听器
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {

            override fun onServiceBind(service: XposedService) {
                isModuleActivated = true

                xposedService = service
                frameworkName = service.frameworkName
                frameworkVersion = service.frameworkVersion
                frameworkAPIVersion = service.getApiVersion()

                remotePreferences = getRemotePreferences()
            }

            override fun onServiceDied(service: XposedService) {
                isModuleActivated = false
                xposedService = null
                remotePreferences = null
                frameworkName = null
                frameworkVersion = null
            }

        })
    }
}