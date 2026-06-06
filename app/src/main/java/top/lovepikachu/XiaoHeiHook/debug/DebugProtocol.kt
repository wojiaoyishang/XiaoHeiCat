package top.lovepikachu.XiaoHeiHook.debug

object DebugProtocol {
    const val ACTION_EVENT = "top.lovepikachu.XiaoHeiHook.DEBUG_EVENT"
    const val ACTION_COMMAND = "top.lovepikachu.XiaoHeiHook.DEBUG_COMMAND"

    const val PREF_GROUP = "XiaoHeiHookSetting"
    const val DEBUG_ENABLED_PREFIX = "debugger_enabled_"
    const val DEBUG_COMMAND_PREFIX = "debugger_command_"

    const val EXTRA_EVENT_JSON = "eventJson"
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_PROCESS_NAME = "processName"
    const val EXTRA_PAUSE_ID = "pauseId"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_EXPRESSION = "expression"
    const val EXTRA_PAYLOAD_JSON = "payloadJson"

    const val COMMAND_CONTINUE = "continue"
    const val COMMAND_ABORT = "abort"
    const val COMMAND_SET_VARIABLE = "setVariable"

    @JvmStatic
    fun debugEnabledKey(packageName: String): String = DEBUG_ENABLED_PREFIX + packageName

    @JvmStatic
    fun debugCommandKey(pauseId: String): String = DEBUG_COMMAND_PREFIX + pauseId
}
