package top.lovepikachu.XiaoHeiHook.mcp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.drawable.Icon
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.system.exitProcess
import top.lovepikachu.XiaoHeiHook.MainActivity
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.keepalive.MainProcessKeepAliveService
import top.lovepikachu.XiaoHeiHook.webide.ProcessUtil

class McpForegroundService : Service() {
    private var server: McpServer? = null
    private var bridgeServer: McpBridgeSocketServer? = null
    private var bridgeDiscoveryRegistered: Boolean = false
    private var serverHost: String = McpDefaults.DEFAULT_HOST
    private var serverPort: Int = McpDefaults.DEFAULT_PORT
    private var runningHost: String = McpDefaults.DEFAULT_HOST
    private var runningPort: Int = McpDefaults.DEFAULT_PORT
    private var runningTokenEnabled: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var processKillScheduled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        Log.i(TAG, "created in process pid=${android.os.Process.myPid()} process=${ProcessUtil.currentProcessName(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopRuntime(updateSavedConfig = true)
            stopForegroundCompat()
            stopSelf(startId)
            killMcpProcessSoon("ACTION_STOP")
            return START_NOT_STICKY
        }

        val config = if (intent == null || action.isNullOrBlank()) {
            McpManager.loadConfig(this)
        } else {
            McpConfig(
                enabled = true,
                host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { McpDefaults.DEFAULT_HOST },
                port = intent.getIntExtra(EXTRA_PORT, McpDefaults.DEFAULT_PORT).coerceIn(1024, 65535),
                tokenEnabled = intent.getBooleanExtra(EXTRA_TOKEN_ENABLED, false),
                token = McpSecurity.token(this)
            )
        }

        if (!config.enabled && (intent == null || action.isNullOrBlank())) {
            stopRuntime(updateSavedConfig = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runningHost = McpManager.sanitizeHost(config.host)
        runningPort = config.port.coerceIn(1024, 65535)
        runningTokenEnabled = config.tokenEnabled
        startForegroundCompat(buildNotification())

        runCatching {
            startRuntime(runningHost, runningPort, runningTokenEnabled)
        }.fold(
            onSuccess = {
                McpManager.markServiceRunning(this, runningHost, runningPort, runningTokenEnabled)
                updateNotification()
            },
            onFailure = { error ->
                Log.e(TAG, "MCP foreground service failed", error)
                McpManager.markServiceError(this, runningHost, runningPort, runningTokenEnabled, error)
                updateNotification()
                stopRuntime(updateSavedConfig = false)
                stopForegroundCompat()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        )

        // 用户显式开启后作为前台服务粘性运行。系统在后台回收服务进程后，会用 null intent 重建；
        // 此时从持久化配置读取 enabled=true 并恢复 MCP HTTP server 与 bridge。主应用进程真正重启时会先重置为关闭。
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime(updateSavedConfig = false)
        stopForegroundCompat()
        Log.i(TAG, "destroyed")
        killMcpProcessSoon("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户切后台或移除最近任务时不停止 MCP；服务关闭只由设置开关或通知动作控制。
        Log.i(TAG, "task removed, keep MCP foreground runtime running")
        acquireKeepAliveLocks()
        runCatching { updateNotification() }
            .onFailure { Log.w(TAG, "refresh MCP notification after task removed failed", it) }
    }

    private fun startRuntime(host: String, port: Int, tokenEnabled: Boolean) {
        val current = server
        if (current != null && serverHost == host && serverPort == port) {
            acquireKeepAliveLocks()
            current.touch("reuse existing runtime")
            return
        }
        stopServerOnly()
        acquireKeepAliveLocks()
        val newServer = McpServer(applicationContext, host, port, tokenEnabled)
        newServer.startServer()
        MainProcessKeepAliveService.startIfNeeded(applicationContext, MainProcessKeepAliveService.REASON_MCP)
        runtimeActive = true
        server = newServer
        serverHost = host
        serverPort = port
        startBridgeRuntime()
    }

    private fun stopRuntime(updateSavedConfig: Boolean) {
        stopServerOnly()
        releaseKeepAliveLocks()
        McpManager.markServiceStopped(this, runningHost, runningPort, runningTokenEnabled, updateSavedConfig)
        MainProcessKeepAliveService.stopIfNotNeeded(applicationContext)
    }

    private fun stopServerOnly() {
        stopBridgeRuntime()
        runCatching { server?.stopServer() }
            .onFailure { Log.w(TAG, "stop server failed", it) }
        server = null
        runtimeActive = false
        serverHost = McpDefaults.DEFAULT_HOST
        serverPort = McpDefaults.DEFAULT_PORT
    }

    private fun startBridgeRuntime() {
        runCatching {
            val bridge = McpBridgeSocketServer(applicationContext, 0)
            bridge.start()
            bridgeServer = bridge
            registerBridgeDiscoveryReceiver()
            Log.i(TAG, "MCP bridge runtime started host=${McpBridgeSocketServer.BRIDGE_HOST} port=${bridge.actualPort}")
        }.onFailure { error ->
            Log.w(TAG, "start MCP bridge runtime failed", error)
            bridgeServer?.stop()
            bridgeServer = null
            throw error
        }
    }

    private fun stopBridgeRuntime() {
        unregisterBridgeDiscoveryReceiver()
        runCatching { bridgeServer?.stop() }
            .onFailure { Log.w(TAG, "stop MCP bridge runtime failed", it) }
        bridgeServer = null
    }

    private fun registerBridgeDiscoveryReceiver() {
        if (bridgeDiscoveryRegistered) return
        val filter = IntentFilter(McpBridgeProtocol.ACTION_DISCOVER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bridgeDiscoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bridgeDiscoveryReceiver, filter)
        }
        bridgeDiscoveryRegistered = true
        Log.i(TAG, "MCP bridge discovery receiver registered action=${McpBridgeProtocol.ACTION_DISCOVER}")
    }

    private fun unregisterBridgeDiscoveryReceiver() {
        if (!bridgeDiscoveryRegistered) return
        runCatching { unregisterReceiver(bridgeDiscoveryReceiver) }
            .onFailure { Log.w(TAG, "unregister MCP bridge discovery receiver failed", it) }
        bridgeDiscoveryRegistered = false
    }

    private val bridgeDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != McpBridgeProtocol.ACTION_DISCOVER) return
            val bridge = bridgeServer
            val requestPackage = intent.getStringExtra(McpBridgeProtocol.EXTRA_PACKAGE_NAME).orEmpty()
            val requestProcess = intent.getStringExtra(McpBridgeProtocol.EXTRA_PROCESS_NAME).orEmpty()
            val requestSession = intent.getStringExtra(McpBridgeProtocol.EXTRA_SESSION_ID).orEmpty()
            val token = intent.getStringExtra(McpBridgeProtocol.EXTRA_BRIDGE_TOKEN).orEmpty()
            val debugLog = intent.getBooleanExtra(McpBridgeProtocol.EXTRA_DEBUG_LOG, false)
            if (!runtimeActive || bridge == null || bridge.actualPort <= 0) {
                if (debugLog) Log.w(TAG, "ignore MCP bridge discovery because runtime is not active package=$requestPackage process=$requestProcess session=$requestSession")
                return
            }
            if (!McpSecurity.isValid(applicationContext, token)) {
                Log.w(TAG, "ignore MCP bridge discovery with invalid token package=$requestPackage process=$requestProcess session=$requestSession")
                return
            }
            val extras = Bundle().apply {
                putString(McpBridgeProtocol.EXTRA_BRIDGE_HOST, McpBridgeSocketServer.BRIDGE_HOST)
                putInt(McpBridgeProtocol.EXTRA_BRIDGE_PORT, bridge.actualPort)
            }
            setResultCode(Activity.RESULT_OK)
            setResultExtras(extras)
            if (debugLog) Log.i(TAG, "reply MCP bridge discovery package=$requestPackage process=$requestProcess session=$requestSession host=${McpBridgeSocketServer.BRIDGE_HOST} port=${bridge.actualPort}")
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireKeepAliveLocks() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:MCPWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }.onFailure { Log.w(TAG, "acquire wake lock failed", it) }

        runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    legacyWifiLockMode()
                }
                wifiLock = wifiManager.createWifiLock(wifiLockMode, "$packageName:MCPWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }.onFailure { Log.w(TAG, "acquire wifi lock failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun legacyWifiLockMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private fun releaseKeepAliveLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun killMcpProcessSoon(reason: String) {
        if (processKillScheduled) return
        processKillScheduled = true

        val pid = android.os.Process.myPid()
        val processName = ProcessUtil.currentProcessName(this)
        if (!processName.endsWith(":mcp")) {
            Log.w(TAG, "skip process kill, current process is not :mcp: $processName")
            return
        }

        Thread({
            runCatching { Thread.sleep(250) }
            Log.i(TAG, "kill :mcp process after MCP stop, reason=$reason, pid=$pid, process=$processName")
            android.os.Process.killProcess(pid)
            exitProcess(0)
        }, "XHH-MCP-process-killer").apply {
            isDaemon = false
            start()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeMask())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypeMask(): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return mask
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_mcp_title))
            .setContentText(getString(R.string.notification_mcp_running))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.notification_mcp_running)))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        builder.addAction(buildStopAction(stopIntent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun buildStopAction(stopIntent: PendingIntent): Notification.Action {
        val title = getString(R.string.notification_mcp_stop_action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                title,
                stopIntent
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                title,
                stopIntent
            ).build()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_mcp_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_mcp_running)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "XiaoHeiHook-MCP-FGS"
        @Volatile private var runtimeActive: Boolean = false

        fun isRuntimeActive(): Boolean = runtimeActive

        private const val CHANNEL_ID = "xiaoheihook_mcp"
        private const val NOTIFICATION_ID = 18787
        const val ACTION_START = "top.lovepikachu.XiaoHeiHook.mcp.START"
        const val ACTION_STOP = "top.lovepikachu.XiaoHeiHook.mcp.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN_ENABLED = "tokenEnabled"

        fun startIntent(context: Context, host: String, port: Int, tokenEnabled: Boolean): Intent {
            return Intent(context, McpForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TOKEN_ENABLED, tokenEnabled)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, McpForegroundService::class.java).setAction(ACTION_STOP)
        }
    }
}
