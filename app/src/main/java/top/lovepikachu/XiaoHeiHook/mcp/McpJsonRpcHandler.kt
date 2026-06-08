package top.lovepikachu.XiaoHeiHook.mcp

import android.content.Context
import org.json.JSONArray
import top.lovepikachu.XiaoHeiHook.XhhConstants
import org.json.JSONObject

class McpJsonRpcHandler(private val context: Context) {
    fun handle(rawBody: String): JSONObject {
        val request = JSONObject(rawBody.ifBlank { "{}" })
        val idValue = if (request.has("id")) request.opt("id") else null
        val method = request.optString("method", "")
        return when (method) {
            "initialize" -> response(idValue, initializeResult())
            "notifications/initialized" -> notificationAck(idValue)
            "ping" -> response(idValue, JSONObject())
            "tools/list" -> response(idValue, JSONObject().put("tools", toolDefinitions()))
            "tools/call" -> response(idValue, callTool(request.optJSONObject("params") ?: JSONObject()))
            else -> error(idValue, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult(): JSONObject {
        return JSONObject()
            .put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject()
                .put("name", "XiaoHeiHook MCP")
                .put("version", XhhConstants.VERSION_NAME))
    }

    private fun toolDefinitions(): JSONArray {
        val tools = JSONArray()
        tools.put(JSONObject()
            .put("name", "list_methods")
            .put("description", "List JavaScript methods registered by XiaoHeiHook scripts in a connected target app process.")
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("packageName"))
                .put("properties", JSONObject()
                    .put("packageName", JSONObject().put("type", "string"))
                    .put("processName", JSONObject().put("type", "string"))
                    .put("includeSchema", JSONObject().put("type", "boolean")))))
        tools.put(JSONObject()
            .put("name", "invoke_method")
            .put("description", "Invoke a JavaScript method previously registered by a XiaoHeiHook script in the target app process.")
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("packageName").put("methodName"))
                .put("properties", JSONObject()
                    .put("packageName", JSONObject().put("type", "string"))
                    .put("processName", JSONObject().put("type", "string"))
                    .put("methodName", JSONObject().put("type", "string"))
                    .put("params", JSONObject().put("type", "object"))
                    .put("timeoutMs", JSONObject().put("type", "integer").put("minimum", 100).put("maximum", 30000)))))
        return tools
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val result = when (name) {
            "list_methods" -> listMethods(arguments)
            "invoke_method" -> invokeMethod(arguments)
            else -> JSONObject()
                .put("ok", false)
                .put("error", JSONObject().put("code", "TOOL_NOT_FOUND").put("message", "Unknown tool: $name"))
        }
        return toolResult(result)
    }

    private fun listMethods(args: JSONObject): JSONObject {
        if (!McpManager.isEnabled(context)) return disabledResult()
        val packageName = args.optString("packageName", "").trim()
        val processName = args.optString("processName", "").trim().ifBlank { null }
        if (packageName.isBlank()) {
            return JSONObject().put("ok", false).put("error", JSONObject().put("code", "INVALID_ARGUMENT").put("message", "packageName is required"))
        }
        val methods = McpMethodRegistry.listMethods(packageName, processName)
        return JSONObject()
            .put("ok", true)
            .put("packageName", packageName)
            .put("processName", processName ?: "")
            .put("methods", methods)
    }

    private fun invokeMethod(args: JSONObject): JSONObject {
        if (!McpManager.isEnabled(context)) return disabledResult()
        val packageName = args.optString("packageName", "").trim()
        val processName = args.optString("processName", "").trim().ifBlank { null }
        val methodName = args.optString("methodName", "").trim()
        if (packageName.isBlank() || methodName.isBlank()) {
            return JSONObject().put("ok", false).put("error", JSONObject().put("code", "INVALID_ARGUMENT").put("message", "packageName and methodName are required"))
        }
        val params = if (args.has("params")) args.opt("params") else JSONObject()
        val timeoutMs = if (args.has("timeoutMs")) args.optLong("timeoutMs") else null
        return McpMethodRegistry.invoke(context, packageName, processName, methodName, params, timeoutMs)
    }

    private fun disabledResult(): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", JSONObject()
                .put("code", McpBridgeProtocol.ERROR_MCP_DISABLED)
                .put("message", "MCP server is disabled"))
    }

    private fun toolResult(structured: JSONObject): JSONObject {
        return JSONObject()
            .put("content", JSONArray().put(JSONObject()
                .put("type", "text")
                .put("text", structured.toString())))
            .put("structuredContent", structured)
            .put("isError", !structured.optBoolean("ok", false))
    }

    private fun response(idValue: Any?, result: JSONObject): JSONObject {
        val out = JSONObject()
            .put("jsonrpc", "2.0")
            .put("result", result)
        putId(out, idValue)
        return out
    }

    private fun notificationAck(idValue: Any?): JSONObject {
        return response(idValue, JSONObject())
    }

    private fun error(idValue: Any?, code: Int, message: String): JSONObject {
        val out = JSONObject()
            .put("jsonrpc", "2.0")
            .put("error", JSONObject().put("code", code).put("message", message))
        putId(out, idValue)
        return out
    }

    private fun putId(out: JSONObject, idValue: Any?) {
        if (idValue == null || idValue == JSONObject.NULL) {
            out.put("id", JSONObject.NULL)
        } else {
            out.put("id", idValue)
        }
    }
}
