package top.lovepikachu.XiaoHeiHook.composables

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard

@Composable
fun RootScriptCacheSyncSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moduleState by XiaoHeiApplication.moduleState.collectAsState()
    var rootAvailable by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading = true
            val prefs = XiaoHeiApplication.remotePreferences
            val root = withContext(Dispatchers.IO) { ScriptRepository.isRootAvailable() }
            rootAvailable = root
            enabled = ScriptRepository.isRootScriptCacheSyncEnabled(prefs, root)
            loading = false
        }
    }

    LaunchedEffect(moduleState.isActivated) {
        reload()
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = stringResource(R.string.root_script_cache_sync_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (rootAvailable) {
                        stringResource(R.string.root_script_cache_sync_body)
                    } else {
                        stringResource(R.string.root_script_cache_sync_no_root)
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled && rootAvailable,
                enabled = moduleState.isActivated && rootAvailable && !loading,
                onCheckedChange = { value ->
                    val prefs = XiaoHeiApplication.remotePreferences
                    ScriptRepository.setRootScriptCacheSyncEnabled(prefs, value)
                    enabled = value
                    Toast.makeText(
                        context,
                        if (value) context.getString(R.string.root_script_cache_sync_enabled) else context.getString(R.string.root_script_cache_sync_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}
