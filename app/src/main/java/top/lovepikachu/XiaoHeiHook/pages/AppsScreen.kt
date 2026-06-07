package top.lovepikachu.XiaoHeiHook.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication
import top.lovepikachu.XiaoHeiHook.data.AppControl
import top.lovepikachu.XiaoHeiHook.data.AppLogRepository
import top.lovepikachu.XiaoHeiHook.data.AppRepository
import top.lovepikachu.XiaoHeiHook.data.InstalledAppInfo
import top.lovepikachu.XiaoHeiHook.data.ScopeController
import top.lovepikachu.XiaoHeiHook.data.ScriptMetadata
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.ui.material.AppActionCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppDialog
import top.lovepikachu.XiaoHeiHook.ui.material.AppInfoCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppIconButton
import top.lovepikachu.XiaoHeiHook.ui.material.AppPageTitle
import top.lovepikachu.XiaoHeiHook.ui.material.AppSearchField
import top.lovepikachu.XiaoHeiHook.ui.material.AppSectionTitle

private const val TAG = "XiaoHeiHook-Apps"

private enum class AppsPane { LIST, DETAIL, LOG }

private enum class AppSortMode { NAME_ASC, NAME_DESC, PACKAGE_ASC }

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
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
    var showListOptionsSheet by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(AppSortMode.NAME_ASC) }
    var enabledAppsFirst by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }
    var enabledAppsRevision by remember { mutableStateOf(0) }
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

    val enabledPackages = remember(apps, enabledAppsRevision, XiaoHeiApplication.remotePreferences) {
        apps.asSequence()
            .filter { ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, it.packageName) }
            .map { it.packageName }
            .toSet()
    }

    val filteredApps = remember(apps, query, showSystemApps, sortMode, enabledAppsFirst, enabledAppsRevision) {
        val q = query.trim().lowercase()
        val sortComparator = when (sortMode) {
            AppSortMode.NAME_ASC -> Comparator<InstalledAppInfo> { left, right ->
                left.label.compareTo(right.label, ignoreCase = true)
            }
            AppSortMode.NAME_DESC -> Comparator<InstalledAppInfo> { left, right ->
                right.label.compareTo(left.label, ignoreCase = true)
            }
            AppSortMode.PACKAGE_ASC -> Comparator<InstalledAppInfo> { left, right ->
                left.packageName.compareTo(right.packageName, ignoreCase = true)
            }
        }
        val comparator = if (enabledAppsFirst) {
            Comparator<InstalledAppInfo> { left, right ->
                val leftEnabled = enabledPackages.contains(left.packageName)
                val rightEnabled = enabledPackages.contains(right.packageName)
                when {
                    leftEnabled != rightEnabled -> if (leftEnabled) -1 else 1
                    else -> sortComparator.compare(left, right)
                }
            }
        } else {
            sortComparator
        }

        apps.asSequence()
            .filter { showSystemApps || !it.isSystemApp }
            .filter { app ->
                q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)
            }
            .toList()
            .sortedWith(comparator)
    }

    val appListState = rememberLazyListState()
    val showBackToTop by remember {
        derivedStateOf {
            appListState.firstVisibleItemIndex > 3 || appListState.firstVisibleItemScrollOffset > 700
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

    if (showListOptionsSheet) {
        AppListOptionsSheet(
            sortMode = sortMode,
            enabledAppsFirst = enabledAppsFirst,
            showSystemApps = showSystemApps,
            moduleActive = moduleState.isActivated,
            onSortModeChange = { sortMode = it },
            onEnabledAppsFirstChange = { enabledAppsFirst = it },
            onShowSystemAppsChange = { showSystemApps = it },
            onRefreshApps = { reload(forceRefreshApps = true) },
            onSyncEnabledScripts = {
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
            onDismiss = { showListOptionsSheet = false }
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
                        modifier = modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        AppPageTitle(
                            title = "适配应用",
                            trailing = {
                                AppIconButton(
                                    icon = Icons.Filled.Menu,
                                    contentDescription = "应用操作",
                                    onClick = { showListOptionsSheet = true },
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        AppSearchField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "搜索应用"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f)) {
                                LazyColumn(
                                    state = appListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    contentPadding = PaddingValues(bottom = 10.dp)
                                ) {
                                    itemsIndexed(filteredApps, key = { index, app -> "${app.packageName}#$index" }) { _, app ->
                                        AppRow(
                                            app = app,
                                            moduleActive = moduleState.isActivated,
                                            onOpen = { selectedApp = app },
                                            onEnabledChanged = { enabledAppsRevision++ }
                                        )
                                    }
                                }

                                if (showBackToTop) {
                                    FloatingActionButton(
                                        onClick = { scope.launch { appListState.animateScrollToItem(0) } },
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                        contentColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 8.dp, bottom = 12.dp)
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "回到顶部")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppListOptionsSheet(
    sortMode: AppSortMode,
    enabledAppsFirst: Boolean,
    showSystemApps: Boolean,
    moduleActive: Boolean,
    onSortModeChange: (AppSortMode) -> Unit,
    onEnabledAppsFirstChange: (Boolean) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRefreshApps: () -> Unit,
    onSyncEnabledScripts: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 10.dp)
                    .size(width = 42.dp, height = 5.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                content = {}
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "排序",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OptionChipButton(
                    text = "按名称 A-Z",
                    selected = sortMode == AppSortMode.NAME_ASC,
                    modifier = Modifier.weight(1f),
                    onClick = { onSortModeChange(AppSortMode.NAME_ASC) }
                )
                OptionChipButton(
                    text = "按名称 Z-A",
                    selected = sortMode == AppSortMode.NAME_DESC,
                    modifier = Modifier.weight(1f),
                    onClick = { onSortModeChange(AppSortMode.NAME_DESC) }
                )
                OptionChipButton(
                    text = "按包名 A-Z",
                    selected = sortMode == AppSortMode.PACKAGE_ASC,
                    modifier = Modifier.weight(1f),
                    onClick = { onSortModeChange(AppSortMode.PACKAGE_ASC) }
                )
            }
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("已启用应用优先", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (enabledAppsFirst) "已启用的应用会显示在列表顶部" else "按当前排序规则直接排列",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabledAppsFirst, onCheckedChange = onEnabledAppsFirstChange)
                }
            }

            Text(
                text = "过滤器",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示系统应用", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (showSystemApps) "列表包含系统应用" else "仅显示用户安装应用",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
                }
            }

            Text(
                text = "脚本同步",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OptionActionButton(
                    text = "刷新应用",
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onRefreshApps()
                    }
                )
                OptionActionButton(
                    text = "同步脚本",
                    icon = Icons.Filled.Sync,
                    enabled = moduleActive,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onSyncEnabledScripts()
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionChipButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(46.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f) else Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OptionActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.55f else 0.24f)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
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
    val context = LocalContext.current
    var appEnabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var showScriptHelpDialog by remember { mutableStateOf(false) }

    LaunchedEffect(app.packageName, initialAutoScan) {
        if (initialAutoScan) {
            Log.d(TAG, "ScriptEnableScreen: first detail enter package=${app.packageName}, auto rescan public scripts once")
            onInitialAutoScanConsumed()
            onInitialRescanScripts()
        } else {
            Log.d(TAG, "ScriptEnableScreen: entered package=${app.packageName}, skip auto rescan; use menu to scan manually")
        }
    }

    if (showScriptHelpDialog) {
        AlertDialog(
            onDismissRequest = { showScriptHelpDialog = false },
            title = { Text("脚本保存位置") },
            text = { Text("脚本保存在 /Documents/XiaoHeiHook 下。\n\n单文件脚本放在根目录，例如 qidian.js；多文件脚本放在文件夹中，并使用 index.js 作为入口。") },
            confirmButton = {
                TextButton(onClick = { showScriptHelpDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            AppIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(42.dp)
            )
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

        Spacer(Modifier.height(12.dp))

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

        Spacer(Modifier.height(14.dp))

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "脚本开关",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            IconButton(
                onClick = { showScriptHelpDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.Info, contentDescription = "脚本保存位置")
            }
        }

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
                        enabled = moduleActive && appEnabled,
                        onOpenLocation = { openScriptLocation(context, it) }
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
    AppActionCard(
        title = title,
        icon = icon,
        modifier = modifier,
        enabled = enabled,
        onClick = onClick
    )
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
            val result = withContext(Dispatchers.IO) { AppLogRepository.readLog(context, app.packageName) }
            logText = result.getOrElse { error ->
                Log.e(TAG, "AppLogScreen: read failed package=${app.packageName}", error)
                error.message ?: error.toString()
            }
            loading = false
        }
    }

    fun clearLog() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppLogRepository.clearLog(context, app.packageName) }
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AppIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(42.dp)
            )
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
                AppIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新日志",
                    onClick = { reloadLog() },
                    modifier = Modifier.size(42.dp)
                )
                AppIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "清空日志",
                    onClick = { clearLog() },
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AppCard(
            modifier = Modifier.fillMaxSize()
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
                                text = logText.ifBlank { "暂无日志" },
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
private fun AppRow(
    app: InstalledAppInfo,
    moduleActive: Boolean,
    onOpen: () -> Unit,
    onEnabledChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var enabled by remember(app.packageName, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isAppEnabled(XiaoHeiApplication.remotePreferences, app.packageName))
    }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app = app, size = 50)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = app.label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = app.packageName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(app.packageName) {
                            detectTapGestures(
                                onTap = { onOpen() },
                                onLongPress = {
                                    clipboard.setText(AnnotatedString(app.packageName))
                                    Toast.makeText(context, "已复制包名：${app.packageName}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                )
                Text(
                    text = if (enabled) "已启用" else if (app.isSystemApp) "系统应用" else "用户应用",
                    fontSize = 12.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
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
                            onEnabledChanged()
                        },
                        onFailed = {
                            enabled = false
                            onEnabledChanged()
                        }
                    )
                }
            )
        }
    }
}

private const val SCRIPT_FILE_PROVIDER_AUTHORITY = "top.lovepikachu.XiaoHeiHook.scriptfileprovider"

private fun openScriptLocation(context: Context, script: ScriptMetadata) {
    val targetRelativePath = when {
        script.kind == "directory" && script.rootPath.isNotBlank() -> script.rootPath
        script.path.isNotBlank() -> script.path
        script.remoteName.startsWith("scripts/") -> script.remoteName.removePrefix("scripts/")
        else -> ""
    }.trim('/').replace('\\', '/')

    if (targetRelativePath.isBlank()) {
        Toast.makeText(context, "无法确定脚本文件位置", Toast.LENGTH_SHORT).show()
        return
    }

    val isDirectory = script.kind == "directory" || (script.rootPath.isNotBlank() && targetRelativePath == script.rootPath)

    if (isDirectory) {
        openScriptDirectory(context, targetRelativePath)
    } else {
        openScriptFile(context, targetRelativePath)
    }
}

private fun openScriptFile(context: Context, relativePath: String) {
    val file = resolveScriptFile(relativePath)
    if (!file.isFile) {
        Toast.makeText(context, "脚本文件不存在：$relativePath", Toast.LENGTH_SHORT).show()
        openScriptDirectory(context, relativePath.substringBeforeLast('/', ""))
        return
    }

    val uri = runCatching {
        FileProvider.getUriForFile(context, SCRIPT_FILE_PROVIDER_AUTHORITY, file)
    }.getOrElse { error ->
        Toast.makeText(context, error.message ?: "无法生成脚本文件 Uri", Toast.LENGTH_LONG).show()
        return
    }

    val mime = when {
        relativePath.endsWith(".js", ignoreCase = true) -> "text/javascript"
        relativePath.endsWith(".json", ignoreCase = true) -> "application/json"
        relativePath.endsWith(".md", ignoreCase = true) -> "text/markdown"
        relativePath.endsWith(".txt", ignoreCase = true) -> "text/plain"
        else -> "text/plain"
    }

    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    grantScriptFileUri(context, uri, viewIntent)

    runCatching {
        context.startActivity(Intent.createChooser(viewIntent, "打开脚本文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        // Fallback to system file picker near the script. This avoids showing a raw permission error.
        openScriptDirectory(context, relativePath.substringBeforeLast('/', ""))
    }
}

private fun openScriptDirectory(context: Context, relativePath: String) {
    val clean = relativePath.trim('/').replace('\\', '/')
    val docId = if (clean.isBlank()) {
        "primary:Documents/XiaoHeiHook"
    } else {
        "primary:Documents/XiaoHeiHook/$clean"
    }

    val initialUri = DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents",
        docId
    )

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    runCatching { context.startActivity(intent) }
        .onFailure { error ->
            Toast.makeText(context, error.message ?: "无法打开脚本目录", Toast.LENGTH_LONG).show()
        }
}

private fun resolveScriptFile(relativePath: String): File {
    val clean = relativePath.trim('/').replace('\\', '/')
    val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "XiaoHeiHook").canonicalFile
    val target = File(root, clean).canonicalFile
    require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
        "脚本路径越界"
    }
    return target
}

private fun grantScriptFileUri(context: Context, uri: Uri, intent: Intent) {
    val pm = context.packageManager
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    for (info in activities) {
        val packageName = info.activityInfo?.packageName ?: continue
        runCatching {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Composable
private fun ScriptRow(script: ScriptMetadata, packageName: String, enabled: Boolean, onOpenLocation: (ScriptMetadata) -> Unit) {
    var checked by remember(packageName, script.id, XiaoHeiApplication.remotePreferences) {
        mutableStateOf(ScopeController.isScriptEnabled(packageName, script.id))
    }

    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenLocation(script) }
                )
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
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        selected = checked
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}


@Composable
private fun AssistCard(title: String, text: String) {
    AppInfoCard(title = title, text = text)
}


@Composable
private fun AllFilesAccessDialog(
    path: String,
    onOpenSettings: () -> Unit,
    onUseRootFallback: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = "需要管理所有文件权限",
        text = "为了扫描 $path 下的 JS 脚本，需要先在系统设置中打开“允许管理所有文件”。返回本应用后会自动重新扫描；如果暂不授权，将尝试使用 root 兜底读取。",
        confirmText = "去授权",
        dismissText = "暂不授权",
        onConfirm = onOpenSettings,
        onDismissAction = onUseRootFallback,
        onDismiss = onDismiss
    )
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
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {}
    }
}
