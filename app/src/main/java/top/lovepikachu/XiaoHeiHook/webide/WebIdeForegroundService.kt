package top.lovepikachu.XiaoHeiHook.webide

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import top.lovepikachu.XiaoHeiHook.MainActivity
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.keepalive.MainProcessKeepAliveService

class WebIdeForegroundService : Service() {

    private var server: WebIdeServer? = null
    private var serverHost: String = WebIdeDefaults.DEFAULT_HOST
    private var serverPort: Int = WebIdeDefaults.DEFAULT_PORT
    private var runningHost: String = WebIdeDefaults.DEFAULT_HOST
    private var runningPort: Int = WebIdeDefaults.DEFAULT_PORT
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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

        // 用户显式开启后作为前台服务粘性运行。系统在后台回收服务进程后，会用 null intent 重建；
        // 此时从持久化配置读取 enabled=true 并恢复 WebIDE。主应用进程真正重启时会先重置为关闭。
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime(updateSavedConfig = false)
        stopForegroundCompat()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户切后台或移除最近任务时不停止 WebIDE；服务关闭只由设置开关或通知动作控制。
        Log.i(TAG, "task removed, keep WebIDE foreground runtime running")
        acquireKeepAliveLocks()
        runCatching { updateNotification() }
            .onFailure { Log.w(TAG, "refresh WebIDE notification after task removed failed", it) }
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
        MainProcessKeepAliveService.startIfNeeded(applicationContext, MainProcessKeepAliveService.REASON_WEBIDE)
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
        MainProcessKeepAliveService.stopIfNotNeeded(applicationContext)
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
                val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    legacyWifiLockMode()
                }
                wifiLock = wifiManager.createWifiLock(
                    wifiLockMode,
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

    @Suppress("DEPRECATION")
    private fun legacyWifiLockMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

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

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeMask())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypeMask(): Int {
        // WebIDE is a user-visible local file/script editing server.  Declare it as dataSync
        // instead of specialUse/connectedDevice so ColorOS/OPlus can classify it more accurately.
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
            .setContentTitle(getString(R.string.notification_webide_title))
            .setContentText(getString(R.string.notification_webide_running))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.notification_webide_running)))
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
        val title = getString(R.string.notification_webide_stop_action)
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
            getString(R.string.notification_webide_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_webide_running)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun baseUrl(host: String, port: Int): String = "http://$host:$port/"

    companion object {
        private const val TAG = "XiaoHeiHook-WebIDE-FGS"
        private const val CHANNEL_ID = "xiaoheihook_webide"
        private const val NOTIFICATION_ID = 8787
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
