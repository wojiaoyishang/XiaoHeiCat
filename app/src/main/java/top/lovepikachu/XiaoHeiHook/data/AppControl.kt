package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

object AppControl {
    private const val TAG = "XiaoHeiHook-AppCtrl"

    fun openApp(context: Context, packageName: String): Result<Unit> = runCatching {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("找不到可启动 Activity：$packageName")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Log.d(TAG, "openApp: $packageName")
    }

    fun openAppSettings(context: Context, packageName: String): Result<Unit> = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d(TAG, "openAppSettings: $packageName")
    }

    fun forceStop(packageName: String): Result<Unit> = runCatching {
        val result = runRootCommand("am force-stop ${shellQuote(packageName)}", timeoutSeconds = 8)
        if (result.timedOut || result.exitCode != 0) {
            throw IllegalStateException("强制终止失败：exit=${result.exitCode}, stderr=${result.stderr.trim()}")
        }
        Log.d(TAG, "forceStop: $packageName")
    }

    fun restartApp(context: Context, packageName: String): Result<Unit> {
        return forceStop(packageName).mapCatching {
            Thread.sleep(650)
            openApp(context, packageName).getOrThrow()
            Log.d(TAG, "restartApp: $packageName")
        }
    }

    fun isAdbEnabled(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
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
