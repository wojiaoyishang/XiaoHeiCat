package top.lovepikachu.XiaoHeiHook.mcp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

class McpBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_BINDER) return null
        if (!McpForegroundService.isRuntimeActive()) {
            Log.w(TAG, "MCP IPC binder request ignored because MCP runtime is not active")
            killMcpProcessSoon()
            return Bundle().apply { putBoolean("ok", false); putString("reason", McpBridgeProtocol.ERROR_MCP_DISABLED) }
        }
        val ctx = context?.applicationContext ?: return Bundle().apply { putBoolean("ok", false); putString("reason", "NO_CONTEXT") }
        return Bundle().apply {
            putBoolean("ok", true)
            putBinder(KEY_BINDER, McpBridgeService.createBinder(ctx))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun killMcpProcessSoon() {
        Thread({
            runCatching { Thread.sleep(200L) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "XHH-MCP-provider-idle-killer").apply {
            isDaemon = false
            start()
        }
    }

    companion object {
        private const val TAG = "XiaoHeiHook-MCP-IPC"
        const val AUTHORITY = "top.lovepikachu.XiaoHeiHook.mcp.bridge"
        const val METHOD_GET_BINDER = "getBinder"
        const val KEY_BINDER = "binder"
        @JvmField val URI: Uri = Uri.parse("content://$AUTHORITY")
    }
}
