package top.lovepikachu.XiaoHeiHook.debug

object DebugProtocol {
    const val ACTION_EVENT = "top.lovepikachu.XiaoHeiHook.DEBUG_EVENT"
    const val ACTION_COMMAND = "top.lovepikachu.XiaoHeiHook.DEBUG_COMMAND"

    const val PREF_GROUP = "XiaoHeiHookSetting"
    const val DEBUG_ENABLED_PREFIX = "debugger_enabled_"
    const val DEBUG_SESSION_PREFIX = "debugger_session_"
    const val DEBUG_EXPIRES_AT_PREFIX = "debugger_expires_at_"
    const val DEBUG_COMMAND_PREFIX = "debugger_command_"
    const val DEBUG_BREAKPOINTS_PREFIX = "debugger_line_breakpoints_"
    const val DEFAULT_DEBUG_SESSION_MILLIS = 30L * 60L * 1000L

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
    const val COMMAND_EVAL = "eval"
    const val COMMAND_STEP_INTO = "stepInto"
    const val COMMAND_STEP_OVER = "stepOver"
    const val COMMAND_STEP_OUT = "stepOut"

    @JvmStatic
    fun debugEnabledKey(packageName: String): String = DEBUG_ENABLED_PREFIX + packageName

    @JvmStatic
    fun debugSessionKey(packageName: String): String = DEBUG_SESSION_PREFIX + packageName

    @JvmStatic
    fun debugExpiresAtKey(packageName: String): String = DEBUG_EXPIRES_AT_PREFIX + packageName

    @JvmStatic
    fun debugCommandKey(pauseId: String): String = DEBUG_COMMAND_PREFIX + pauseId

    @JvmStatic
    fun debugBreakpointsKey(packageName: String): String = DEBUG_BREAKPOINTS_PREFIX + packageName
}
