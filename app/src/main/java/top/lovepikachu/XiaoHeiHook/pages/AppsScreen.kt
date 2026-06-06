package top.lovepikachu.XiaoHeiHook.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import top.lovepikachu.XiaoHeiHook.data.AppLogRepository
import top.lovepikachu.XiaoHeiHook.data.AppRepository
import top.lovepikachu.XiaoHeiHook.data.InstalledAppInfo
import top.lovepikachu.XiaoHeiHook.data.ScopeController
import top.lovepikachu.XiaoHeiHook.data.ScriptMetadata
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository

private const val TAG = "XiaoHeiHook-Apps"

private enum class AppsPane { LIST, DETAIL, LOG }

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppsScreen(
    modifier: Modifier = Modifier,
    onDetailVisibleChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val moduleState by XiaoHeiApplication.moduleState.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf(AppRepository.cachedInstalledApps().orEmpty()) }
    var scripts by remember { mutableStateOf<List<ScriptMetadata>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var logApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var loading by remember { mutableStateOf(apps.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var scanningScripts by remember { mutableStateOf(false) }
    var showAllFilesAccessDialog by remember { mutableStateOf(false) }
    var allFilesSettingsLaunched by remember { mutableStateOf(false) }
    var allowRootFallback by remember { mutableStateOf(!ScriptRepository.needsAllFilesAccess()) }
    var pendingPermissionRescanApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var pendingPermissionScanToast by remember { mutableStateOf(false) }
    val detailAutoScannedPackages = remember { mutableStateListOf<String>() }

    LaunchedEffect(selectedApp != null, logApp != null) {
        onDetailVisibleChange(selectedApp != null || logApp != null)
    }

    DisposableEffect(Unit) {
        onDispose { onDetailVisibleChange(false) }
    }

    BackHandler(enabled = selectedApp != null || logApp != null) {
        if (logApp != null) {
            logApp = null
        } else {
            selectedApp = null
        }
    }

    fun requestAllFilesAccessBeforeScan(app: InstalledAppInfo? = null, showToastAfterPermission: Boolean = false): Boolean {
        if (!ScriptRepository.needsAllFilesAccess() || allowRootFallback) return false
        pendingPermissionRescanApp = app
        pendingPermissionScanToast = showToastAfterPermission
        showAllFilesAccessDialog = true
        Log.d(TAG, "requestAllFilesAccessBeforeScan: show dialog before scan, package=${app?.packageName.orEmpty()}, showToastAfterPermission=$showToastAfterPermission")
        return true
    }

    fun launchAllFilesAccessSettings() {
        showAllFilesAccessDialog = false
        allFilesSettingsLaunched = true
        Log.d(TAG, "launchAllFilesAccessSettings: open settings for MANAGE_EXTERNAL_STORAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:${context.packageName}")
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = packageUri
            }
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching {
                context.startActivity(appIntent)
            }.onFailure { appPageError ->
                Log.w(TAG, "launchAllFilesAccessSettings: app page failed, fallback to all-files list", appPageError)
                runCatching {
                    context.startActivity(fallbackIntent)
                }.onFailure { fallbackError ->
                    Log.e(TAG, "launchAllFilesAccessSettings: fallback settings failed", fallbackError)
                    Toast.makeText(context, "无法打开管理所有文件权限页面，请手动到系统设置中授权", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun loadScripts(debugPackageName: String? = null, syncToRemote: Boolean = true): List<ScriptMetadata> = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadScripts: start, debugPackage=${debugPackageName.orEmpty()}, syncToRemote=$syncToRemote")
        ScriptRepository.ensurePublicFolderAndSample(allowRootFallback)
        val publicScripts = ScriptRepository.readPublicScripts(debugPackageName, allowRootFallback).map { it.first }
        Log.d(TAG, "loadScripts: public count=${publicScripts.size}, ids=${publicScripts.joinToString { it.id }}")
        val loaded = if (syncToRemote && XiaoHeiApplication.xposedService != null && XiaoHeiApplication.remotePreferences != null) {
            ScriptRepository.syncPublicScriptsToRemote(
                XiaoHeiApplication.xposedService,
                XiaoHeiApplication.remotePreferences,
                debugPackageName,
                allowRootFallback
            ).getOrElse { error ->
                Log.e(TAG, "loadScripts: sync failed, use public scripts", error)
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
            val loadedScripts = loadScripts()
            apps = loadedApps
            scripts = loadedScripts
            loading = false
        }
    }

    fun rescanScriptsForApp(app: InstalledAppInfo, syncToRemote: Boolean = false, showToast: Boolean = false) {
        if (requestAllFilesAccessBeforeScan(app, showToastAfterPermission = showToast)) return
        scope.launch {
            scanningScripts = true
            Log.d(TAG, "rescanScriptsForApp: package=${app.packageName}, syncToRemote=$syncToRemote")
            val loadedScripts = loadScripts(app.packageName, syncToRemote)
            scripts = loadedScripts
            val matchedCount = loadedScripts.count { it.supportsPackage(app.packageName) }
            Log.d(TAG, "rescanScriptsForApp: package=${app.packageName}, total=${loadedScripts.size}, matched=$matchedCount")
            if (showToast) {
                Toast.makeText(context, "已重新扫描：共 ${loadedScripts.size} 个，匹配 ${matchedCount} 个", Toast.LENGTH_SHORT).show()
            }
            scanningScripts = false
        }
    }

    fun syncScriptsForApp(app: InstalledAppInfo, restartAfterSync: Boolean = false) {
        if (requestAllFilesAccessBeforeScan(app, showToastAfterPermission = false)) return
        scope.launch {
            scanningScripts = true
            val result = withContext(Dispatchers.IO) {
                ScriptRepository.syncPublicScriptsToRemote(
                    XiaoHeiApplication.xposedService,
                    XiaoHeiApplication.remotePreferences,
                    app.packageName,
                    allowRootFallback
                ).mapCatching { synced ->
                    if (restartAfterSync) {
                        AppControl.forceStop(app.packageName).getOrThrow()
                    }
                    synced
                }
            }
            result.onSuccess { synced ->
                scripts = synced
                val matchedCount = synced.count { it.supportsPackage(app.packageName) }
                Log.d(TAG, "syncScriptsForApp: package=${app.packageName}, synced=${synced.size}, matched=$matchedCount, restart=$restartAfterSync")
                if (restartAfterSync) {
                    delay(500)
                    AppControl.launchPackage(context, app.packageName).onFailure { error ->
                        Toast.makeText(context, error.message ?: "启动失败", Toast.LENGTH_LONG).show()
                    }
                    Toast.makeText(context, "已同步并重启 ${app.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "已同步 ${synced.size} 个脚本，匹配 ${matchedCount} 个", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Log.e(TAG, "syncScriptsForApp: failed package=${app.packageName}", it)
                Toast.makeText(context, it.message ?: "同步失败", Toast.LENGTH_LONG).show()
            }
            scanningScripts = false
        }
    }

    fun forceStopApp(app: InstalledAppInfo) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppControl.forceStop(app.packageName) }
            Toast.makeText(context, result.fold({ "已强制终止 ${app.label}" }, { it.message ?: "强制终止失败" }), Toast.LENGTH_SHORT).show()
        }
    }

    fun launchApp(app: InstalledAppInfo) {
        AppControl.launchPackage(context, app.packageName).onFailure {
            Toast.makeText(context, it.message ?: "启动失败", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (ScriptRepository.needsAllFilesAccess() && !allowRootFallback) {
            showAllFilesAccessDialog = true
        }
        if (apps.isEmpty()) {
            reload(forceRefreshApps = false)
        } else {
            scripts = loadScripts()
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
                val pendingApp = pendingPermissionRescanApp
                val pendingShowToast = pendingPermissionScanToast
                pendingPermissionRescanApp = null
                pendingPermissionScanToast = false
                if (pendingApp != null) {
                    rescanScriptsForApp(pendingApp, syncToRemote = false, showToast = pendingShowToast)
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

    if (showAllFilesAccessDialog) {
        AllFilesAccessDialog(
            path = ScriptRepository.publicScriptsDir.absolutePath,
            onOpenSettings = { launchAllFilesAccessSettings() },
            onUseRootFallback = {
                showAllFilesAccessDialog = false
                allowRootFallback = true
                Log.d(TAG, "AllFilesAccessDialog: user skipped settings, enable root fallback")
                val pendingApp = pendingPermissionRescanApp
                val pendingShowToast = pendingPermissionScanToast
                pendingPermissionRescanApp = null
                pendingPermissionScanToast = false
                if (pendingApp != null) {
                    rescanScriptsForApp(pendingApp, syncToRemote = false, showToast = pendingShowToast)
                } else {
                    reload(forceRefreshApps = false)
                }
            },
            onDismiss = { showAllFilesAccessDialog = false }
        )
    }

    val pane = when {
        logApp != null -> AppsPane.LOG
        selectedApp != null -> AppsPane.DETAIL
        else -> AppsPane.LIST
    }

    AnimatedContent(
        targetState = pane,
        label = "AppsPaneTransition",
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                initialOffsetX = { width -> if (forward) width else -width }
            ) + fadeIn(animationSpec = tween(durationMillis = 160))
            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                targetOffsetX = { width -> if (forward) -width / 3 else width }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
            enter.togetherWith(exit)
        },
        modifier = Modifier.fillMaxSize()
    ) { currentPane ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentPane) {
                AppsPane.LOG -> {
                    val app = logApp
                    if (app != null) {
                        AppLogScreen(
                            app = app,
                            onBack = { logApp = null },
                            modifier = modifier
                        )
                    }
                }

                AppsPane.DETAIL -> {
                    val app = selectedApp
                    if (app != null) {
                        val matchedScripts = scripts.filter { it.supportsPackage(app.packageName) }
                        Log.d(TAG, "ScriptEnableScreen: filter package=${app.packageName}, total=${scripts.size}, matched=${matchedScripts.size}, ids=${matchedScripts.joinToString { it.id }}")
                        ScriptEnableScreen(
                            app = app,
                            scripts = matchedScripts,
                            scanningScripts = scanningScripts,
                            moduleActive = moduleState.isActivated,
                            initialAutoScan = !detailAutoScannedPackages.contains(app.packageName),
                            onInitialAutoScanConsumed = { detailAutoScannedPackages.add(app.packageName) },
                            onInitialRescanScripts = { rescanScriptsForApp(app, syncToRemote = false, showToast = false) },
                            onBack = { selectedApp = null },
                            onRescanScripts = { rescanScriptsForApp(app, syncToRemote = false, showToast = true) },
                            onSyncScripts = { syncScriptsForApp(app, restartAfterSync = false) },
                            onSyncAndRestart = { syncScriptsForApp(app, restartAfterSync = true) },
                            onOpenApp = { launchApp(app) },
                            onForceStop = { forceStopApp(app) },
                            onOpenSystemSettings = {
                                AppControl.openSystemSettings(context, app.packageName).onFailure {
                                    Toast.makeText(context, it.message ?: "无法打开系统设置", Toast.LENGTH_LONG).show()
                                }
                            },
                            onOpenTerminal = { logApp = app },
                            modifier = modifier
                        )
                    }
                }

                AppsPane.LIST -> {
                    Column(
                        modifier = modifier.fillMaxSize()
                    ) {
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
                                enabled = moduleState.isActivated,
                                onClick = {
                                    if (!requestAllFilesAccessBeforeScan(showToastAfterPermission = false)) {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                ScriptRepository.syncEnabledAppsScriptsToRemote(
                                                    XiaoHeiApplication.xposedService,
                                                    XiaoHeiApplication.remotePreferences,
                                                    allowRootFallback = allowRootFallback
                                                )
                                            }
                                            result.onSuccess { synced ->
                                                scripts = synced
                                                Toast.makeText(context, "已同步 ${synced.size} 个脚本", Toast.LENGTH_SHORT).show()
                                            }.onFailure {
                                                Toast.makeText(context, it.message ?: "同步失败", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(46.dp)
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = "同步脚本", modifier = Modifier.size(20.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                itemsIndexed(filteredApps, key = { index, app -> "${app.packageName}#$index" }) { _, app ->
                                    AppRow(
                                        app = app,
                                        moduleActive = moduleState.isActivated,
                                        onOpen = { selectedApp = app }
                                    )
                                }
                            }
                        }
                    }
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
    moduleActive: Boolean,
    initialAutoScan: Boolean,
    onInitialAutoScanConsumed: () -> Unit,
    onInitialRescanScripts: () -> Unit,
    onBack: () -> Unit,
    onRescanScripts: () -> Unit,
    onSyncScripts: () -> Unit,
    onSyncAndRestart: () -> Unit,
    onOpenApp: () -> Unit,
    onForceStop: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var appEnabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(app.packageName, initialAutoScan) {
        if (initialAutoScan) {
            Log.d(TAG, "ScriptEnableScreen: first detail enter package=${app.packageName}, auto rescan public scripts once")
            onInitialAutoScanConsumed()
            onInitialRescanScripts()
        } else {
            Log.d(TAG, "ScriptEnableScreen: entered package=${app.packageName}, skip auto rescan; use menu to scan manually")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(modifier = Modifier.width(4.dp))
            AppIcon(
                app = app,
                size = 44,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onOpenSystemSettings
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ScrollableSingleLineText(
                    text = app.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(3.dp))
                CopyablePackageNameText(
                    packageName = app.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailActionCard(
                title = "同步重启",
                icon = Icons.Filled.Refresh,
                enabled = moduleActive && appEnabled && !scanningScripts,
                modifier = Modifier.weight(1f),
                onClick = onSyncAndRestart
            )
            DetailActionCard(
                title = "终端",
                icon = Icons.Filled.Terminal,
                modifier = Modifier.weight(1f),
                onClick = onOpenTerminal
            )
            Box(modifier = Modifier.weight(1f)) {
                DetailActionCard(
                    title = "更多",
                    icon = Icons.Filled.MoreVert,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (scanningScripts) "扫描中" else "重新扫描脚本") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        enabled = !scanningScripts,
                        onClick = { menuExpanded = false; onRescanScripts() }
                    )
                    DropdownMenuItem(
                        text = { Text("同步脚本") },
                        leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                        enabled = moduleActive && appEnabled && !scanningScripts,
                        onClick = { menuExpanded = false; onSyncScripts() }
                    )
                    DropdownMenuItem(
                        text = { Text("同步脚本并重启") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = moduleActive && appEnabled && !scanningScripts,
                        onClick = { menuExpanded = false; onSyncAndRestart() }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("打开程序") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpenApp() }
                    )
                    DropdownMenuItem(
                        text = { Text("强制终止程序") },
                        leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                        onClick = { menuExpanded = false; onForceStop() }
                    )
                    DropdownMenuItem(
                        text = { Text("系统应用设置") },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = { menuExpanded = false; onOpenSystemSettings() }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

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

        Text(
            text = "脚本开关",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )

        if (scripts.isEmpty()) {
            AssistCard(
                title = "没有匹配脚本",
                text = "未找到匹配脚本。请确认脚本头部使用 @target ${app.packageName} 或 @target *；需要重新读取公共目录时，请从右上角“更多”菜单手动扫描。"
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                itemsIndexed(scripts, key = { index, script -> "${script.id}#$index" }) { _, script ->
                    ScriptRow(
                        script = script,
                        packageName = app.packageName,
                        enabled = moduleActive && appEnabled
                    )
                }
            }
        }
    }
}


@Composable
private fun DetailActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(72.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = if (enabled) 1.dp else 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}



@Composable
private fun AppLogScreen(
    app: InstalledAppInfo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var logText by remember(app.packageName) { mutableStateOf("") }
    var loading by remember(app.packageName) { mutableStateOf(false) }

    fun reloadLog() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { AppLogRepository.readLog(app.packageName) }
            logText = result.getOrElse { error ->
                Log.e(TAG, "AppLogScreen: read failed package=${app.packageName}", error)
                error.message ?: error.toString()
            }
            loading = false
        }
    }

    fun clearLog() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppLogRepository.clearLog(app.packageName) }
            result.onSuccess {
                Toast.makeText(context, "已清空日志", Toast.LENGTH_SHORT).show()
                reloadLog()
            }.onFailure {
                Toast.makeText(context, it.message ?: "清空日志失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(app.packageName) {
        reloadLog()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("终端日志", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                ScrollableSingleLineText(
                    text = app.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                CopyablePackageNameText(
                    packageName = app.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { reloadLog() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新日志")
                }
                IconButton(onClick = { clearLog() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        AssistCard(
            title = "日志文件",
            text = "${AppLogRepository.logPath(app.packageName)}\nJS 的 console.log / xposed.log 会写入这里。管理端通过 root 读取该文件。"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    item {
                        SelectionContainer {
                            Text(
                                text = logText.ifBlank { "暂无日志。" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledAppInfo, moduleActive: Boolean, onOpen: () -> Unit) {
    var enabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app = app, size = 42)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                CopyablePackageNameText(
                    packageName = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    scrollable = false,
                    modifier = Modifier.fillMaxWidth()
                )
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
    }
}

@Composable
private fun ScriptRow(script: ScriptMetadata, packageName: String, enabled: Boolean) {
    var checked by remember(packageName, script.id, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isScriptEnabled(packageName, script.id))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(script.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${script.id} · ${script.version} · ${script.runAt}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (script.url.isNotBlank()) {
                    Text(
                        text = "URL：${script.url} · ${if (script.urlRefreshOnApply) "应用时拉取" else "使用本地正文"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (script.description.isNotBlank()) {
                    Text(
                        text = script.description,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = {
                    checked = it
                    ScopeController.setScriptEnabled(packageName, script.id, it)
                }
            )
        }
    }
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
        shape = RoundedCornerShape(12.dp),
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AssistCard(title: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                text = "为了扫描 $path 下的 JS 脚本，需要先在系统设置中打开“允许管理所有文件”。返回本应用后会自动重新扫描；如果暂不授权，将尝试使用 root 兜底读取。",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyablePackageNameText(
    packageName: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    scrollable: Boolean = true
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    fun copyPackageName() {
        clipboard.setText(AnnotatedString(packageName))
        Toast.makeText(context, "已复制包名：$packageName", Toast.LENGTH_SHORT).show()
    }

    if (scrollable) {
        val clickableModifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { copyPackageName() }
        )
        ScrollableSingleLineText(
            text = packageName,
            color = color,
            fontSize = fontSize,
            modifier = clickableModifier
        )
    } else {
        Text(
            text = packageName,
            color = color,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.pointerInput(packageName) {
                detectTapGestures(
                    onTap = {},
                    onLongPress = { copyPackageName() }
                )
            }
        )
    }
}

@Composable
private fun ScrollableSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight? = null
) {
    val scrollState = rememberScrollState()
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
        }
    }
}


@Composable
private fun AppIcon(app: InstalledAppInfo, size: Int, modifier: Modifier = Modifier) {
    if (app.icon != null) {
        val imageBitmap = remember(app.icon) { app.icon.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = modifier.size(size.dp)
        )
    } else {
        Surface(
            modifier = modifier.size(size.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.LightGray.copy(alpha = 0.3f)
        ) {}
    }
}
