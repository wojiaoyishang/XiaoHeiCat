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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard
import top.lovepikachu.XiaoHeiHook.webide.BatteryOptimizationHelper
import top.lovepikachu.XiaoHeiHook.webide.WebIdeDefaults
import top.lovepikachu.XiaoHeiHook.webide.WebIdeManager

@Composable
fun WebIdeSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by WebIdeManager.status.collectAsState()
    var hostText by remember { mutableStateOf(WebIdeManager.loadConfig(context).host) }
    var portText by remember { mutableStateOf(WebIdeManager.loadConfig(context).port.toString()) }
    var showDangerDialog by remember { mutableStateOf(false) }
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

    if (showDangerDialog) {
        AlertDialog(
            onDismissRequest = { showDangerDialog = false },
            title = { Text("危险操作确认") },
            text = {
                Text(
                    text = "开启 WebIDE 会在手机上启动 HTTP 服务。任何能访问该地址的人都可能读取、修改、删除脚本，并触发脚本同步。\n\n" +
                        "建议默认绑定 127.0.0.1，并通过 adb forward tcp:${portText.ifBlank { WebIdeDefaults.DEFAULT_PORT.toString() }} tcp:${portText.ifBlank { WebIdeDefaults.DEFAULT_PORT.toString() }} 在电脑访问。\n\n" +
                        "请确认你理解风险后再开启。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDangerDialog = false
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            val message = "端口必须是 1024 到 65535"
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
                                    Toast.makeText(context, "WebIDE 已启动：${it.baseUrl}", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { error ->
                                    pendingError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, "WebIDE 启动失败：$pendingError", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                ) {
                    Text("确认开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDangerDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WebIDE",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (status.running) "正在监听 ${status.baseUrl}" else "未启动。开启后可在电脑浏览器编辑脚本。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status.running,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showDangerDialog = true
                        } else {
                            WebIdeManager.stop(context)
                            Toast.makeText(context, "WebIDE 已关闭", Toast.LENGTH_SHORT).show()
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
                    label = { Text("绑定地址") },
                    placeholder = { Text(WebIdeDefaults.DEFAULT_HOST) },
                    modifier = Modifier.weight(1.35f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                    enabled = !status.running,
                    singleLine = true,
                    label = { Text("端口") },
                    placeholder = { Text(WebIdeDefaults.DEFAULT_PORT.toString()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.75f)
                )
            }

            Text(
                text = if (status.running) {
                    "电脑访问：adb forward tcp:${status.port} tcp:${status.port} 后打开 http://127.0.0.1:${status.port}/"
                } else {
                    "默认 127.0.0.1 只允许本机/ADB 转发访问；0.0.0.0 会暴露到网络，请谨慎。"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!batteryIgnored) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "未忽略电池优化时，部分系统会在后台冻结 WebIDE。请先允许忽略电池优化，再切后台使用。",
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
                        Text("忽略电池优化")
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
