package top.lovepikachu.XiaoHeiHook.keepalive

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import top.lovepikachu.XiaoHeiHook.MainActivity
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.webide.ProcessUtil
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager

/**
 * Foreground-service anchor for the normal app process.
 *
 * WebIDE and MCP run in their own foreground-service processes, but some APIs still
 * cross into the normal process through WebIdeBridgeProvider to use Remote Preferences
 * and XposedService. On some ROMs that normal process can be heavily throttled while
 * the UI is backgrounded, which makes bridge calls slow even though :webide / :mcp
 * are alive. This service is enabled only while WebIDE or MCP is enabled.
 */
class MainProcessKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatScheduled = false
    private var lastReason: String = ""

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatScheduled) return
            if (!shouldKeepAlive(this@MainProcessKeepAliveService)) {
                Log.i(TAG, "heartbeat found no active WebIDE/MCP config, stopping main-process keepalive")
                stopSelfSafely()
                return
            }
            Log.d(TAG, "heartbeat active reason=$lastReason process=${ProcessUtil.currentProcessName(this@MainProcessKeepAliveService)}")
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        Log.i(TAG, "created pid=${android.os.Process.myPid()} process=${ProcessUtil.currentProcessName(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        lastReason = intent?.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "refresh" }
        if (!shouldKeepAlive(this)) {
            Log.i(TAG, "no active WebIDE/MCP config, skip main-process keepalive")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification())
        acquireWakeLock()
        scheduleHeartbeat()
        Log.i(TAG, "main-process keepalive active reason=$lastReason")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, keep main-process bridge anchor running")
        if (shouldKeepAlive(this)) {
            acquireWakeLock()
            runCatching { updateNotification() }
                .onFailure { Log.w(TAG, "refresh main-process keepalive notification failed", it) }
        } else {
            stopSelfSafely()
        }
    }

    override fun onDestroy() {
        heartbeatScheduled = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        releaseWakeLock()
        stopForegroundCompat()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:MainBridgeWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }.onFailure { Log.w(TAG, "acquire main-process keepalive wake lock failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "release main-process keepalive wake lock failed", it) }
        wakeLock = null
    }

    private fun scheduleHeartbeat() {
        if (heartbeatScheduled) return
        heartbeatScheduled = true
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopSelfSafely() {
        heartbeatScheduled = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
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
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = getString(R.string.notification_main_keepalive_running)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_main_keepalive_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_main_keepalive_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_main_keepalive_running)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "XiaoHeiHook-MainKeepAlive"
        private const val CHANNEL_ID = "xiaoheihook_main_keepalive"
        private const val NOTIFICATION_ID = 28787
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val BRIDGE_START_THROTTLE_MS = 15_000L
        @Volatile private var lastBridgeStartAttemptUptime: Long = 0L
        const val ACTION_START = "top.lovepikachu.XiaoHeiHook.keepalive.START"
        const val ACTION_STOP = "top.lovepikachu.XiaoHeiHook.keepalive.STOP"
        const val EXTRA_REASON = "reason"
        const val REASON_WEBIDE = "webide"
        const val REASON_MCP = "mcp"
        const val REASON_BRIDGE = "bridge"

        fun startIfNeeded(context: Context, reason: String): Result<Unit> = runCatching {
            val appContext = context.applicationContext
            if (reason == REASON_BRIDGE) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBridgeStartAttemptUptime < BRIDGE_START_THROTTLE_MS) return@runCatching
                lastBridgeStartAttemptUptime = now
            }
            if (!shouldKeepAlive(appContext)) {
                stopIfNotNeeded(appContext)
                return@runCatching
            }
            val intent = Intent(appContext, MainProcessKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure { Log.w(TAG, "start main-process keepalive failed reason=$reason", it) }

        fun stopIfNotNeeded(context: Context): Result<Unit> = runCatching {
            val appContext = context.applicationContext
            if (shouldKeepAlive(appContext)) {
                startIfNeeded(appContext, "refresh")
                Unit
            } else {
                appContext.stopService(Intent(appContext, MainProcessKeepAliveService::class.java))
                Unit
            }
        }.onFailure { Log.w(TAG, "stop main-process keepalive failed", it) }


        fun shouldKeepAlive(context: Context): Boolean {
            val appContext = context.applicationContext
            return runCatching { WebIdeManager.loadConfig(appContext).enabled }.getOrDefault(false) ||
                runCatching { McpManager.loadConfig(appContext).enabled }.getOrDefault(false)
        }
    }
}
