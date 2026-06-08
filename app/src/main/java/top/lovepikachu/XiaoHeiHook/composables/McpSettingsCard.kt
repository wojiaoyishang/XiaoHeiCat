package top.lovepikachu.XiaoHeiHook.composables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.mcp.McpDefaults
import top.lovepikachu.XiaoHeiHook.mcp.McpManager
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun McpSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by McpManager.status.collectAsState()
    val savedConfig = remember { McpManager.loadConfig(context) }
    var hostText by remember { mutableStateOf(savedConfig.host) }
    var portText by remember { mutableStateOf(savedConfig.port.toString()) }
    var tokenEnabled by remember { mutableStateOf(savedConfig.tokenEnabled) }
    var showDangerDialog by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        McpManager.syncStatusWithSavedConfig(context)
    }

    if (showDangerDialog) {
        AlertDialog(
            onDismissRequest = { showDangerDialog = false },
            title = { Text(stringResource(R.string.mcp_danger_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.mcp_danger_message,
                        portText.ifBlank { McpDefaults.DEFAULT_PORT.toString() },
                        portText.ifBlank { McpDefaults.DEFAULT_PORT.toString() }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDangerDialog = false
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            val message = context.getString(R.string.mcp_port_invalid)
                            pendingError = message
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            val host = hostText.ifBlank { McpDefaults.DEFAULT_HOST }
                            McpManager.start(context, host, port, tokenEnabled)
                                .onSuccess {
                                    hostText = it.host
                                    portText = it.port.toString()
                                    pendingError = null
                                    Toast.makeText(context, context.getString(R.string.mcp_started, it.baseUrl), Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { error ->
                                    pendingError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, context.getString(R.string.mcp_start_failed, pendingError), Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                ) {
                    Text(stringResource(R.string.mcp_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDangerDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
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
                        text = stringResource(R.string.mcp_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (status.running) {
                            stringResource(R.string.mcp_running_at, status.baseUrl)
                        } else {
                            stringResource(R.string.mcp_not_running_hint)
                        },
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
                            McpManager.stop(context)
                            Toast.makeText(context, context.getString(R.string.mcp_stopped), Toast.LENGTH_SHORT).show()
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
                    placeholder = { Text(McpDefaults.DEFAULT_HOST) },
                    modifier = Modifier.weight(1.35f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                    enabled = !status.running,
                    singleLine = true,
                    label = { Text(stringResource(R.string.webide_port_label)) },
                    placeholder = { Text(McpDefaults.DEFAULT_PORT.toString()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.75f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcp_token_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (tokenEnabled) stringResource(R.string.mcp_token_enabled_hint) else stringResource(R.string.mcp_token_disabled_hint),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = tokenEnabled,
                    enabled = !status.running,
                    onCheckedChange = { checked -> tokenEnabled = checked }
                )
            }

            if (tokenEnabled) {
                val tokenValue = status.token.ifBlank { McpManager.loadConfig(context).token }
                OutlinedTextField(
                    value = tokenValue,
                    onValueChange = {},
                    readOnly = true,
                    enabled = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.mcp_token_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                copyMcpTokenToClipboard(context, tokenValue)
                                Toast.makeText(context, context.getString(R.string.mcp_token_copied), Toast.LENGTH_SHORT).show()
                            }
                        ),
                    supportingText = { Text(stringResource(R.string.mcp_token_copy_hint)) }
                )
                Button(
                    enabled = !status.running,
                    onClick = {
                        McpManager.rotateToken(context)
                        Toast.makeText(context, context.getString(R.string.mcp_token_rotated), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.mcp_rotate_token))
                }
            }

            Text(
                text = if (status.running) {
                    stringResource(R.string.mcp_adb_access_hint, status.port, status.port)
                } else {
                    stringResource(R.string.mcp_bind_hint)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

private fun copyMcpTokenToClipboard(context: Context, token: String) {
    if (token.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mcp_token_label), token))
}
