package top.lovepikachu.XiaoHeiHook.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

class LogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_EVENT) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return
        val line = intent.getStringExtra(EXTRA_LINE) ?: return
        runCatching {
            AppLogRepository.appendModuleLog(context, packageName, line)
        }.onFailure {
            Log.e(TAG, "append log failed: package=$packageName", it)
        }
    }

    companion object {
        private const val TAG = "XiaoHeiHook-LogReceiver"
        const val ACTION_LOG_EVENT = "top.lovepikachu.XiaoHeiHook.LOG_EVENT"
        const val ACTION_LOG_EVENT_LIVE = "top.lovepikachu.XiaoHeiHook.LOG_EVENT_LIVE"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_LINE = "line"
    }
}
