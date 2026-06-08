package top.lovepikachu.XiaoHeiHook.mcp

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication

object McpManager {
    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    fun loadConfig(context: Context): McpConfig {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(McpDefaults.PREFS_NAME, Context.MODE_PRIVATE)
        val token = McpSecurity.token(appContext)
        return McpConfig(
            enabled = prefs.getBoolean(McpDefaults.KEY_ENABLED, false),
            host = prefs.getString(McpDefaults.KEY_HOST, McpDefaults.DEFAULT_HOST)?.ifBlank { McpDefaults.DEFAULT_HOST }
                ?: McpDefaults.DEFAULT_HOST,
            port = prefs.getInt(McpDefaults.KEY_PORT, McpDefaults.DEFAULT_PORT).coerceIn(1024, 65535),
            tokenEnabled = prefs.getBoolean(McpDefaults.KEY_TOKEN_ENABLED, false),
            token = token
        )
    }

    fun saveConfig(context: Context, config: McpConfig) {
        val appContext = context.applicationContext
        val safeHost = sanitizeHost(config.host)
        val safePort = config.port.coerceIn(1024, 65535)
        val token = McpSecurity.token(appContext)
        appContext.getSharedPreferences(McpDefaults.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(McpDefaults.KEY_ENABLED, config.enabled)
            .putString(McpDefaults.KEY_HOST, safeHost)
            .putInt(McpDefaults.KEY_PORT, safePort)
            .putBoolean(McpDefaults.KEY_TOKEN_ENABLED, config.tokenEnabled)
            .commit()
        syncRemoteState(config.enabled, token)
    }

    fun isEnabled(context: Context): Boolean = loadConfig(context).enabled

    fun start(context: Context, host: String, port: Int, tokenEnabled: Boolean): Result<McpStatus> = runCatching {
        val appContext = context.applicationContext
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        require(safePort in 1024..65535) { "端口必须是 1024 到 65535" }
        val token = McpSecurity.token(appContext)
        val pendingStatus = McpStatus(
            running = true,
            host = safeHost,
            port = safePort,
            tokenEnabled = tokenEnabled,
            token = token,
            lastError = null
        )
        _status.value = pendingStatus
        saveConfig(appContext, McpConfig(enabled = true, host = safeHost, port = safePort, tokenEnabled = tokenEnabled, token = token))

        val intent = McpForegroundService.startIntent(appContext, safeHost, safePort, tokenEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        pendingStatus
    }.onFailure { error ->
        val safeHost = sanitizeHost(host)
        val safePort = port.coerceIn(1024, 65535)
        _status.value = McpStatus(
            running = false,
            host = safeHost,
            port = safePort,
            tokenEnabled = tokenEnabled,
            token = McpSecurity.token(context),
            lastError = error.message ?: error.javaClass.simpleName
        )
        saveConfig(context, McpConfig(enabled = false, host = safeHost, port = safePort, tokenEnabled = tokenEnabled))
    }

    fun stop(context: Context? = null) {
        val appContext = context?.applicationContext
        val current = _status.value
        _status.value = current.copy(running = false, lastError = null)
        McpMethodRegistry.clearAll()
        if (appContext != null) {
            saveConfig(appContext, McpConfig(enabled = false, host = current.host, port = current.port, tokenEnabled = current.tokenEnabled))
            val stopResult = runCatching { appContext.startService(McpForegroundService.stopIntent(appContext)) }
            if (stopResult.isFailure) {
                runCatching { appContext.stopService(android.content.Intent(appContext, McpForegroundService::class.java)) }
            }
        } else {
            syncRemoteEnabled(false)
        }
    }


    fun resetOnApplicationStart(context: Context) {
        val appContext = context.applicationContext
        val config = loadConfig(appContext)
        saveConfig(appContext, config.copy(enabled = false))
        _status.value = McpStatus(
            running = false,
            host = config.host,
            port = config.port,
            tokenEnabled = config.tokenEnabled,
            token = config.token,
            lastError = null
        )
        McpMethodRegistry.clearAll()
        syncRemoteEnabled(false)
        runCatching { appContext.startService(McpForegroundService.stopIntent(appContext)) }
        runCatching { appContext.stopService(android.content.Intent(appContext, McpForegroundService::class.java)) }
    }

    fun syncStatusWithSavedConfig(context: Context) {
        val config = loadConfig(context)
        _status.value = McpStatus(
            running = config.enabled,
            host = config.host,
            port = config.port,
            tokenEnabled = config.tokenEnabled,
            token = config.token,
            lastError = _status.value.lastError
        )
        syncRemoteState(config.enabled, config.token)
    }

    fun rotateToken(context: Context): String {
        val token = McpSecurity.rotateToken(context)
        val current = _status.value.copy(token = token)
        _status.value = current
        syncRemoteState(current.running, token)
        return token
    }

    internal fun markServiceRunning(context: Context, host: String, port: Int, tokenEnabled: Boolean) {
        val token = McpSecurity.token(context)
        _status.value = McpStatus(running = true, host = sanitizeHost(host), port = port.coerceIn(1024, 65535), tokenEnabled = tokenEnabled, token = token)
        saveConfig(context, McpConfig(enabled = true, host = host, port = port, tokenEnabled = tokenEnabled, token = token))
    }

    internal fun markServiceStopped(context: Context?, host: String, port: Int, tokenEnabled: Boolean, updateSavedConfig: Boolean) {
        val token = if (context != null) McpSecurity.token(context) else _status.value.token
        _status.value = McpStatus(running = false, host = sanitizeHost(host), port = port.coerceIn(1024, 65535), tokenEnabled = tokenEnabled, token = token)
        McpMethodRegistry.clearAll()
        if (context != null && updateSavedConfig) {
            saveConfig(context, McpConfig(enabled = false, host = host, port = port, tokenEnabled = tokenEnabled, token = token))
        }
    }

    internal fun markServiceError(context: Context, host: String, port: Int, tokenEnabled: Boolean, error: Throwable) {
        val token = McpSecurity.token(context)
        _status.value = McpStatus(
            running = false,
            host = sanitizeHost(host),
            port = port.coerceIn(1024, 65535),
            tokenEnabled = tokenEnabled,
            token = token,
            lastError = error.message ?: error.javaClass.simpleName
        )
        saveConfig(context, McpConfig(enabled = false, host = host, port = port, tokenEnabled = tokenEnabled, token = token))
    }

    internal fun sanitizeHost(host: String): String = host.trim().ifBlank { McpDefaults.DEFAULT_HOST }


    private fun syncRemoteEnabled(enabled: Boolean) {
        runCatching {
            XiaoHeiApplication.getRemotePreferences(McpDefaults.REMOTE_PREF_GROUP)
                ?.edit()
                ?.putBoolean(McpDefaults.REMOTE_KEY_ENABLED, enabled)
                ?.putString(McpDefaults.REMOTE_KEY_BRIDGE_TOKEN, "")
                ?.commit()
        }
    }

    private fun syncRemoteState(enabled: Boolean, bridgeToken: String) {
        runCatching {
            XiaoHeiApplication.getRemotePreferences(McpDefaults.REMOTE_PREF_GROUP)
                ?.edit()
                ?.putBoolean(McpDefaults.REMOTE_KEY_ENABLED, enabled)
                ?.putString(McpDefaults.REMOTE_KEY_BRIDGE_TOKEN, if (enabled) bridgeToken else "")
                ?.commit()
        }
    }
}
