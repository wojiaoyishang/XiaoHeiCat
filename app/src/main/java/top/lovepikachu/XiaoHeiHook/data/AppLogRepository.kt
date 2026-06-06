package top.lovepikachu.XiaoHeiHook.data

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object AppLogRepository {
    private const val TAG = "XiaoHeiHook-AppLog"
    private const val LOG_DIR_NAME = "xiaoheihook_logs"
    private const val LOG_FILE_NAME = "xiaoheihook.log"

    fun logPath(packageName: String): String {
        return "/data/user/0/${packageName}/files/${LOG_DIR_NAME}/${LOG_FILE_NAME}"
    }

    fun readLog(packageName: String, maxLines: Int = 800): Result<String> = runCatching {
        val path = logPath(packageName)
        val result = runRootCommand("tail -n $maxLines ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 8)
        Log.d(TAG, "readLog: package=$packageName, path=$path, exit=${result.exitCode}, timeout=${result.timedOut}, stderr=${result.stderr.trim()}")
        if (result.timedOut) error("读取日志超时：$path")
        if (result.exitCode != 0) {
            return@runCatching "暂无日志。\n\n路径：$path\n\n请先启动目标应用并触发脚本；如果仍为空，请确认设备已授予 root 权限。"
        }
        result.stdout.ifBlank {
            "暂无日志。\n\n路径：$path\n\n请先启动目标应用并触发脚本。"
        }
    }

    fun clearLog(packageName: String): Result<Unit> = runCatching {
        val path = logPath(packageName)
        val result = runRootCommand("mkdir -p ${shellQuote(File(path).parent ?: "")} && : > ${shellQuote(path)}", timeoutSeconds = 8)
        Log.d(TAG, "clearLog: package=$packageName, path=$path, exit=${result.exitCode}, timeout=${result.timedOut}, stderr=${result.stderr.trim()}")
        if (result.timedOut) error("清空日志超时：$path")
        if (result.exitCode != 0) error(result.stderr.ifBlank { "清空日志失败：$path" })
    }

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    )

    private fun runRootCommand(command: String, timeoutSeconds: Long): RootCommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootCommandResult(-1, "", "timeout", timedOut = true)
            }
            RootCommandResult(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().use { it.readText() },
                stderr = process.errorStream.bufferedReader().use { it.readText() }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "runRootCommand failed: $command", t)
            RootCommandResult(-1, "", t.message ?: t.toString())
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
