package top.lovepikachu.XiaoHeiHook.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip          // ← 新增这行 import
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication

@Composable
fun ActivationStatusCard() {

    val isActive = XiaoHeiApplication.isModuleActivated;

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = {}),

        shape = RoundedCornerShape(12.dp),
        color = if (isActive)
            Color(0xFF4CAF50).copy(alpha = 0.08f)
        else
            Color(0xFFFF5252).copy(alpha = 0.08f),
        tonalElevation = 1.dp

    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (isActive) Color(0xFF4CAF50) else Color(0xFFFF5252),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column {
                if (!isActive) {
                    Text(
                        text = stringResource(R.string.module_not_active),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = stringResource(R.string.module_active),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.activation_status,
                            XiaoHeiApplication.frameworkName ?: "Unknown",
                            XiaoHeiApplication.frameworkVersion ?: "Unknown",
                            XiaoHeiApplication.frameworkAPIVersion ?: 0
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}