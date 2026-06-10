package top.lovepikachu.XiaoHeiHook.composables

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.permissions.ForegroundServicePermissionHelper

@Composable
fun ForegroundServicePermissionDialog(
    serviceName: String,
    onDismiss: () -> Unit,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionState by remember { mutableStateOf(ForegroundServicePermissionHelper.check(context)) }

    fun refreshPermissionState() {
        val latest = ForegroundServicePermissionHelper.check(context)
        permissionState = latest
        if (latest.allGranted) {
            onAllGranted()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && !ForegroundServicePermissionHelper.areNotificationsAllowed(context)) {
            ForegroundServicePermissionHelper.openNotificationSettings(context)
        }
        refreshPermissionState()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.service_permission_title, serviceName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.service_permission_message, serviceName))
                if (!permissionState.notificationsAllowed) {
                    Text(stringResource(R.string.service_permission_notification_missing))
                }
                if (!permissionState.batteryOptimizationsIgnored) {
                    Text(stringResource(R.string.service_permission_battery_missing))
                }
                if (!permissionState.notificationsAllowed) {
                    Button(
                        onClick = {
                            if (ForegroundServicePermissionHelper.shouldRequestPostNotificationPermission(context)) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ForegroundServicePermissionHelper.openNotificationSettings(context)
                                refreshPermissionState()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.service_permission_open_notifications))
                    }
                }
            }
        },
        confirmButton = {
            Column {
                if (!permissionState.batteryOptimizationsIgnored) {
                    Button(
                        onClick = {
                            ForegroundServicePermissionHelper.openBatteryOptimizationSettings(context)
                            refreshPermissionState()
                        }
                    ) {
                        Text(stringResource(R.string.service_permission_open_battery))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(onClick = { refreshPermissionState() }) {
                    Text(stringResource(R.string.service_permission_recheck))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
