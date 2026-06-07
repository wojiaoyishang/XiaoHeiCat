package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import java.io.File

object AppLogRepository {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "xiaoheihook.log"
    private const val MAX_LOG_BYTES = 1024L * 1024L

    /**
     * Logs are relayed from target processes by explicit broadcast and stored in XiaoHeiHook's
     * own private directory. The runtime no longer writes into target app private directories.
     */
    fun moduleLogFile(context: Context, packageName: String): File {
        return File(File(File(context.filesDir, LOG_DIR_NAME), sanitizePackageName(packageName)), LOG_FILE_NAME)
    }

    fun logPath(context: Context, packageName: String): String = moduleLogFile(context, packageName).absolutePath

    fun appendModuleLog(context: Context, packageName: String, line: String) {
        val file = moduleLogFile(context, packageName)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        rotateIfNeeded(file)
        file.appendText(line)
    }

    fun readLog(context: Context, packageName: String, maxLines: Int = 800): Result<String> = runCatching {
        val moduleFile = moduleLogFile(context, packageName)
        val moduleText = readLocalTail(moduleFile, maxLines)
        if (moduleText.isNotBlank()) {
            return@runCatching moduleText
        }

        "暂无日志"
    }

    fun clearLog(context: Context, packageName: String): Result<Unit> = runCatching {
        val moduleFile = moduleLogFile(context, packageName)
        moduleFile.parentFile?.mkdirs()
        moduleFile.writeText("")
    }

    private fun readLocalTail(file: File, maxLines: Int): String {
        if (!file.exists()) return ""
        return file.readLines().takeLast(maxLines).joinToString("\n")
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val old = File(file.parentFile, "$LOG_FILE_NAME.1")
            if (old.exists()) old.delete()
            file.renameTo(old)
        }
    }

    private fun sanitizePackageName(packageName: String): String {
        return packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
