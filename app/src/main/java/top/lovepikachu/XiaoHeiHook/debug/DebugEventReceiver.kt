package top.lovepikachu.XiaoHeiHook.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

class DebugEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DebugProtocol.ACTION_EVENT) return
        try {
            val raw = intent.getStringExtra(DebugProtocol.EXTRA_EVENT_JSON)
            val obj = if (!raw.isNullOrBlank()) {
                JSONObject(raw)
            } else {
                JSONObject()
                    .put("type", "debug")
                    .put("packageName", intent.getStringExtra(DebugProtocol.EXTRA_PACKAGE_NAME).orEmpty())
                    .put("processName", intent.getStringExtra(DebugProtocol.EXTRA_PROCESS_NAME).orEmpty())
                    .put("pauseId", intent.getStringExtra(DebugProtocol.EXTRA_PAUSE_ID).orEmpty())
                    .put("message", "debug event")
            }
            if (!obj.has("receivedAt")) obj.put("receivedAt", System.currentTimeMillis())
            DebugEventRepository.append(context, obj)
        } catch (t: Throwable) {
            Log.w("XiaoHeiHook-Debug", "保存调试事件失败", t)
        }
    }
}
