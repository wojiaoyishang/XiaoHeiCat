package top.lovepikachu.XiaoHeiHook.composables

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.keepalive.AccessibilityKeepAliveManager
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard

@Composable
fun AccessibilityKeepAliveCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requested by remember { mutableStateOf(AccessibilityKeepAliveManager.isRequested(context)) }
    var enabled by remember { mutableStateOf(AccessibilityKeepAliveManager.isServiceEnabled(context)) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun refreshState() {
        requested = AccessibilityKeepAliveManager.isRequested(context)
        enabled = AccessibilityKeepAliveManager.isServiceEnabled(context)
    }

    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) { AccessibilityKeepAliveManager.isRootAvailable() }
        refreshState()
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.accessibility_keepalive_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            enabled -> stringResource(R.string.accessibility_keepalive_enabled_hint)
                            requested -> stringResource(R.string.accessibility_keepalive_requested_hint)
                            else -> stringResource(R.string.accessibility_keepalive_disabled_hint)
                        },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = requested,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        requested = checked
                        AccessibilityKeepAliveManager.setRequested(context, checked)
                        lastError = null
                        if (!checked) {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { AccessibilityKeepAliveManager.disable(context) }
                                busy = false
                                refreshState()
                                result.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.accessibility_keepalive_disabled), Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    lastError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, context.getString(R.string.accessibility_keepalive_disable_failed, lastError), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )
            }

            Text(
                text = stringResource(R.string.accessibility_keepalive_body),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rootAvailable == true) {
                    Button(
                        enabled = requested && !busy,
                        onClick = {
                            busy = true
                            lastError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { AccessibilityKeepAliveManager.enableWithRoot(context) }
                                busy = false
                                refreshState()
                                result.onSuccess {
                                    Toast.makeText(context, context.getString(R.string.accessibility_keepalive_root_enabled), Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    lastError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, context.getString(R.string.accessibility_keepalive_root_enable_failed, lastError), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.accessibility_keepalive_root_enable))
                    }
                } else {
                    Button(
                        enabled = requested && !busy,
                        onClick = {
                            AccessibilityKeepAliveManager.openAccessibilitySettings(context)
                                .onFailure { error ->
                                    lastError = error.message ?: error.javaClass.simpleName
                                    Toast.makeText(context, context.getString(R.string.accessibility_keepalive_open_settings_failed, lastError), Toast.LENGTH_LONG).show()
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.accessibility_keepalive_open_settings))
                    }
                }

                TextButton(
                    enabled = !busy,
                    onClick = {
                        rootAvailable = null
                        scope.launch {
                            val root = withContext(Dispatchers.IO) { AccessibilityKeepAliveManager.isRootAvailable() }
                            rootAvailable = root
                            refreshState()
                        }
                    }
                ) {
                    Text(stringResource(R.string.accessibility_keepalive_refresh))
                }
            }

            Text(
                text = when (rootAvailable) {
                    true -> stringResource(R.string.accessibility_keepalive_root_available)
                    false -> stringResource(R.string.accessibility_keepalive_root_unavailable)
                    null -> stringResource(R.string.accessibility_keepalive_root_checking)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!lastError.isNullOrBlank()) {
                Text(
                    text = lastError.orEmpty(),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
