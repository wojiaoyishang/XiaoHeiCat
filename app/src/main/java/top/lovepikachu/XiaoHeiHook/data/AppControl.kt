package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

object AppControl {
    private const val TAG = "XiaoHeiHook-AppControl"

    fun launchPackage(context: Context, packageName: String): Result<Unit> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("找不到可启动入口：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.d(TAG, "launchPackage: $packageName")
    }

    fun openSystemSettings(context: Context, packageName: String): Result<Unit> = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d(TAG, "openSystemSettings: $packageName")
    }

    fun forceStop(packageName: String): Result<Unit> = runCatching {
        val command = "am force-stop ${shellQuote(packageName)}"
        val result = runRootCommand(command, timeoutSeconds = 8)
        if (result.exitCode != 0 || result.timedOut) {
            throw IllegalStateException("强制终止失败：exit=${result.exitCode}, timeout=${result.timedOut}, stderr=${result.stderr.trim()}")
        }
        Log.d(TAG, "forceStop: $packageName")
    }

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    )

    private fun runRootCommand(command: String, timeoutSeconds: Long): RootCommandResult {
        return runCatching {
            Log.d(TAG, "root exec: su -c $command")
            val process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@runCatching RootCommandResult(-1, "", "timeout", timedOut = true)
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            RootCommandResult(process.exitValue(), stdout, stderr)
        }.getOrElse { error ->
            Log.w(TAG, "root exec failed: $command", error)
            RootCommandResult(-1, "", error.message.orEmpty())
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
