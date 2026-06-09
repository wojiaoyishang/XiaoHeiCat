package top.lovepikachu.XiaoHeiHook.composables

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.io.File

@Composable
fun ScriptRootSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moduleState by XiaoHeiApplication.moduleState.collectAsState()
    val defaultPath = remember { ScriptRepository.defaultPublicScriptsDir().absolutePath }
    var currentPath by remember { mutableStateOf(ScriptRepository.publicScriptsDir.absolutePath) }
    var editPath by remember { mutableStateOf(currentPath) }
    var showEditDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun reloadPathFromPrefs() {
        val prefs = XiaoHeiApplication.remotePreferences
        ScriptRepository.applyScriptRootFromPrefs(prefs)
        currentPath = ScriptRepository.scriptRootFromPrefs(prefs).absolutePath
    }

    LaunchedEffect(moduleState.isActivated) {
        reloadPathFromPrefs()
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showEditDialog = false },
            title = { Text(stringResource(R.string.script_root_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.script_root_edit_message),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editPath,
                        onValueChange = { editPath = it },
                        enabled = !saving,
                        singleLine = true,
                        label = { Text(stringResource(R.string.script_root_path_label)) },
                        placeholder = { Text(defaultPath) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !saving,
                    onClick = {
                        val raw = editPath.trim()
                        if (raw.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.script_root_empty), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            saving = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val prefs = XiaoHeiApplication.remotePreferences
                                        ?: throw IllegalStateException(context.getString(R.string.script_root_remote_prefs_disconnected))
                                    val input = File(raw)
                                    if (!input.isAbsolute) {
                                        throw IllegalArgumentException(context.getString(R.string.script_root_must_be_absolute))
                                    }
                                    val dir = input.absoluteFile
                                    if (dir.exists() && !dir.isDirectory) {
                                        throw IllegalArgumentException(context.getString(R.string.script_root_not_directory, dir.absolutePath))
                                    }
                                    val saved = ScriptRepository.setScriptRoot(prefs, dir.absolutePath)
                                    ScriptRepository.ensurePublicFolderAndSample(allowRootFallback = true).getOrThrow()
                                    saved.absolutePath
                                }
                            }
                            saving = false
                            result.onSuccess { path ->
                                currentPath = path
                                editPath = path
                                showEditDialog = false
                                Toast.makeText(context, context.getString(R.string.script_root_saved), Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.script_root_save_failed, error.message ?: error.javaClass.simpleName),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(if (saving) stringResource(R.string.common_saving) else stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showEditDialog = false }) {
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
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.script_root_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentPath,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = if (moduleState.isActivated) {
                    stringResource(R.string.script_root_hint)
                } else {
                    stringResource(R.string.script_root_inactive_hint)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (moduleState.isActivated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = moduleState.isActivated && !saving,
                    onClick = {
                        editPath = currentPath
                        showEditDialog = true
                    }
                ) {
                    Text(stringResource(R.string.script_root_change))
                }
                TextButton(
                    enabled = moduleState.isActivated && !saving && currentPath != defaultPath,
                    onClick = {
                        scope.launch {
                            saving = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val prefs = XiaoHeiApplication.remotePreferences
                                        ?: throw IllegalStateException(context.getString(R.string.script_root_remote_prefs_disconnected))
                                    val saved = ScriptRepository.setScriptRoot(prefs, defaultPath)
                                    ScriptRepository.ensurePublicFolderAndSample(allowRootFallback = true).getOrThrow()
                                    saved.absolutePath
                                }
                            }
                            saving = false
                            result.onSuccess { path ->
                                currentPath = path
                                editPath = path
                                Toast.makeText(context, context.getString(R.string.script_root_reset_done), Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.script_root_save_failed, error.message ?: error.javaClass.simpleName),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.script_root_reset_default))
                }
            }
        }
    }
}
