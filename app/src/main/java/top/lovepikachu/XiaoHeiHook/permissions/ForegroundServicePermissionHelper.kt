package top.lovepikachu.XiaoHeiHook.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.webide.BatteryOptimizationHelper

object ForegroundServicePermissionHelper {
    data class State(
        val notificationsAllowed: Boolean,
        val batteryOptimizationsIgnored: Boolean
    ) {
        val allGranted: Boolean
            get() = notificationsAllowed && batteryOptimizationsIgnored
    }

    fun check(context: Context): State {
        return State(
            notificationsAllowed = areNotificationsAllowed(context),
            batteryOptimizationsIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        )
    }

    fun areNotificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return runCatching {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.areNotificationsEnabled()
        }.getOrDefault(true)
    }

    fun shouldRequestPostNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    fun openNotificationSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            appContext.startActivity(notificationIntent)
            true
        }.recoverCatching {
            appContext.startActivity(appDetailsIntent)
            true
        }.onFailure { error ->
            Toast.makeText(
                appContext,
                appContext.getString(
                    R.string.service_permission_open_settings_failed,
                    error.message ?: error.javaClass.simpleName
                ),
                Toast.LENGTH_LONG
            ).show()
        }.getOrDefault(false)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
    }
}
