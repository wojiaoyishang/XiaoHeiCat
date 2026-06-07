package top.lovepikachu.XiaoHeiHook.webide

import android.content.Context
import java.io.File
import java.util.Locale

object WebIdeLogMaintenance {
    /**
     * The management app stores target-process logs under files/logs.
     * Do not infer size from a single probe file; walk the whole directory so
     * nested/rotated log files are counted as well.
     */
    fun logDir(context: Context): File {
        return File(context.applicationContext.filesDir, "logs")
    }

    fun totalLogSize(context: Context): Long {
        val dir = logDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { acc, file -> acc + file.length() }
    }

    fun logFileCount(context: Context): Int {
        val dir = logDir(context)
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    fun clearAllLogs(context: Context): ClearResult {
        val dir = logDir(context)
        if (!dir.exists()) return ClearResult(0, 0L, 0)

        var deletedCount = 0
        var clearedBytes = 0L
        var failedCount = 0

        dir.walkBottomUp()
            .filter { it != dir }
            .toList()
            .forEach { file ->
                if (file.isFile) {
                    val len = file.length()
                    val ok = runCatching { file.delete() || run { file.writeText(""); true } }.getOrDefault(false)
                    if (ok) {
                        deletedCount++
                        clearedBytes += len
                    } else {
                        failedCount++
                    }
                } else if (file.isDirectory) {
                    runCatching { file.delete() }
                }
            }

        runCatching { dir.mkdirs() }
        return ClearResult(deletedCount, clearedBytes, failedCount)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    data class ClearResult(
        val deletedFiles: Int,
        val clearedBytes: Long,
        val failedFiles: Int
    )
}
