package top.lovepikachu.XiaoHeiHook.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.AppControl
import top.lovepikachu.XiaoHeiHook.data.AppRepository
import top.lovepikachu.XiaoHeiHook.data.InstalledAppInfo
import top.lovepikachu.XiaoHeiHook.data.ScopeController
import top.lovepikachu.XiaoHeiHook.data.ScriptMetadata
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository

private const val TAG = "XiaoHeiHook-Apps"

@Composable
fun AppsScreen(
    modifier: Modifier = Modifier,
    onDetailModeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val moduleState by XiaoHeiApplication.moduleState.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf(AppRepository.cachedInstalledApps().orEmpty()) }
    var scripts by remember { mutableStateOf<List<ScriptMetadata>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var loading by remember { mutableStateOf(apps.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var scanningScripts by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var showAllFilesAccessDialog by remember { mutableStateOf(false) }
    var allFilesSettingsLaunched by remember { mutableStateOf(false) }
    var allowRootFallback by remember { mutableStateOf(!ScriptRepository.needsAllFilesAccess()) }
    var pendingPermissionRescanApp by remember { mutableStateOf<InstalledAppInfo?>(null) }

    fun toast(message: String, long: Boolean = false) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun requestAllFilesAccessBeforeScan(app: InstalledAppInfo? = null): Boolean {
        if (!ScriptRepository.needsAllFilesAccess() || allowRootFallback) return false
        pendingPermissionRescanApp = app
        showAllFilesAccessDialog = true
        Log.d(TAG, "requestAllFilesAccessBeforeScan: show dialog before scan, package=${app?.packageName.orEmpty()}")
        return true
    }

    fun launchAllFilesAccessSettings() {
        showAllFilesAccessDialog = false
        allFilesSettingsLaunched = true
        Log.d(TAG, "launchAllFilesAccessSettings: open settings for MANAGE_EXTERNAL_STORAGE")
        openAllFilesAccessSettings(context)
    }

    suspend fun loadScripts(debugPackageName: String? = null, syncToRemote: Boolean = true): List<ScriptMetadata> = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadScripts: start, debugPackage=${debugPackageName.orEmpty()}, syncToRemote=$syncToRemote")
        ScriptRepository.ensurePublicFolderAndSample(allowRootFallback)
        val publicScripts = ScriptRepository.readPublicScripts(debugPackageName, allowRootFallback).map { it.first }
        Log.d(TAG, "loadScripts: public count=${publicScripts.size}, ids=${publicScripts.joinToString { it.id }}")
        val loaded = if (syncToRemote && XiaoHeiApplication.xposedService != null && XiaoHeiApplication.remotePreferences != null) {
            ScriptRepository.syncEnabledAppsScriptsToRemote(
                XiaoHeiApplication.xposedService,
                XiaoHeiApplication.remotePreferences,
                allowRootFallback
            ).getOrElse { error ->
                Log.e(TAG, "loadScripts: sync enabled-app scripts failed, use public scripts", error)
                publicScripts
            }
        } else {
            publicScripts
        }
        debugPackageName?.let { packageName ->
            val matched = loaded.filter { it.supportsPackage(packageName) }
            Log.d(TAG, "loadScripts: matched package=$packageName, count=${matched.size}, ids=${matched.joinToString { it.id }}")
        }
        loaded
    }

    fun reload(forceRefreshApps: Boolean = false) {
        scope.launch {
            loading = apps.isEmpty() || forceRefreshApps
            val loadedApps = withContext(Dispatchers.IO) {
                AppRepository.loadInstalledApps(context, forceRefresh = forceRefreshApps)
            }
            val loadedScripts = loadScripts(syncToRemote = false)
            apps = loadedApps
            scripts = loadedScripts
            loading = false
        }
    }

    fun rescanScriptsForApp(app: InstalledAppInfo, syncToRemote: Boolean = false, showToast: Boolean = false) {
        if (requestAllFilesAccessBeforeScan(app)) return
        scope.launch {
            scanningScripts = true
            Log.d(TAG, "rescanScriptsForApp: package=${app.packageName}, syncToRemote=$syncToRemote")
            val loadedScripts = loadScripts(app.packageName, syncToRemote)
            scripts = loadedScripts
            val matchedCount = loadedScripts.count { it.supportsPackage(app.packageName) }
            Log.d(TAG, "rescanScriptsForApp: package=${app.packageName}, total=${loadedScripts.size}, matched=$matchedCount")
            if (showToast) toast("已重新扫描：共 ${loadedScripts.size} 个，匹配 ${matchedCount} 个")
            scanningScripts = false
        }
    }

    fun syncScriptsForApp(app: InstalledAppInfo, scriptId: String? = null, restartAfter: Boolean = false) {
        if (requestAllFilesAccessBeforeScan(app)) return
        scope.launch {
            scanningScripts = true
            busyMessage = when {
                restartAfter -> "同步并重启中…"
                scriptId != null -> "同步单个脚本…"
                else -> "同步脚本中…"
            }
            val result = withContext(Dispatchers.IO) {
                if (scriptId != null) {
                    ScriptRepository.syncSingleScriptToRemote(
                        XiaoHeiApplication.xposedService,
                        XiaoHeiApplication.remotePreferences,
                        scriptId,
                        app.packageName,
                        allowRootFallback
                    )
                } else {
                    ScriptRepository.syncPublicScriptsToRemote(
                        XiaoHeiApplication.xposedService,
                        XiaoHeiApplication.remotePreferences,
                        app.packageName,
                        allowRootFallback
                    )
                }
            }
            result.onSuccess { synced ->
                scripts = loadScripts(app.packageName, syncToRemote = false)
                val matchedCount = synced.count { it.supportsPackage(app.packageName) }
                Log.d(TAG, "syncScriptsForApp: package=${app.packageName}, scriptId=${scriptId.orEmpty()}, index=${synced.size}, matched=$matchedCount, restartAfter=$restartAfter")
                if (restartAfter) {
                    val restart = withContext(Dispatchers.IO) { AppControl.restartApp(context, app.packageName) }
                    restart.onSuccess { toast("已同步并重启 ${app.label}") }
                        .onFailure { toast(it.message ?: "重启失败", long = true) }
                } else {
                    toast(if (scriptId == null) "已同步该应用脚本" else "已同步单个脚本")
                }
            }.onFailure {
                Log.e(TAG, "syncScriptsForApp: failed package=${app.packageName}", it)
                toast(it.message ?: "同步失败", long = true)
            }
            busyMessage = null
            scanningScripts = false
        }
    }

    fun forceStopApp(app: InstalledAppInfo) {
        scope.launch {
            busyMessage = "正在强制终止…"
            val result = withContext(Dispatchers.IO) { AppControl.forceStop(app.packageName) }
            result.onSuccess { toast("已强制终止 ${app.label}") }
                .onFailure { toast(it.message ?: "强制终止失败", long = true) }
            busyMessage = null
        }
    }

    fun openApp(app: InstalledAppInfo) {
        AppControl.openApp(context, app.packageName)
            .onFailure { toast(it.message ?: "打开失败", long = true) }
    }

    fun openAppSettings(app: InstalledAppInfo) {
        AppControl.openAppSettings(context, app.packageName)
            .onFailure { toast("无法打开系统应用信息", long = true) }
    }

    fun addUrlScript(url: String, app: InstalledAppInfo) {
        if (requestAllFilesAccessBeforeScan(app)) return
        scope.launch {
            scanningScripts = true
            val result = withContext(Dispatchers.IO) {
                ScriptRepository.createUrlScriptPointer(url, app.packageName, allowRootFallback)
            }
            result.onSuccess {
                toast("已添加 URL 脚本指针")
                val loadedScripts = loadScripts(app.packageName, syncToRemote = false)
                scripts = loadedScripts
            }.onFailure {
                toast(it.message ?: "添加 URL 脚本失败", long = true)
            }
            scanningScripts = false
        }
    }

    LaunchedEffect(Unit) {
        if (ScriptRepository.needsAllFilesAccess() && !allowRootFallback) {
            showAllFilesAccessDialog = true
        }
        if (apps.isEmpty()) {
            reload(forceRefreshApps = false)
        } else {
            scripts = loadScripts(syncToRemote = false)
            loading = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && allFilesSettingsLaunched) {
                allFilesSettingsLaunched = false
                val granted = ScriptRepository.hasAllFilesAccess()
                allowRootFallback = !granted
                Log.d(TAG, "all-files settings returned: granted=$granted, allowRootFallback=$allowRootFallback")
                toast(if (granted) "已获得管理所有文件权限，正在重新扫描" else "未获得管理所有文件权限，将尝试 root 兜底扫描")
                val pendingApp = pendingPermissionRescanApp
                pendingPermissionRescanApp = null
                if (pendingApp != null) {
                    rescanScriptsForApp(pendingApp, syncToRemote = false, showToast = true)
                } else {
                    reload(forceRefreshApps = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredApps = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    LaunchedEffect(selectedApp != null) {
        onDetailModeChange(selectedApp != null)
    }

    DisposableEffect(Unit) {
        onDispose { onDetailModeChange(false) }
    }

    if (showAllFilesAccessDialog) {
        AllFilesAccessDialog(
            path = ScriptRepository.publicScriptsDir.absolutePath,
            onOpenSettings = { launchAllFilesAccessSettings() },
            onUseRootFallback = {
                showAllFilesAccessDialog = false
                allowRootFallback = true
                Log.d(TAG, "AllFilesAccessDialog: user skipped settings, enable root fallback")
                val pendingApp = pendingPermissionRescanApp
                pendingPermissionRescanApp = null
                if (pendingApp != null) {
                    rescanScriptsForApp(pendingApp, syncToRemote = false, showToast = true)
                } else {
                    reload(forceRefreshApps = false)
                }
            },
            onDismiss = { showAllFilesAccessDialog = false }
        )
    }

    selectedApp?.let { app ->
        val matchedScripts = remember(scripts, app.packageName) {
            val matched = scripts.filter { it.supportsPackage(app.packageName) }
            Log.d(TAG, "ScriptEnableScreen: filter package=${app.packageName}, total=${scripts.size}, matched=${matched.size}, ids=${matched.joinToString { it.id }}")
            matched
        }
        ScriptEnableScreen(
            app = app,
            scripts = matchedScripts,
            scanningScripts = scanningScripts,
            busyMessage = busyMessage,
            moduleActive = moduleState.isActivated,
            allowRootFallback = allowRootFallback,
            onBack = { selectedApp = null },
            onRescanScripts = { rescanScriptsForApp(app, syncToRemote = false, showToast = true) },
            onSyncScripts = { syncScriptsForApp(app) },
            onSyncAndRestart = { syncScriptsForApp(app, restartAfter = true) },
            onForceStop = { forceStopApp(app) },
            onOpenApp = { openApp(app) },
            onOpenSettings = { openAppSettings(app) },
            onSyncSingleScript = { script -> syncScriptsForApp(app, scriptId = script.id) },
            onAddUrlScript = { url -> addUrlScript(url, app) },
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        AssistCard(
            title = "公共脚本目录",
            text = "Documents/XiaoHeiHook。支持 .js 本地脚本和 .url 远程脚本指针；主页同步只会复制已启用应用匹配的脚本。长按应用图标可打开系统应用信息。"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompactSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f)
            )

            FilledTonalIconButton(
                onClick = { reload(forceRefreshApps = true) },
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
            }

            FilledTonalIconButton(
                enabled = moduleState.isActivated && !scanningScripts,
                onClick = {
                    if (!requestAllFilesAccessBeforeScan()) {
                        scope.launch {
                            scanningScripts = true
                            val result = withContext(Dispatchers.IO) {
                                ScriptRepository.syncEnabledAppsScriptsToRemote(
                                    XiaoHeiApplication.xposedService,
                                    XiaoHeiApplication.remotePreferences,
                                    allowRootFallback
                                )
                            }
                            result.onSuccess { synced ->
                                scripts = loadScripts(syncToRemote = false)
                                toast("已同步启用应用的 ${synced.size} 个脚本")
                            }.onFailure {
                                toast(it.message ?: "同步失败", long = true)
                            }
                            scanningScripts = false
                        }
                    }
                },
                modifier = Modifier.size(46.dp)
            ) {
                Icon(Icons.Filled.Sync, contentDescription = "同步启用应用脚本", modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 112.dp)
            ) {
                itemsIndexed(filteredApps, key = { index, app -> "${app.packageName}#$index" }) { _, app ->
                    AppRow(
                        app = app,
                        moduleActive = moduleState.isActivated,
                        onOpen = { selectedApp = app },
                        onOpenApp = { openApp(app) },
                        onForceStop = { forceStopApp(app) },
                        onSyncAndRestart = { syncScriptsForApp(app, restartAfter = true) },
                        onOpenSettings = { openAppSettings(app) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptEnableScreen(
    app: InstalledAppInfo,
    scripts: List<ScriptMetadata>,
    scanningScripts: Boolean,
    busyMessage: String?,
    moduleActive: Boolean,
    allowRootFallback: Boolean,
    onBack: () -> Unit,
    onRescanScripts: () -> Unit,
    onSyncScripts: () -> Unit,
    onSyncAndRestart: () -> Unit,
    onForceStop: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSettings: () -> Unit,
    onSyncSingleScript: (ScriptMetadata) -> Unit,
    onAddUrlScript: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var appEnabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }
    var hotReload by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isHotReloadEnabled(app.packageName))
    }
    var showUrlDialog by remember { mutableStateOf(false) }
    val adbEnabled = remember { AppControl.isAdbEnabled(context) }

    LaunchedEffect(app.packageName) {
        Log.d(TAG, "ScriptEnableScreen: entered package=${app.packageName}, rescan public scripts")
        onRescanScripts()
    }

    HotReloadEffect(
        enabled = hotReload && appEnabled && moduleActive,
        app = app,
        allowRootFallback = allowRootFallback,
        onChanged = onSyncAndRestart
    )

    if (showUrlDialog) {
        UrlScriptDialog(
            onConfirm = { url ->
                showUrlDialog = false
                onAddUrlScript(url)
            },
            onDismiss = { showUrlDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Box(
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onOpenSettings
                )
            ) {
                AppIcon(app = app, size = 40)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        SettingSwitchCard(
            title = "应用总开关",
            subtitle = if (appEnabled) "已申请作用域，允许加载该应用脚本" else "关闭后会移除该应用 LSPosed 作用域",
            checked = appEnabled,
            enabled = moduleActive,
            onCheckedChange = { enabled ->
                appEnabled = enabled
                ScopeController.setAppEnabled(
                    packageName = app.packageName,
                    enabled = enabled,
                    onApproved = {
                        appEnabled = ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName)
                    },
                    onFailed = {
                        appEnabled = false
                    }
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        SettingSwitchCard(
            title = "热重载模式",
            subtitle = if (adbEnabled) {
                "监控本地脚本和 URL 脚本变化，变化后自动同步并重启目标应用"
            } else {
                "ADB 未开启；仍可手动打开，但建议只在调试环境使用"
            },
            checked = hotReload,
            enabled = moduleActive && appEnabled,
            onCheckedChange = {
                hotReload = it
                ScopeController.setHotReloadEnabled(app.packageName, it)
            }
        )

        Spacer(Modifier.height(10.dp))

        DetailActionPanel(
            scanningScripts = scanningScripts,
            moduleActive = moduleActive,
            onRescanScripts = onRescanScripts,
            onSyncScripts = onSyncScripts,
            onOpenApp = onOpenApp,
            onForceStop = onForceStop,
            onSyncAndRestart = onSyncAndRestart,
            onAddUrlScript = { showUrlDialog = true }
        )

        if (busyMessage != null) {
            Text(
                text = busyMessage,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Text(
            text = "脚本开关 · 匹配 ${scripts.size} 个",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )

        if (scripts.isEmpty()) {
            AssistCard(
                title = "没有匹配脚本",
                text = "点击进入本页会重新扫描 Documents/XiaoHeiHook 及其子目录。URL 模式会读取 .url 文件并下载远程 JS。请确认脚本头部使用 @target ${app.packageName} 或 @target *。"
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                scripts.forEachIndexed { index, script ->
                    ScriptRow(
                        script = script,
                        packageName = app.packageName,
                        enabled = moduleActive && appEnabled,
                        onSync = { onSyncSingleScript(script) }
                    )
                    if (index != scripts.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DetailActionPanel(
    scanningScripts: Boolean,
    moduleActive: Boolean,
    onRescanScripts: () -> Unit,
    onSyncScripts: () -> Unit,
    onOpenApp: () -> Unit,
    onForceStop: () -> Unit,
    onSyncAndRestart: () -> Unit,
    onAddUrlScript: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionPill(
                text = if (scanningScripts) "扫描中" else "扫描",
                icon = Icons.Filled.Refresh,
                onClick = onRescanScripts,
                enabled = !scanningScripts,
                modifier = Modifier.weight(1f)
            )
            ActionPill(
                text = "同步",
                icon = Icons.Filled.Sync,
                onClick = onSyncScripts,
                enabled = moduleActive && !scanningScripts,
                modifier = Modifier.weight(1f),
                primary = true
            )
            ActionPill(
                text = "URL",
                icon = Icons.Filled.Link,
                onClick = onAddUrlScript,
                enabled = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionPill(
                text = "打开",
                icon = Icons.Filled.PlayArrow,
                onClick = onOpenApp,
                enabled = true,
                modifier = Modifier.weight(1f)
            )
            ActionPill(
                text = "强停",
                icon = Icons.Filled.Stop,
                onClick = onForceStop,
                enabled = true,
                modifier = Modifier.weight(1f)
            )
            ActionPill(
                text = "同步重启",
                icon = Icons.Filled.Sync,
                onClick = onSyncAndRestart,
                enabled = moduleActive && !scanningScripts,
                modifier = Modifier.weight(1.25f),
                primary = true
            )
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        tonalElevation = if (primary) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HotReloadEffect(
    enabled: Boolean,
    app: InstalledAppInfo,
    allowRootFallback: Boolean,
    onChanged: () -> Unit
) {
    LaunchedEffect(enabled, app.packageName, allowRootFallback) {
        if (!enabled) return@LaunchedEffect
        var lastFingerprint = withContext(Dispatchers.IO) {
            ScriptRepository.packageScriptFingerprint(app.packageName, allowRootFallback)
        }
        Log.d(TAG, "HotReload: start package=${app.packageName}, fingerprint=$lastFingerprint")
        while (true) {
            delay(1800)
            val current = withContext(Dispatchers.IO) {
                ScriptRepository.packageScriptFingerprint(app.packageName, allowRootFallback)
            }
            if (current != lastFingerprint) {
                Log.d(TAG, "HotReload: changed package=${app.packageName}, old=$lastFingerprint, new=$current")
                lastFingerprint = current
                onChanged()
            }
        }
    }

    DisposableEffect(enabled, app.packageName) {
        if (!enabled) {
            onDispose { }
        } else {
            val observer = object : FileObserver(
                ScriptRepository.publicScriptsDir.absolutePath,
                FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or FileObserver.MOVE_SELF
            ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (!path.endsWith(".js", ignoreCase = true) && !path.endsWith(".url", ignoreCase = true)) return
                Log.d(TAG, "HotReload(FileObserver): event=$event path=$path package=${app.packageName}")
                onChanged()
            }
            }
            observer.startWatching()
            Log.d(TAG, "HotReload(FileObserver): watching ${ScriptRepository.publicScriptsDir.absolutePath}")
            onDispose {
                observer.stopWatching()
                Log.d(TAG, "HotReload(FileObserver): stop watching ${ScriptRepository.publicScriptsDir.absolutePath}")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledAppInfo,
    moduleActive: Boolean,
    onOpen: () -> Unit,
    onOpenApp: () -> Unit,
    onForceStop: () -> Unit,
    onSyncAndRestart: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var enabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.combinedClickable(
                        onClick = onOpen,
                        onLongClick = onOpenSettings
                    )
                ) {
                    AppIcon(app = app, size = 42)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = enabled,
                    enabled = moduleActive,
                    onCheckedChange = { checked ->
                        enabled = checked
                        ScopeController.setAppEnabled(
                            packageName = app.packageName,
                            enabled = checked,
                            onApproved = {
                                enabled = ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName)
                            },
                            onFailed = { enabled = false }
                        )
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ActionPill(
                    text = "打开",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onOpenApp,
                    enabled = true,
                    modifier = Modifier.weight(1f)
                )
                ActionPill(
                    text = "强停",
                    icon = Icons.Filled.Stop,
                    onClick = onForceStop,
                    enabled = true,
                    modifier = Modifier.weight(1f)
                )
                ActionPill(
                    text = "同步重启",
                    icon = Icons.Filled.Sync,
                    onClick = onSyncAndRestart,
                    enabled = moduleActive,
                    modifier = Modifier.weight(1.32f),
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun ScriptRow(
    script: ScriptMetadata,
    packageName: String,
    enabled: Boolean,
    onSync: () -> Unit
) {
    var checked by remember(packageName, script.id, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isScriptEnabled(packageName, script.id))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = script.id,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onSync,
                enabled = enabled,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Sync, contentDescription = "同步脚本", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = {
                    checked = it
                    ScopeController.setScriptEnabled(packageName, script.id, it)
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (checked) "已启用" else "未启用",
                fontSize = 12.sp,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${script.version} · ${script.runAt} · ${script.sourceMode}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (script.url.isNotBlank()) {
            Text(
                text = script.url,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (script.description.isNotBlank()) {
            Text(
                text = script.description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UrlScriptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 URL 脚本") },
        text = {
            Column {
                Text(
                    text = "填写电脑或远程终端暴露的 JS 地址，例如 http://192.168.1.100:8080/qidian.js。同步时会下载脚本正文并写入 LSPosed Remote Files。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("脚本 URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.trim().startsWith("http://") || value.trim().startsWith("https://"),
                onClick = { onConfirm(value.trim()) }
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SettingSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AssistCard(title: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AllFilesAccessDialog(
    path: String,
    onOpenSettings: () -> Unit,
    onUseRootFallback: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要管理所有文件权限") },
        text = {
            Text(
                text = "为了扫描 $path 下的 JS 和 URL 脚本，需要先在系统设置中打开“允许管理所有文件”。返回本应用后会自动重新扫描；如果暂不授权，将尝试使用 root 兜底读取。",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("去授权") }
        },
        dismissButton = {
            TextButton(onClick = onUseRootFallback) { Text("暂不授权") }
        }
    )
}

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val packageUri = Uri.parse("package:${context.packageName}")
    val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = packageUri
    }
    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    runCatching {
        context.startActivity(appIntent)
    }.onFailure { appPageError ->
        Log.w(TAG, "openAllFilesAccessSettings: app page failed, fallback to all-files list", appPageError)
        runCatching {
            context.startActivity(fallbackIntent)
        }.onFailure { fallbackError ->
            Log.e(TAG, "openAllFilesAccessSettings: fallback settings failed", fallbackError)
            Toast.makeText(context, "无法打开管理所有文件权限页面，请手动到系统设置中授权", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isBlank()) {
                    Text(
                        text = "搜索应用",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AppIcon(app: InstalledAppInfo, size: Int) {
    if (app.icon != null) {
        val imageBitmap = remember(app.icon) { app.icon.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(size.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.LightGray.copy(alpha = 0.3f)
        ) {}
    }
}
