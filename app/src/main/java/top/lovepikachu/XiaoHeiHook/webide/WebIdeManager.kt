package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.lovepikachu.XiaoHeiHook.keepalive.AccessibilityKeepAliveManager
import top.lovepikachu.XiaoHeiHook.keepalive.MainProcessKeepAliveService

object WebIdeManager {
    private val _status = MutableStateFlow(WebIdeStatus())
    val status: StateFlow<WebIdeStatus> = _status.asStateFlow()

    fun loadConfig(context: Context): WebIdeConfig {
        val prefs = context.applicationContext.getSharedPreferences(WebIdeDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        return WebIdeConfig(
            enabled = prefs.getBoolean(WebIdeDefaults.KEY_ENABLED, false),
            host = prefs.getString(WebIdeDefaults.KEY_HOST, WebIdeDefaults.DEFAULT_HOST)?.ifBlank { WebIdeDefaults.DEFAULT_HOST }
                ?: WebIdeDefaults.DEFAULT_HOST,
            port = prefs.getInt(WebIdeDefaults.KEY_PORT, WebIdeDefaults.DEFAULT_PORT).coerceIn(1024, 65535)
        )
    }

    fun saveConfig(context: Context, config: WebIdeConfig) {
        // commit() 而不是 apply()：WebIDE 服务运行在 :webide 独立进程，立即落盘能降低跨进程读取旧值的概率。
        context.applicationContext.getSharedPreferences(WebIdeDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WebIdeDefaults.KEY_ENABLED, config.enabled)
            .putString(WebIdeDefaults.KEY_HOST, sanitizeHost(config.host))
            .putInt(WebIdeDefaults.KEY_PORT, config.port.coerceIn(1024, 65535))
            .commit()
    }

    /**
     * UI 只负责保存配置并启动前台服务。
     * HTTP Server 只允许由 WebIdeForegroundService(:webide) 持有，避免主 UI 进程切后台后请求被系统冻结。
     */
    fun start(context: Context, host: String, port: Int): Result<WebIdeStatus> = runCatching {
        val appContext = context.applicationContext
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        require(safePort in 1024..65535) { "端口必须是 1024 到 65535" }

        val pendingStatus = WebIdeStatus(running = true, host = safeHost, port = safePort, lastError = null)
        _status.value = pendingStatus
        saveConfig(appContext, WebIdeConfig(enabled = true, host = safeHost, port = safePort))

        val intent = WebIdeForegroundService.startIntent(appContext, safeHost, safePort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        MainProcessKeepAliveService.startIfNeeded(appContext, MainProcessKeepAliveService.REASON_WEBIDE)

        pendingStatus
    }.onFailure { error ->
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        _status.value = WebIdeStatus(
            running = false,
            host = safeHost,
            port = safePort,
            lastError = error.message ?: error.javaClass.simpleName
        )
        saveConfig(context, WebIdeConfig(enabled = false, host = safeHost, port = safePort))
        AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(context)
    }

    /**
     * UI 或通知动作停止 WebIDE。真正 stop 在 :webide 前台服务进程内完成。
     */
    fun stop(context: Context? = null) {
        val appContext = context?.applicationContext
        val current = _status.value
        _status.value = current.copy(running = false, lastError = null)
        if (appContext != null) {
            saveConfig(appContext, WebIdeConfig(enabled = false, host = current.host, port = current.port))
            val stopResult = runCatching { appContext.startService(WebIdeForegroundService.stopIntent(appContext)) }
            if (stopResult.isFailure) {
                // 兜底：如果系统拒绝投递 STOP intent，至少强制停止服务声明本身。
                runCatching { appContext.stopService(android.content.Intent(appContext, WebIdeForegroundService::class.java)) }
            }
            MainProcessKeepAliveService.stopIfNotNeeded(appContext)
            AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(appContext)
        }
    }


    fun resetOnApplicationStart(context: Context) {
        // 兼容旧调用名：WebIDE 是用户显式打开的前台服务，进程被系统回收后应按保存的开关恢复，
        // 不能在主进程重新创建时把 enabled 强制改回 false。
        ensureStartedIfEnabled(context)
    }

    fun ensureStartedIfEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val config = loadConfig(appContext)
        if (!config.enabled) {
            _status.value = WebIdeStatus(
                running = false,
                host = config.host,
                port = config.port,
                lastError = null
            )
            return false
        }

        val safeHost = sanitizeHost(config.host)
        val safePort = config.port.coerceIn(1024, 65535)
        _status.value = WebIdeStatus(running = true, host = safeHost, port = safePort, lastError = null)

        val intent = WebIdeForegroundService.startIntent(appContext, safeHost, safePort)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure { error ->
            _status.value = WebIdeStatus(
                running = false,
                host = safeHost,
                port = safePort,
                lastError = error.message ?: error.javaClass.simpleName
            )
            return false
        }

        MainProcessKeepAliveService.startIfNeeded(appContext, MainProcessKeepAliveService.REASON_WEBIDE)
        return true
    }

    fun syncStatusWithSavedConfig(context: Context) {
        val config = loadConfig(context)
        _status.value = WebIdeStatus(
            running = config.enabled,
            host = config.host,
            port = config.port,
            lastError = _status.value.lastError
        )
    }

    internal fun markServiceRunning(context: Context, host: String, port: Int) {
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        _status.value = WebIdeStatus(running = true, host = safeHost, port = safePort, lastError = null)
        saveConfig(context, WebIdeConfig(enabled = true, host = safeHost, port = safePort))
    }

    internal fun markServiceStopped(context: Context?, host: String, port: Int, updateSavedConfig: Boolean) {
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        _status.value = WebIdeStatus(running = false, host = safeHost, port = safePort, lastError = null)
        if (context != null && updateSavedConfig) {
            saveConfig(context, WebIdeConfig(enabled = false, host = safeHost, port = safePort))
            AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(context)
        }
    }

    internal fun markServiceError(context: Context, host: String, port: Int, error: Throwable) {
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        _status.value = WebIdeStatus(
            running = false,
            host = safeHost,
            port = safePort,
            lastError = error.message ?: error.javaClass.simpleName
        )
        saveConfig(context, WebIdeConfig(enabled = false, host = safeHost, port = safePort))
        AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(context)
    }

    internal fun sanitizeHost(host: String): String {
        val trimmed = host.trim()
        return trimmed.ifBlank { WebIdeDefaults.DEFAULT_HOST }
    }
}
