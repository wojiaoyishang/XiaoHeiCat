package top.lovepikachu.XiaoHeiHook.mcp

data class McpConfig(
    val enabled: Boolean = false,
    val host: String = McpDefaults.DEFAULT_HOST,
    val port: Int = McpDefaults.DEFAULT_PORT,
    val tokenEnabled: Boolean = false,
    val token: String = ""
)

object McpDefaults {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 18787
    const val PREFS_NAME = "mcp_settings"
    const val SECURITY_PREFS_NAME = "xhh_mcp_security"
    const val KEY_ENABLED = "enabled"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_TOKEN_ENABLED = "token_enabled"
    const val KEY_TOKEN = "token"

    const val REMOTE_PREF_GROUP = "XiaoHeiHookSetting"
    const val REMOTE_KEY_ENABLED = "mcp_enabled"
    const val REMOTE_KEY_BRIDGE_TOKEN = "mcp_bridge_token"
}

data class McpStatus(
    val running: Boolean = false,
    val host: String = McpDefaults.DEFAULT_HOST,
    val port: Int = McpDefaults.DEFAULT_PORT,
    val tokenEnabled: Boolean = false,
    val token: String = "",
    val lastError: String? = null
) {
    val baseUrl: String
        get() = "http://$host:$port/mcp"
}

data class RegisteredMcpMethod(
    val packageName: String,
    val processName: String,
    val sessionId: String,
    val methodName: String,
    val handlerId: String,
    val scriptName: String,
    val scriptPath: String,
    val description: String,
    val paramsSchemaJson: String,
    val timeoutMs: Long,
    val concurrency: String,
    val registeredAt: Long,
    @Volatile var lastSeenAt: Long,
    val debugLogging: Boolean = false
) {
    val key: String
        get() = methodKey(packageName, processName, methodName)

    companion object {
        fun methodKey(packageName: String, processName: String, methodName: String): String {
            return packageName.trim() + "\u0000" + processName.trim() + "\u0000" + methodName.trim()
        }
    }
}

data class McpRuntimeSessionInfo(
    val packageName: String,
    val processName: String
)
