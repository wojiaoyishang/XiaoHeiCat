package top.lovepikachu.XiaoHeiHook.webide

data class WebIdeConfig(
    val enabled: Boolean = false,
    val host: String = WebIdeDefaults.DEFAULT_HOST,
    val port: Int = WebIdeDefaults.DEFAULT_PORT
)

object WebIdeDefaults {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8787
    const val PREFS_NAME = "webide_settings"
    const val KEY_ENABLED = "enabled"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
}

data class WebIdeStatus(
    val running: Boolean = false,
    val host: String = WebIdeDefaults.DEFAULT_HOST,
    val port: Int = WebIdeDefaults.DEFAULT_PORT,
    val lastError: String? = null
) {
    val baseUrl: String
        get() = "http://$host:$port/"
}
