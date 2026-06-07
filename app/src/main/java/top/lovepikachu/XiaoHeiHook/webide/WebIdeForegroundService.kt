package top.lovepikachu.XiaoHeiHook.webide

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.system.exitProcess
import top.lovepikachu.XiaoHeiHook.R

class WebIdeForegroundService : Service() {

    private var server: WebIdeServer? = null
    private var serverHost: String = WebIdeDefaults.DEFAULT_HOST
    private var serverPort: Int = WebIdeDefaults.DEFAULT_PORT
    private var runningHost: String = WebIdeDefaults.DEFAULT_HOST
    private var runningPort: Int = WebIdeDefaults.DEFAULT_PORT
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
            killWebIdeProcessSoon("ACTION_STOP")
            return START_NOT_STICKY
        }

        val config = if (intent == null || action.isNullOrBlank()) {
            WebIdeManager.loadConfig(this)
        } else {
            WebIdeConfig(
                enabled = true,
                host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { WebIdeDefaults.DEFAULT_HOST },
                port = intent.getIntExtra(EXTRA_PORT, WebIdeDefaults.DEFAULT_PORT).coerceIn(1024, 65535)
            )
        }

        if (!config.enabled && (intent == null || action.isNullOrBlank())) {
            stopRuntime(updateSavedConfig = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runningHost = WebIdeManager.sanitizeHost(config.host)
        runningPort = config.port.coerceIn(1024, 65535)

        // 必须尽快调用 startForeground，避免 Android 8+ 判定前台服务启动超时。
        startForegroundCompat(buildNotification())

        runCatching {
            startRuntime(runningHost, runningPort)
        }.fold(
            onSuccess = {
                WebIdeManager.markServiceRunning(this, runningHost, runningPort)
                updateNotification()
            },
            onFailure = { error ->
                Log.e(TAG, "WebIDE foreground service failed", error)
                WebIdeManager.markServiceError(this, runningHost, runningPort, error)
                updateNotification()
                stopRuntime(updateSavedConfig = false)
                stopForegroundCompat()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        )

        // 用户关闭时必须彻底停止；系统杀掉后也不自动粘性重启，避免残留多余服务。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRuntime(updateSavedConfig = false)
        stopForegroundCompat()
        Log.i(TAG, "destroyed")
        killWebIdeProcessSoon("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // WebIDE 由设置开关和通知点击关闭；移除最近任务不额外创建服务。
        Log.i(TAG, "task removed")
    }

    private fun startRuntime(host: String, port: Int) {
        val current = server
        if (current != null && serverHost == host && serverPort == port) {
            acquireKeepAliveLocks()
            current.touch("reuse existing runtime")
            return
        }

        stopServerOnly()
        acquireKeepAliveLocks()

        val newServer = WebIdeServer(applicationContext, host, port)
        newServer.startServer()
        server = newServer
        serverHost = host
        serverPort = port
        Log.i(TAG, "WebIDE runtime started at ${baseUrl(host, port)} pid=${android.os.Process.myPid()}")
    }

    private fun stopRuntime(updateSavedConfig: Boolean) {
        stopServerOnly()
        releaseKeepAliveLocks()
        runCatching { WebIdeBridgeClient(applicationContext).clearAllDebugState() }
            .onFailure { Log.w(TAG, "clear WebIDE debug state failed", it) }
        WebIdeManager.markServiceStopped(this, runningHost, runningPort, updateSavedConfig)
    }

    private fun stopServerOnly() {
        runCatching { server?.stopServer() }
            .onFailure { Log.w(TAG, "stop server failed", it) }
        server = null
        serverHost = WebIdeDefaults.DEFAULT_HOST
        serverPort = WebIdeDefaults.DEFAULT_PORT
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireKeepAliveLocks() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:WebIDEWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "wake lock acquired")
            }
        }.onFailure {
            Log.w(TAG, "acquire wake lock failed", it)
        }

        runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$packageName:WebIDEWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "wifi lock acquired")
            }
        }.onFailure {
            Log.w(TAG, "acquire wifi lock failed", it)
        }
    }

    private fun releaseKeepAliveLocks() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }.onFailure { Log.w(TAG, "release wifi lock failed", it) }
        wifiLock = null

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }.onFailure { Log.w(TAG, "release wake lock failed", it) }
        wakeLock = null
    }

    private fun killWebIdeProcessSoon(reason: String) {
        if (processKillScheduled) return
        processKillScheduled = true

        val pid = android.os.Process.myPid()
        val processName = ProcessUtil.currentProcessName(this)
        if (!processName.endsWith(":webide")) {
            Log.w(TAG, "skip process kill, current process is not :webide: $processName")
            return
        }

        Thread({
            runCatching { Thread.sleep(250) }
            Log.i(TAG, "kill :webide process after WebIDE stop, reason=$reason, pid=$pid, process=$processName")
            android.os.Process.killProcess(pid)
            exitProcess(0)
        }, "XHH-WebIDE-process-killer").apply {
            isDaemon = false
            start()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // 同时声明 dataSync，让系统按“正在进行的数据传输/本地服务”维持调度。
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        }
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
            Intent(this, WebIdeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("XiaoHeiHook WebIDE")
            .setContentText(NOTIFICATION_TEXT)
            .setStyle(Notification.BigTextStyle().bigText(NOTIFICATION_TEXT))
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭 WebIDE", stopIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WebIDE 服务",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = NOTIFICATION_TEXT
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun baseUrl(host: String, port: Int): String = "http://$host:$port/"

    companion object {
        private const val TAG = "XiaoHeiHook-WebIDE-FGS"
        private const val CHANNEL_ID = "xiaoheihook_webide_v11"
        private const val NOTIFICATION_ID = 8787
        private const val NOTIFICATION_TEXT = "WebIDE运行中，点击此处可关闭"
        const val ACTION_START = "top.lovepikachu.XiaoHeiHook.webide.START"
        const val ACTION_STOP = "top.lovepikachu.XiaoHeiHook.webide.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        fun startIntent(context: Context, host: String, port: Int): Intent {
            return Intent(context, WebIdeForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, WebIdeForegroundService::class.java).setAction(ACTION_STOP)
        }
    }
}
