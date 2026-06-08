package top.lovepikachu.XiaoHeiHook.mcp

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

object McpBridgeIpc {
    private const val JSON_VALUE_PREFIX = "__xhh_json__:"
    private const val JSON_ARRAY_PREFIX = "__xhh_json_array__:"
    private const val JSON_NULL = "__xhh_json_null__"

    fun jsonToBundle(json: JSONObject): Bundle {
        val bundle = Bundle()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            putValue(bundle, key, json.opt(key))
        }
        return bundle
    }

    fun bundleToJson(bundle: Bundle?): JSONObject {
        val json = JSONObject()
        if (bundle == null) return json
        for (key in bundle.keySet()) {
            json.put(key, unwrapValue(bundle.get(key)))
        }
        return json
    }

    fun resultBundle(ok: Boolean, action: String? = null, reason: String? = null): Bundle {
        return Bundle().apply {
            putBoolean("ok", ok)
            if (!action.isNullOrBlank()) putString("action", action)
            if (!reason.isNullOrBlank()) putString("reason", reason)
        }
    }

    private fun putValue(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> bundle.putString(key, JSON_NULL)
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            is Number -> bundle.putString(key, value.toString())
            is JSONObject -> bundle.putString(key, JSON_VALUE_PREFIX + value.toString())
            is JSONArray -> bundle.putString(key, JSON_ARRAY_PREFIX + value.toString())
            else -> bundle.putString(key, value.toString())
        }
    }

    private fun unwrapValue(value: Any?): Any? {
        if (value is String) {
            return when {
                value == JSON_NULL -> JSONObject.NULL
                value.startsWith(JSON_VALUE_PREFIX) -> runCatching { JSONObject(value.removePrefix(JSON_VALUE_PREFIX)) }.getOrElse { value }
                value.startsWith(JSON_ARRAY_PREFIX) -> runCatching { JSONArray(value.removePrefix(JSON_ARRAY_PREFIX)) }.getOrElse { value }
                else -> value
            }
        }
        return value ?: JSONObject.NULL
    }
}
