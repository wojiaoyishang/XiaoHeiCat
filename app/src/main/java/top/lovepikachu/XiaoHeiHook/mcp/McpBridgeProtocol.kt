package top.lovepikachu.XiaoHeiHook.mcp

object McpBridgeProtocol {
    const val PREF_GROUP = McpDefaults.REMOTE_PREF_GROUP
    const val KEY_ENABLED = McpDefaults.REMOTE_KEY_ENABLED
    const val KEY_BRIDGE_TOKEN = McpDefaults.REMOTE_KEY_BRIDGE_TOKEN

    const val ACTION_DISCOVER = "top.lovepikachu.XiaoHeiHook.MCP_BRIDGE_DISCOVER"

    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_PROCESS_NAME = "processName"
    const val EXTRA_SESSION_ID = "sessionId"
    const val EXTRA_METHOD_NAME = "methodName"
    const val EXTRA_HANDLER_ID = "handlerId"
    const val EXTRA_SCRIPT_NAME = "scriptName"
    const val EXTRA_SCRIPT_PATH = "scriptPath"
    const val EXTRA_DESCRIPTION = "description"
    const val EXTRA_PARAMS_SCHEMA_JSON = "paramsSchemaJson"
    const val EXTRA_TIMEOUT_MS = "timeoutMs"
    const val EXTRA_CONCURRENCY = "concurrency"
    const val EXTRA_CONFLICT = "conflict"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_PARAMS_JSON = "paramsJson"
    const val EXTRA_RESULT_JSON = "resultJson"
    const val EXTRA_ERROR_CODE = "errorCode"
    const val EXTRA_ERROR_MESSAGE = "errorMessage"
    const val EXTRA_OK = "ok"
    const val EXTRA_BRIDGE_TOKEN = "bridgeToken"
    const val EXTRA_BRIDGE_HOST = "bridgeHost"
    const val EXTRA_BRIDGE_PORT = "bridgePort"
    const val EXTRA_DEBUG_LOG = "debugLog"

    const val CONFLICT_OVERWRITE = "overwrite"
    const val CONFLICT_IGNORE = "ignore"
    const val CONFLICT_ERROR = "error"

    const val ERROR_TARGET_OFFLINE = "TARGET_OFFLINE"
    const val ERROR_METHOD_NOT_FOUND = "METHOD_NOT_FOUND"
    const val ERROR_TIMEOUT = "TIMEOUT"
    const val ERROR_REGISTER_CONFLICT = "REGISTER_CONFLICT"
    const val ERROR_INVOKE_FAILED = "INVOKE_FAILED"
    const val ERROR_MCP_DISABLED = "MCP_DISABLED"
}
