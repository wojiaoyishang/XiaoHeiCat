package top.lovepikachu.XiaoHeiHook.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.lovepikachu.XiaoHeiHook.BuildConfig
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard

@Composable
fun ActivationStatusCard() {
    val moduleState by XiaoHeiApplication.moduleState.collectAsStateWithLifecycle()
    val isActive = moduleState.isActivated
    val statusColor = if (isActive) Color(0xFF3F8F5F) else MaterialTheme.colorScheme.error

    Box(modifier = Modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
            selected = isActive,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isActive) 0.54f else 0.32f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isActive) stringResource(R.string.module_active) else stringResource(R.string.module_not_active),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isActive) {
                            val framework = moduleState.frameworkName ?: stringResource(R.string.unknown)
                            val version = moduleState.frameworkVersion ?: BuildConfig.VERSION_NAME
                            stringResource(R.string.activation_status, framework, version, moduleState.frameworkAPIVersion ?: 0)
                        } else {
                            stringResource(R.string.module_enable_in_lsposed)
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
