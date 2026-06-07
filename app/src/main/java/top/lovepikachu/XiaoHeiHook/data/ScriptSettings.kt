package top.lovepikachu.XiaoHeiHook.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Dynamic script settings schema and value sanitizer.
 *
 * settings.json is trusted only as a declaration input.  User values are saved
 * separately in LSPosed Remote Preferences under packageName + scriptId.
 */
object ScriptSettings {
    private val allowedTypes = setOf(
        "list",
        "group",
        "heading",
        "info",
        "switch",
        "number",
        "text",
        "checkbox",
        "radio",
        "select",
        "custom",
        "tags"
    )

    private val valueTypes = setOf("list", "switch", "number", "text", "checkbox", "radio", "select", "custom", "tags")
    private val unsafeKeys = setOf("__proto__", "prototype", "constructor")
    private val keyRegex = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    fun settingsKey(packageName: String, scriptId: String): String {
        return "script_settings_${packageName}_${scriptId}"
    }

    fun normalizeSchema(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val fields = input.optJSONArray("fields") ?: return null
        val normalizedFields = normalizeFields(fields, depth = 0)
        return JSONObject()
            .put("version", input.optInt("version", 1).coerceIn(1, 1000))
            .put("title", limit(input.optString("title", "脚本设置"), 80))
            .put("description", limit(input.optString("description", ""), 600))
            .put("fields", normalizedFields)
    }

    fun defaults(schema: JSONObject?): JSONObject {
        if (schema == null) return JSONObject()
        return defaultsForFields(schema.optJSONArray("fields") ?: JSONArray())
    }

    fun merge(schema: JSONObject?, savedRaw: String?): JSONObject {
        val base = defaults(schema)
        val saved = runCatching { JSONObject(savedRaw ?: "") }.getOrNull()
        val values = saved?.optJSONObject("values") ?: saved ?: JSONObject()
        val clean = normalizeValues(schema, values, strict = false)
        copyInto(base, clean)
        return base
    }

    fun normalizeValues(schema: JSONObject?, values: JSONObject, strict: Boolean = true): JSONObject {
        if (schema == null) return JSONObject()
        return normalizeValuesForFields(schema.optJSONArray("fields") ?: JSONArray(), values, strict)
    }

    fun savedDocument(packageName: String, scriptId: String, scriptPath: String, schema: JSONObject?, values: JSONObject): JSONObject {
        return JSONObject()
            .put("version", schema?.optInt("version", 1) ?: 1)
            .put("packageName", packageName)
            .put("scriptId", scriptId)
            .put("scriptPath", scriptPath)
            .put("updatedAt", System.currentTimeMillis())
            .put("values", values)
    }

    private fun normalizeFields(fields: JSONArray, depth: Int): JSONArray {
        val out = JSONArray()
        if (depth > 8) return out
        val max = fields.length().coerceAtMost(if (depth == 0) 256 else 128)
        for (i in 0 until max) {
            val obj = fields.optJSONObject(i) ?: continue
            normalizeField(obj, depth)?.let { out.put(it) }
        }
        return out
    }

    private fun normalizeField(obj: JSONObject, depth: Int): JSONObject? {
        val type = normalizeType(obj.optString("type", "text"))
        if (type !in allowedTypes) return null

        val key = obj.optString("key", "").trim()
        if (type in valueTypes && !isSafeKey(key)) return null

        val out = JSONObject()
            .put("type", type)
            .put("label", limit(obj.optString("label", obj.optString("title", key)), 80))
            .put("description", limit(obj.optString("description", ""), 600))

        if (key.isNotBlank()) out.put("key", key)
        if (obj.has("title")) out.put("title", limit(obj.optString("title", ""), 80))
        if (obj.has("message")) out.put("message", limit(obj.optString("message", ""), 1200))
        if (obj.has("placeholder")) out.put("placeholder", limit(obj.optString("placeholder", ""), 120))

        when (type) {
            "info" -> out.put("tone", normalizeTone(obj.optString("tone", obj.optString("variant", "info"))))
            "number" -> normalizeNumberField(obj, out)
            "text" -> {
                out.put("masked", obj.optBoolean("masked", false))
                out.put("multiline", obj.optBoolean("multiline", obj.optBoolean("textarea", false)))
                out.put("maxLength", obj.optInt("maxLength", 2000).coerceIn(1, 100_000))
            }
            "select" -> out.put("options", normalizeOptions(obj.optJSONArray("options")))
            "radio" -> {
                if (obj.has("value")) out.put("value", sanitizeJson(obj.opt("value"), 300))
                if (obj.has("name")) out.put("name", limit(obj.optString("name", ""), 80))
            }
            "tags" -> out.put("maxItems", obj.optInt("maxItems", 128).coerceIn(0, 1024))
            "custom" -> {
                out.put("maxItems", obj.optInt("maxItems", 128).coerceIn(0, 1024))
                out.put("keyPlaceholder", limit(obj.optString("keyPlaceholder", "key"), 80))
                out.put("valuePlaceholder", limit(obj.optString("valuePlaceholder", "value"), 80))
            }
            "list" -> {
                out.put("maxItems", obj.optInt("maxItems", 64).coerceIn(0, 512))
                val uniqueKey = obj.optString("uniqueKey", "").trim()
                if (isSafeKey(uniqueKey)) out.put("uniqueKey", uniqueKey)
                val child = obj.optJSONArray("items") ?: obj.optJSONArray("fields") ?: JSONArray()
                out.put("items", normalizeFields(child, depth + 1))
            }
            "group" -> {
                val child = obj.optJSONArray("items") ?: obj.optJSONArray("fields") ?: JSONArray()
                out.put("items", normalizeFields(child, depth + 1))
                out.put("collapsible", obj.optBoolean("collapsible", false))
                out.put("defaultCollapsed", obj.optBoolean("defaultCollapsed", false))
            }
        }

        if (obj.has("default") && type in valueTypes) {
            out.put("default", normalizeValueForField(out, obj.opt("default"), strict = false))
        }

        return out
    }

    private fun normalizeType(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "boolean" -> "switch"
            "string" -> "text"
            "integer" -> "number"
            "stringarray", "string-array", "array" -> "tags"
            else -> raw.trim().lowercase(Locale.US)
        }
    }

    private fun normalizeTone(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
        "warning", "success", "error" -> raw.trim().lowercase(Locale.US)
        else -> "info"
    }

    private fun normalizeNumberField(input: JSONObject, out: JSONObject) {
        val integer = input.optBoolean("integer", input.optString("type").equals("integer", ignoreCase = true))
        val min = input.optDoubleOrNull("min")
        val max = input.optDoubleOrNull("max")
        val safeMin = min?.takeIf { it.isFinite() }
        val safeMax = max?.takeIf { it.isFinite() }
        if (safeMin != null) out.put("min", safeMin)
        if (safeMax != null) out.put("max", safeMax)
        out.put("integer", integer)
        val step = input.optDoubleOrNull("step")?.takeIf { it.isFinite() && it > 0 } ?: if (integer) 1.0 else 0.1
        out.put("step", step)
    }

    private fun normalizeOptions(options: JSONArray?): JSONArray {
        val out = JSONArray()
        if (options == null) return out
        val seen = LinkedHashSet<String>()
        for (i in 0 until options.length().coerceAtMost(256)) {
            val item = options.opt(i)
            val obj = when (item) {
                is JSONObject -> item
                else -> JSONObject().put("label", jsonString(item)).put("value", item)
            }
            val value = sanitizeJson(obj.opt("value"), 300)
            val key = jsonString(value)
            if (key.isBlank() || !seen.add(key)) continue
            out.put(JSONObject().put("label", limit(obj.optString("label", key), 80)).put("value", value))
        }
        return out
    }

    private fun defaultsForFields(fields: JSONArray): JSONObject {
        val out = JSONObject()
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            val type = field.optString("type")
            if (type == "group") {
                copyInto(out, defaultsForFields(field.optJSONArray("items") ?: JSONArray()))
                continue
            }
            val key = field.optString("key", "")
            if (!isSafeKey(key)) continue
            // radio groups often declare several items with the same key. Keep the first
            // value, unless a later item explicitly declares a default.
            if (!out.has(key) || field.has("default")) {
                out.put(key, defaultForField(field))
            }
        }
        return out
    }

    private fun defaultForField(field: JSONObject): Any? {
        return if (field.has("default")) {
            normalizeValueForField(field, field.opt("default"), strict = false)
        } else when (field.optString("type")) {
            "switch", "checkbox" -> false
            "number" -> if (field.optBoolean("integer", false)) 0 else 0.0
            "text" -> ""
            "select" -> field.optJSONArray("options")?.optJSONObject(0)?.opt("value") ?: ""
            "radio" -> field.opt("value") ?: false
            "tags" -> JSONArray()
            "custom" -> JSONObject()
            "list" -> JSONArray()
            else -> JSONObject.NULL
        }
    }

    private fun normalizeValuesForFields(fields: JSONArray, values: JSONObject, strict: Boolean): JSONObject {
        val out = JSONObject()
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            val type = field.optString("type")
            if (type == "group") {
                copyInto(out, normalizeValuesForFields(field.optJSONArray("items") ?: JSONArray(), values, strict))
                continue
            }
            val key = field.optString("key", "")
            if (!isSafeKey(key) || !values.has(key)) continue
            val raw = values.opt(key)
            val normalized = normalizeValueForField(field, raw, strict)
            if (normalized != JSONObject.NULL) out.put(key, normalized)
        }
        return out
    }

    private fun normalizeValueForField(field: JSONObject, raw: Any?, strict: Boolean): Any? {
        val type = field.optString("type")
        return when (type) {
            "switch", "checkbox" -> when (raw) {
                is Boolean -> raw
                else -> if (strict) throw IllegalArgumentException("${field.optString("key")} 必须是布尔值") else parseBoolean(raw)
            }
            "number" -> normalizeNumberValue(field, raw, strict)
            "text" -> normalizeStringValue(field, raw, strict)
            "select" -> normalizeSelectValue(field, raw, strict)
            "radio" -> sanitizeJson(raw, 300)
            "tags" -> normalizeTagsValue(field, raw, strict)
            "custom" -> normalizeCustomValue(field, raw, strict)
            "list" -> normalizeListValue(field, raw, strict)
            else -> JSONObject.NULL
        }
    }

    private fun normalizeNumberValue(field: JSONObject, raw: Any?, strict: Boolean): Any {
        val d = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        if (d == null || !d.isFinite()) {
            if (strict) throw IllegalArgumentException("${field.optString("key")} 必须是数字")
            return defaultForNumber(field)
        }
        var value = d
        val min = field.optDoubleOrNull("min")
        val max = field.optDoubleOrNull("max")
        if (min != null) value = value.coerceAtLeast(min)
        if (max != null) value = value.coerceAtMost(max)
        return if (field.optBoolean("integer", false)) value.toInt() else value
    }

    private fun defaultForNumber(field: JSONObject): Any {
        val fallback = if (field.optBoolean("integer", false)) 0 else 0.0
        return if (field.has("default")) {
            runCatching { normalizeNumberValue(field, field.opt("default"), false) }.getOrDefault(fallback)
        } else fallback
    }

    private fun normalizeStringValue(field: JSONObject, raw: Any?, strict: Boolean): String {
        if (raw == null || raw == JSONObject.NULL) return ""
        if (strict && raw !is String) throw IllegalArgumentException("${field.optString("key")} 必须是文本")
        val max = field.optInt("maxLength", 2000).coerceIn(1, 100_000)
        return limit(jsonString(raw), max)
    }

    private fun normalizeSelectValue(field: JSONObject, raw: Any?, strict: Boolean): Any? {
        val options = field.optJSONArray("options") ?: JSONArray()
        if (options.length() == 0) return sanitizeJson(raw, 300)
        val rawText = jsonString(raw)
        for (i in 0 until options.length()) {
            val value = options.optJSONObject(i)?.opt("value") ?: continue
            if (jsonString(value) == rawText) return value
        }
        if (strict) throw IllegalArgumentException("${field.optString("key")} 不是有效选项")
        return options.optJSONObject(0)?.opt("value") ?: ""
    }

    private fun normalizeTagsValue(field: JSONObject, raw: Any?, strict: Boolean): JSONArray {
        val arr = raw as? JSONArray ?: if (!strict) JSONArray() else throw IllegalArgumentException("${field.optString("key")} 必须是字符串数组")
        val out = JSONArray()
        val seen = LinkedHashSet<String>()
        val max = field.optInt("maxItems", 128).coerceIn(0, 1024)
        for (i in 0 until arr.length()) {
            val text = arr.optString(i, "").trim()
            if (text.isBlank()) continue
            val safe = limit(text, 300)
            if (seen.add(safe)) out.put(safe)
            if (out.length() >= max) break
        }
        return out
    }

    private fun normalizeCustomValue(field: JSONObject, raw: Any?, strict: Boolean): JSONObject {
        val obj = raw as? JSONObject ?: if (!strict) JSONObject() else throw IllegalArgumentException("${field.optString("key")} 必须是对象")
        val out = JSONObject()
        val keys = obj.keys()
        val max = field.optInt("maxItems", 128).coerceIn(0, 1024)
        var count = 0
        while (keys.hasNext() && count < max) {
            val key = keys.next().trim()
            if (!isSafeCustomKey(key)) continue
            out.put(limit(key, 120), limit(jsonString(obj.opt(key)), 2000))
            count++
        }
        return out
    }

    private fun normalizeListValue(field: JSONObject, raw: Any?, strict: Boolean): JSONArray {
        val arr = raw as? JSONArray ?: if (!strict) JSONArray() else throw IllegalArgumentException("${field.optString("key")} 必须是数组")
        val child = field.optJSONArray("items") ?: JSONArray()
        val out = JSONArray()
        val max = field.optInt("maxItems", 64).coerceIn(0, 512)
        val uniqueKey = field.optString("uniqueKey", "")
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            if (out.length() >= max) break
            val itemObj = arr.optJSONObject(i) ?: JSONObject()
            val base = defaultsForFields(child)
            copyInto(base, normalizeValuesForFields(child, itemObj, strict = false))
            if (uniqueKey.isNotBlank()) {
                val uniqueValue = base.optString(uniqueKey, "").trim()
                if (uniqueValue.isNotBlank() && !seen.add(uniqueValue)) continue
            }
            out.put(base)
        }
        return out
    }

    private fun sanitizeJson(value: Any?, maxString: Int): Any? {
        return when (value) {
            null -> JSONObject.NULL
            JSONObject.NULL -> JSONObject.NULL
            is Boolean, is Number -> value
            is String -> limit(value, maxString)
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length().coerceAtMost(512)) out.put(sanitizeJson(value.opt(i), maxString))
                out
            }
            is JSONObject -> {
                val out = JSONObject()
                val keys = value.keys()
                var count = 0
                while (keys.hasNext() && count < 512) {
                    val key = keys.next()
                    if (!isSafeCustomKey(key)) continue
                    out.put(limit(key, 120), sanitizeJson(value.opt(key), maxString))
                    count++
                }
                out
            }
            else -> limit(jsonString(value), maxString)
        }
    }

    private fun jsonString(value: Any?): String {
        return if (value == null || value == JSONObject.NULL) "" else value.toString()
    }

    private fun parseBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase(Locale.US) in setOf("1", "true", "yes", "y", "on")
        else -> false
    }

    private fun copyInto(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            target.put(key, source.opt(key))
        }
    }

    private fun isSafeKey(key: String): Boolean = keyRegex.matches(key) && key !in unsafeKeys
    private fun isSafeCustomKey(key: String): Boolean = key.isNotBlank() && key.length <= 120 && key !in unsafeKeys && !key.contains('\u0000')
    private fun limit(value: String, max: Int): String = value.take(max.coerceAtLeast(0))

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
