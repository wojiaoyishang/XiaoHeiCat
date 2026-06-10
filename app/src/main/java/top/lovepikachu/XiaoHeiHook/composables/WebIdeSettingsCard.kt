package top.lovepikachu.XiaoHeiHook.composables

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.permissions.ForegroundServicePermissionHelper
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard
import top.lovepikachu.XiaoHeiHook.webide.BatteryOptimizationHelper
import top.lovepikachu.XiaoHeiHook.webide.WebIdeDefaults
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager
import top.lovepikachu.XiaoHeiHook.webide.WebIdeLogMaintenance

@Composable
fun WebIdeSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by WebIdeManager.status.collectAsState()
    var hostText by remember { mutableStateOf(WebIdeManager.loadConfig(context).host) }
    var portText by remember { mutableStateOf(WebIdeManager.loadConfig(context).port.toString()) }
    var showDangerDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var batteryIgnored by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }

    LaunchedEffect(Unit) {
        WebIdeManager.syncStatusWithSavedConfig(context)
        batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    if (showPermissionDialog) {
        ForegroundServicePermissionDialog(
            serviceName = stringResource(R.string.webide_title),
            onDismiss = { showPermissionDialog = false },
            onAllGranted = {
                showPermissionDialog = false
                showDangerDialog = true
                batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        )
    }

    if (showDangerDialog) {
        AlertDialog(
            onDismissRequest = { showDangerDialog = false },
            title = { Text(stringResource(R.string.webide_danger_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.webide_danger_message,
                        portText.ifBlank { WebIdeDefaults.DEFAULT_PORT.toString() },
                        portText.ifBlank { WebIdeDefaults.DEFAULT_PORT.toString() }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDangerDialog = false
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            val message = context.getString(R.string.webide_port_invalid)
                            pendingError = message
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            val host = hostText.ifBlank { WebIdeDefaults.DEFAULT_HOST }
                            WebIdeManager.start(context, host, port)
                                .onSuccess {
                                    hostText = it.host
                                    portText = it.port.toString()
                                    pendingError = null
                                    batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                                    Toast.makeText(context, context.getString(R.string.webide_started, it.baseUrl), Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { error ->
                                    pendingError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, context.getString(R.string.webide_start_failed, pendingError), Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                ) {
                    Text(stringResource(R.string.webide_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDangerDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }


    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.webide_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (status.running) stringResource(R.string.webide_running_at, status.baseUrl) else stringResource(R.string.webide_not_running_hint),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = status.running,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (ForegroundServicePermissionHelper.check(context).allGranted) {
                                    showDangerDialog = true
                                } else {
                                    showPermissionDialog = true
                                }
                            } else {
                                WebIdeManager.stop(context)
                                Toast.makeText(context, context.getString(R.string.webide_stopped), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        enabled = !status.running,
                        singleLine = true,
                        label = { Text(stringResource(R.string.webide_host_label)) },
                        placeholder = { Text(WebIdeDefaults.DEFAULT_HOST) },
                        modifier = Modifier.weight(1.35f)
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                        enabled = !status.running,
                        singleLine = true,
                        label = { Text(stringResource(R.string.webide_port_label)) },
                        placeholder = { Text(WebIdeDefaults.DEFAULT_PORT.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.75f)
                    )
                }

                Text(
                    text = if (status.running) {
                        stringResource(R.string.webide_adb_access_hint, status.port, status.port, status.port)
                    } else {
                        stringResource(R.string.webide_bind_hint)
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!batteryIgnored) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.webide_battery_warning),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                val opened = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                                if (!opened) {
                                    batteryIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.webide_ignore_battery))
                        }
                    }
                }

                val errorText = pendingError ?: status.lastError
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun WebIdeLogMaintenanceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var logSizeBytes by remember { mutableStateOf(WebIdeLogMaintenance.totalLogSize(context)) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logSizeBytes = WebIdeLogMaintenance.totalLogSize(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                logSizeBytes = WebIdeLogMaintenance.totalLogSize(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            title = { Text(stringResource(R.string.webide_clear_logs_title)) },
            text = {
                Text(stringResource(R.string.webide_clear_logs_message, WebIdeLogMaintenance.formatBytes(logSizeBytes)))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = WebIdeLogMaintenance.clearAllLogs(context)
                        logSizeBytes = WebIdeLogMaintenance.totalLogSize(context)
                        showClearLogsDialog = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.webide_logs_cleared, result.deletedFiles, WebIdeLogMaintenance.formatBytes(result.clearedBytes)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.webide_clear_logs_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.webide_log_maintenance),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.webide_log_dir, WebIdeLogMaintenance.logDir(context).absolutePath),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = stringResource(R.string.webide_log_stats, WebIdeLogMaintenance.formatBytes(logSizeBytes), WebIdeLogMaintenance.logFileCount(context)),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { logSizeBytes = WebIdeLogMaintenance.totalLogSize(context) }) {
                    Text(stringResource(R.string.webide_refresh_log_size))
                }
                Button(onClick = { showClearLogsDialog = true }) {
                    Text(stringResource(R.string.webide_clear_all_logs))
                }
            }
        }
    }
}
