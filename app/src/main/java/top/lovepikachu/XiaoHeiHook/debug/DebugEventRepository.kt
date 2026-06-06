package top.lovepikachu.XiaoHeiHook.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

object DebugEventRepository {
    private const val DIR_NAME = "debug"
    private const val FILE_NAME = "debug-events.jsonl"
    private const val ACTIVE_FILE_NAME = "debug-active-pauses.json"
    private const val DEFAULT_ACTIVE_MAX_AGE_MS = 10L * 60L * 1000L
    private val lock = Any()

    fun eventFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun activeFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, ACTIVE_FILE_NAME)
    }

    fun append(context: Context, event: JSONObject) {
        if (!event.has("time")) event.put("time", System.currentTimeMillis())
        if (!event.has("updatedAt")) event.put("updatedAt", System.currentTimeMillis())
        append(context, event.toString())
        updateActiveFromEvent(context, event)
    }

    fun append(context: Context, eventJson: String) {
        if (eventJson.isBlank()) return
        synchronized(lock) {
            val file = eventFile(context)
            file.parentFile?.mkdirs()
            file.appendText(eventJson.trim() + "\n", StandardCharsets.UTF_8)
        }
    }

    private fun readActiveLocked(context: Context): JSONObject {
        val file = activeFile(context)
        if (!file.exists()) return JSONObject()
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrDefault("").trim()
        if (text.isBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun writeActiveLocked(context: Context, active: JSONObject) {
        val file = activeFile(context)
        file.parentFile?.mkdirs()
        file.writeText(active.toString(), StandardCharsets.UTF_8)
    }

    fun updateActiveFromEvent(context: Context, event: JSONObject) {
        val type = event.optString("type")
        val pauseId = event.optString("pauseId")
        if (pauseId.isBlank()) return

        synchronized(lock) {
            val active = readActiveLocked(context)
            when (type) {
                "paused" -> {
                    val copy = JSONObject(event.toString())
                        .put("status", "paused")
                        .put("active", true)
                        .put("updatedAt", System.currentTimeMillis())
                    active.put(pauseId, copy)
                }
                "continued", "resumed", "aborted", "expired", "continuing", "aborting" -> {
                    active.remove(pauseId)
                }
                "variablesUpdated" -> {
                    val existing = active.optJSONObject(pauseId)
                    if (existing != null) {
                        if (event.has("locals")) existing.put("locals", event.opt("locals"))
                        if (event.has("message")) existing.put("message", event.optString("message"))
                        if (event.has("error")) existing.put("error", event.optString("error"))
                        existing.put("updatedAt", System.currentTimeMillis())
                        active.put(pauseId, existing)
                    }
                }
            }
            writeActiveLocked(context, active)
        }
    }

    /**
     * Called when WebIDE sends Continue/Abort. The target process may send a final
     * continued/aborted event later, but the paused item must disappear immediately
     * so a page refresh cannot resurrect an already-commanded breakpoint.
     */
    fun markCommanded(context: Context, packageName: String, processName: String?, pauseId: String, command: String): JSONObject {
        val type = when (command) {
            DebugProtocol.COMMAND_ABORT -> "aborting"
            else -> "continuing"
        }
        val event = JSONObject()
            .put("type", type)
            .put("time", System.currentTimeMillis())
            .put("updatedAt", System.currentTimeMillis())
            .put("packageName", packageName)
            .put("processName", processName.orEmpty())
            .put("pauseId", pauseId)
            .put("message", "debug command accepted: $command")
        append(context, event)
        return event
    }

    fun readActivePaused(context: Context, maxAgeMs: Long = DEFAULT_ACTIVE_MAX_AGE_MS, packageName: String? = null): List<JSONObject> {
        val now = System.currentTimeMillis()
        val result = ArrayList<JSONObject>()
        val expired = ArrayList<JSONObject>()
        synchronized(lock) {
            val active = readActiveLocked(context)
            val keys = active.keys().asSequence().toList()
            for (key in keys) {
                val obj = active.optJSONObject(key)
                if (obj == null) {
                    active.remove(key)
                    continue
                }
                val ageBase = obj.optLong("updatedAt", obj.optLong("time", now))
                val tooOld = now - ageBase > maxAgeMs
                val pkgMismatch = !packageName.isNullOrBlank() && obj.optString("packageName") != packageName
                if (tooOld) {
                    active.remove(key)
                    expired.add(
                        JSONObject()
                            .put("type", "expired")
                            .put("time", now)
                            .put("updatedAt", now)
                            .put("packageName", obj.optString("packageName"))
                            .put("processName", obj.optString("processName"))
                            .put("pauseId", key)
                            .put("breakpointName", obj.optString("breakpointName"))
                            .put("message", "stale paused breakpoint expired")
                    )
                    continue
                }
                if (!pkgMismatch) result.add(obj)
            }
            writeActiveLocked(context, active)
        }
        // Append expiration events outside the synchronized active read/write loop.
        expired.forEach { append(context, it) }
        return result.sortedBy { it.optLong("time", 0L) }
    }

    fun readRecent(context: Context, maxLines: Int = 300, packageName: String? = null, activeOnly: Boolean = false): List<JSONObject> {
        if (activeOnly) return readActivePaused(context, packageName = packageName)
        val file = eventFile(context)
        if (!file.exists()) return emptyList()
        val keep = ArrayDeque<String>()
        synchronized(lock) {
            file.useLines(StandardCharsets.UTF_8) { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    keep.addLast(line)
                    while (keep.size > maxLines.coerceAtLeast(1)) keep.removeFirst()
                }
            }
        }
        return keep.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }.filter { obj ->
            packageName.isNullOrBlank() || obj.optString("packageName") == packageName
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            val file = eventFile(context)
            if (file.exists()) file.writeText("", StandardCharsets.UTF_8)
            val active = activeFile(context)
            if (active.exists()) active.writeText(JSONObject().toString(), StandardCharsets.UTF_8)
        }
    }

    fun activePausedArray(context: Context, packageName: String? = null): JSONArray {
        return JSONArray(readActivePaused(context, packageName = packageName))
    }
}
