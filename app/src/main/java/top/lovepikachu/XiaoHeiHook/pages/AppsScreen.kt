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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import top.lovepikachu.XiaoHeiHook.data.ScriptPrefs
import top.lovepikachu.XiaoHeiHook.data.ScriptSettings
import org.json.JSONArray
import org.json.JSONObject
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
            if (selectedApp == null) onDetailVisibleChange(false)
        } else {
            selectedApp = null
            onDetailVisibleChange(false)
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
                targetOffsetX = { width -> if (forward) -width else width }
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
                            onBack = {
                                logApp = null
                                if (selectedApp == null) onDetailVisibleChange(false)
                            },
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
                            onBack = {
                                selectedApp = null
                                onDetailVisibleChange(false)
                            },
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
                            onOpenTerminal = {
                                onDetailVisibleChange(true)
                                logApp = app
                            },
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
                            title = "应用配置",
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
                                            onOpen = {
                                                onDetailVisibleChange(true)
                                                selectedApp = app
                                            },
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
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
    var settingsScript by remember { mutableStateOf<ScriptMetadata?>(null) }

    LaunchedEffect(app.packageName, initialAutoScan) {
        if (initialAutoScan) {
            Log.d(TAG, "ScriptEnableScreen: first detail enter package=${app.packageName}, auto rescan public scripts once")
            onInitialAutoScanConsumed()
            onInitialRescanScripts()
        } else {
            Log.d(TAG, "ScriptEnableScreen: entered package=${app.packageName}, skip auto rescan; use menu to scan manually")
        }
    }

    AnimatedContent(
        targetState = settingsScript,
        label = "ScriptSettingsPaneTransition",
        transitionSpec = {
            val openingSettings = targetState != null
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                initialOffsetX = { width -> if (openingSettings) width else -width }
            ) + fadeIn(animationSpec = tween(durationMillis = 140))
            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                targetOffsetX = { width -> if (openingSettings) -width else width }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
            enter.togetherWith(exit)
        },
        modifier = modifier.fillMaxSize()
    ) { scriptForSettings ->
        if (scriptForSettings != null) {
            ScriptSettingsVisualScreen(
                packageName = app.packageName,
                script = scriptForSettings,
                onBack = { settingsScript = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {

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
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                        onOpenLocation = { openScriptLocation(context, it) },
                        onOpenSettings = { settingsScript = it }
                    )
                }
            }
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
    var logFontSize by remember(app.packageName) { mutableStateOf(12f) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

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
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompactLogIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(21.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = "终端日志",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${app.label} · ${app.packageName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactLogTextButton(text = "A-", contentDescription = "缩小字体") {
                    logFontSize = (logFontSize - 1f).coerceAtLeast(8f)
                }
                CompactLogTextButton(text = "A+", contentDescription = "放大字体") {
                    logFontSize = (logFontSize + 1f).coerceAtMost(28f)
                }
                CompactLogIconButton(
                    icon = Icons.Filled.OpenInNew,
                    contentDescription = "用其他应用打开日志",
                    onClick = { openLogFileWithExternalEditor(context, app.packageName) }
                )
                CompactLogIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新日志",
                    onClick = { reloadLog() }
                )
                CompactLogIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "清空日志",
                    onClick = { clearLog() }
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val lines = remember(logText) { logText.ifBlank { "暂无日志" }.lines() }
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(app.packageName) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    logFontSize = (logFontSize * zoom).coerceIn(8f, 28f)
                                }
                            }
                            .horizontalScroll(horizontalScroll)
                            .verticalScroll(verticalScroll)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = logFontSize.sp,
                                lineHeight = (logFontSize + 5f).sp,
                                color = colorForLogLine(line),
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLogIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompactLogTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(34.dp)
            .height(32.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun colorForLogLine(line: String): Color {
    val upper = line.uppercase()
    return when {
        " E/" in line || "ERROR" in upper || "错误" in line || "Exception" in line || "Throwable" in line -> MaterialTheme.colorScheme.error
        " W/" in line || "WARN" in upper || "警告" in line -> MaterialTheme.colorScheme.tertiary
        " I/" in line || "INFO" in upper || "信息" in line -> MaterialTheme.colorScheme.primary
        " D/" in line || "DEBUG" in upper -> MaterialTheme.colorScheme.onSurfaceVariant
        " V/" in line || "VERBOSE" in upper -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurface
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
        // 多文件脚本也直接打开入口文件，不再打开目录。
        // v15+ 目录脚本的入口应是 folder/index.js。
        script.entryPath.isNotBlank() -> script.entryPath
        script.path.isNotBlank() -> script.path
        script.kind == "directory" && script.rootPath.isNotBlank() -> "${script.rootPath.trim('/')}/index.js"
        script.remoteName.startsWith("scripts/") -> script.remoteName.removePrefix("scripts/")
        else -> ""
    }.trim('/').replace('\\', '/')

    if (targetRelativePath.isBlank()) {
        Toast.makeText(context, "无法确定脚本文件位置", Toast.LENGTH_SHORT).show()
        return
    }

    openScriptFile(context, targetRelativePath)
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

private fun openLogFileWithExternalEditor(context: Context, packageName: String) {
    val file = AppLogRepository.moduleLogFile(context, packageName)
    runCatching {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("")
    }.onFailure { error ->
        Toast.makeText(context, error.message ?: "无法准备日志文件", Toast.LENGTH_LONG).show()
        return
    }

    val uri = runCatching {
        FileProvider.getUriForFile(context, SCRIPT_FILE_PROVIDER_AUTHORITY, file)
    }.getOrElse { error ->
        Toast.makeText(context, error.message ?: "无法生成日志文件 Uri", Toast.LENGTH_LONG).show()
        return
    }

    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    grantScriptFileUri(context, uri, viewIntent)

    runCatching {
        context.startActivity(Intent.createChooser(viewIntent, "打开日志文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure { error ->
        Toast.makeText(context, error.message ?: "没有可用的文件编辑器", Toast.LENGTH_LONG).show()
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
private fun ScriptRow(
    script: ScriptMetadata,
    packageName: String,
    enabled: Boolean,
    onOpenLocation: (ScriptMetadata) -> Unit,
    onOpenSettings: (ScriptMetadata) -> Unit
) {
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
            if (script.hasSettings) {
                IconButton(
                    onClick = { onOpenSettings(script) },
                    enabled = enabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "脚本设置")
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
private fun ScriptSettingsVisualScreen(
    packageName: String,
    script: ScriptMetadata,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val schema = remember(script.settingsSchema) { ScriptSettings.normalizeSchema(script.settingsSchema) }
    val key = remember(packageName, script.id) { ScriptPrefs.scriptSettingsKey(packageName, script.id) }
    val initialValues = remember(packageName, script.id, script.settingsSchema, XiaoHeiApplication.remotePreferences) {
        val raw = XiaoHeiApplication.remotePreferences?.getString(key, "{}") ?: "{}"
        ScriptSettings.merge(schema, raw)
    }
    var values by remember(packageName, script.id) { mutableStateOf(initialValues.deepCopyObject()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    BackHandler(onBack = onBack)

    fun save() {
        runCatching {
            val clean = ScriptSettings.normalizeValues(schema, values, strict = true)
            val doc = ScriptSettings.savedDocument(packageName, script.id, script.path, schema, clean)
            val prefs = XiaoHeiApplication.remotePreferences ?: throw IllegalStateException("LSPosed Remote Preferences 未连接")
            prefs.edit().putString(key, doc.toString()).commit()
            Toast.makeText(context, "已保存脚本设置", Toast.LENGTH_SHORT).show()
            onBack()
        }.onFailure { e ->
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun resetToDefault() {
        XiaoHeiApplication.remotePreferences?.edit()?.remove(key)?.commit()
        values = ScriptSettings.defaults(schema).deepCopyObject()
        error = null
        Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schema?.optString("title", "脚本设置") ?: "脚本设置",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${script.name} · $packageName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { showResetConfirm = true }) { Text("恢复默认") }
            Button(onClick = { save() }) { Text("保存") }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("恢复默认设置？") },
                text = { Text("这会清除当前应用中该脚本已保存的设置，并恢复 settings.json 中声明的默认值。") },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        resetToDefault()
                    }) { Text("恢复默认") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
                }
            )
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        if (schema == null) {
            AppInfoCard(title = "无法读取设置", text = "settings.json 为空或格式不正确。")
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val fields = schema.optJSONArray("fields") ?: JSONArray()
                ScriptSettingsFields(
                    fields = fields,
                    values = values,
                    onValuesChange = { values = it.deepCopyObject(); error = null }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ScriptSettingsFields(
    fields: JSONArray,
    values: JSONObject,
    onValuesChange: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
    depth: Int = 0
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            ScriptSettingsField(
                field = field,
                values = values,
                onValuesChange = onValuesChange,
                depth = depth
            )
        }
    }
}

@Composable
private fun ScriptSettingsField(
    field: JSONObject,
    values: JSONObject,
    onValuesChange: (JSONObject) -> Unit,
    depth: Int = 0
) {
    when (field.optString("type")) {
        "heading" -> SettingsHeading(field)
        "info" -> SettingsInfo(field)
        "group" -> SettingsGroup(field, values, onValuesChange, depth)
        "switch" -> SettingsSwitch(field, values, onValuesChange)
        "checkbox" -> SettingsCheckbox(field, values, onValuesChange)
        "number" -> SettingsNumber(field, values, onValuesChange)
        "text" -> SettingsText(field, values, onValuesChange)
        "select" -> SettingsSelect(field, values, onValuesChange)
        "radio" -> SettingsRadio(field, values, onValuesChange)
        "tags" -> SettingsTags(field, values, onValuesChange)
        "custom" -> SettingsCustom(field, values, onValuesChange)
        "list" -> SettingsList(field, values, onValuesChange, depth)
    }
}

@Composable
private fun SettingsHeading(field: JSONObject) {
    val label = field.optString("label", field.optString("title", "")).trim()
    Column(modifier = Modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = label.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }
        Divider(modifier = Modifier.padding(top = if (label.isNotBlank()) 8.dp else 0.dp))
    }
}

@Composable
private fun SettingsInfo(field: JSONObject) {
    val tone = field.optString("tone", "info")
    val title = field.optString("title", field.optString("label", "")).trim()
    val message = field.optString("message", "").trim()
    if (message.isBlank()) return
    val color = when (tone) {
        "warning" -> MaterialTheme.colorScheme.tertiaryContainer
        "success" -> MaterialTheme.colorScheme.primaryContainer
        "error" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tone) {
        "error" -> MaterialTheme.colorScheme.onErrorContainer
        "warning" -> MaterialTheme.colorScheme.onTertiaryContainer
        "success" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = color, contentColor = contentColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title.isNotBlank()) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(message, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    field: JSONObject,
    values: JSONObject,
    onValuesChange: (JSONObject) -> Unit,
    depth: Int
) {
    val label = field.optString("label", "分组")
    val items = field.optJSONArray("items") ?: JSONArray()
    var collapsed by remember(field.optString("key", label)) { mutableStateOf(field.optBoolean("defaultCollapsed", false)) }
    val radioKeys = remember(items.toString()) {
        val keys = linkedSetOf<String>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type") == "radio") keys.add(item.optString("key"))
        }
        keys.filter { it.isNotBlank() }
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                if (field.optBoolean("collapsible", false)) {
                    TextButton(onClick = { collapsed = !collapsed }) { Text(if (collapsed) "展开" else "折叠") }
                }
            }
            if (!collapsed) {
                radioKeys.forEach { radioKey ->
                    SettingsRadioGroup(radioKey, items, values, onValuesChange)
                }
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("type") == "radio") continue
                    ScriptSettingsField(item, values, onValuesChange, depth + 1)
                }
            }
        }
    }
}

@Composable
private fun SettingsRadioGroup(
    radioKey: String,
    items: JSONArray,
    values: JSONObject,
    onValuesChange: (JSONObject) -> Unit
) {
    val radios = remember(items.toString(), radioKey) {
        buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") == "radio" && item.optString("key") == radioKey) add(item)
            }
        }
    }
    if (radios.isEmpty()) return
    val current = values.opt(radioKey) ?: radios.first().opt("value")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        radios.firstOrNull()?.optString("name", "")?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        radios.forEach { radio ->
            val optionValue = radio.opt("value")
            val selected = jsonString(current) == jsonString(optionValue)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = values.deepCopyObject().putJson(radioKey, optionValue)
                        onValuesChange(next)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = {
                        val next = values.deepCopyObject().putJson(radioKey, optionValue)
                        onValuesChange(next)
                    }
                )
                Column {
                    Text(radio.optString("label", jsonString(optionValue)), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitch(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val checked = values.optBoolean(key, false)
    AppCard(modifier = Modifier.fillMaxWidth(), selected = checked) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsLabel(field, Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = { onValuesChange(values.deepCopyObject().put(key, it)) }
            )
        }
    }
}

@Composable
private fun SettingsCheckbox(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val checked = values.optBoolean(key, false)
    AppCard(modifier = Modifier.fillMaxWidth(), selected = checked) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onValuesChange(values.deepCopyObject().put(key, !checked)) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onValuesChange(values.deepCopyObject().put(key, it)) })
            Spacer(Modifier.width(10.dp))
            SettingsLabel(field, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingsNumber(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val integer = field.optBoolean("integer", false)
    val min = field.optNullableDouble("min")
    val max = field.optNullableDouble("max")
    val step = field.optDouble("step", if (integer) 1.0 else 0.1)
    val current = values.optNullableDouble(key) ?: 0.0
    var text by remember(key, jsonString(values.opt(key))) { mutableStateOf(if (integer) current.toInt().toString() else trimNumber(current)) }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(field)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw
                        raw.toDoubleOrNull()?.let { parsed ->
                            val normalized = normalizeNumberForUi(parsed, min, max, integer)
                            onValuesChange(values.deepCopyObject().put(key, normalized))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    label = { Text("数值") }
                )
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        val normalized = normalizeNumberForUi(current - step, min, max, integer)
                        onValuesChange(values.deepCopyObject().put(key, normalized))
                    }) { Text("-") }
                    OutlinedButton(onClick = {
                        val normalized = normalizeNumberForUi(current + step, min, max, integer)
                        onValuesChange(values.deepCopyObject().put(key, normalized))
                    }) { Text("+") }
                }
            }
            if (min != null && max != null && max > min) {
                Slider(
                    value = current.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                    onValueChange = { v ->
                        val raw = v.toDouble()
                        val snapped = if (step > 0) min + kotlin.math.round((raw - min) / step) * step else raw
                        onValuesChange(values.deepCopyObject().put(key, normalizeNumberForUi(snapped, min, max, integer)))
                    },
                    valueRange = min.toFloat()..max.toFloat()
                )
                Text("范围：${trimNumber(min)} - ${trimNumber(max)}，步进：${trimNumber(step)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsText(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val multiline = field.optBoolean("multiline", false)
    val masked = field.optBoolean("masked", false)
    val maxLength = field.optInt("maxLength", 2000).coerceAtLeast(1)
    val current = values.optString(key, "")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(field)
            OutlinedTextField(
                value = current,
                onValueChange = { raw ->
                    onValuesChange(values.deepCopyObject().put(key, raw.take(maxLength)))
                },
                singleLine = !multiline,
                minLines = if (multiline) 4 else 1,
                maxLines = if (multiline) 10 else 1,
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                placeholder = { field.optString("placeholder", "").takeIf { it.isNotBlank() }?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${current.length}/$maxLength", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSelect(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val options = field.optJSONArray("options") ?: JSONArray()
    val current = values.opt(key)
    var expanded by remember(key) { mutableStateOf(false) }
    val currentLabel = optionLabel(options, current)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(field)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(currentLabel.ifBlank { "请选择" }, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (i in 0 until options.length()) {
                        val option = options.optJSONObject(i) ?: continue
                        DropdownMenuItem(
                            text = { Text(option.optString("label", jsonString(option.opt("value")))) },
                            onClick = {
                                expanded = false
                                onValuesChange(values.deepCopyObject().putJson(key, option.opt("value")))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRadio(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val optionValue = field.opt("value")
    val selected = jsonString(values.opt(key)) == jsonString(optionValue)
    AppCard(modifier = Modifier.fillMaxWidth(), selected = selected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onValuesChange(values.deepCopyObject().putJson(key, optionValue)) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = { onValuesChange(values.deepCopyObject().putJson(key, optionValue)) })
            Spacer(Modifier.width(10.dp))
            SettingsLabel(field, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingsTags(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val arr = values.optJSONArray(key) ?: JSONArray()
    val maxItems = field.optInt("maxItems", 128).coerceAtLeast(0)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(field)
            for (i in 0 until arr.length()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = arr.optString(i, ""),
                        onValueChange = { raw ->
                            val nextArr = arr.deepCopyArray()
                            nextArr.put(i, raw)
                            onValuesChange(values.deepCopyObject().put(key, nextArr))
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("标签 ${i + 1}") }
                    )
                    IconButton(onClick = {
                        val nextArr = JSONArray()
                        for (j in 0 until arr.length()) if (j != i) nextArr.put(arr.opt(j))
                        onValuesChange(values.deepCopyObject().put(key, nextArr))
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            }
            OutlinedButton(
                onClick = {
                    val nextArr = arr.deepCopyArray().put("")
                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                },
                enabled = arr.length() < maxItems
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加标签")
            }
        }
    }
}

@Composable
private fun SettingsCustom(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit) {
    val key = field.optString("key")
    val obj = values.optJSONObject(key) ?: JSONObject()
    val pairs = remember(obj.toString()) { obj.toPairs() }
    val maxItems = field.optInt("maxItems", 128).coerceAtLeast(0)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsLabel(field)
            pairs.forEachIndexed { index, pair ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pair.first,
                        onValueChange = { newKey ->
                            val nextObj = JSONObject()
                            pairs.forEachIndexed { j, p ->
                                val k = if (j == index) newKey else p.first
                                if (k.isNotBlank()) nextObj.put(k, p.second)
                            }
                            onValuesChange(values.deepCopyObject().put(key, nextObj))
                        },
                        modifier = Modifier.weight(0.42f),
                        singleLine = true,
                        label = { Text(field.optString("keyPlaceholder", "key")) }
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = pair.second,
                        onValueChange = { newValue ->
                            val nextObj = JSONObject()
                            pairs.forEachIndexed { j, p ->
                                if (p.first.isNotBlank()) nextObj.put(p.first, if (j == index) newValue else p.second)
                            }
                            onValuesChange(values.deepCopyObject().put(key, nextObj))
                        },
                        modifier = Modifier.weight(0.58f),
                        singleLine = true,
                        label = { Text(field.optString("valuePlaceholder", "value")) }
                    )
                    IconButton(onClick = {
                        val nextObj = JSONObject()
                        pairs.forEachIndexed { j, p -> if (j != index && p.first.isNotBlank()) nextObj.put(p.first, p.second) }
                        onValuesChange(values.deepCopyObject().put(key, nextObj))
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            }
            OutlinedButton(
                onClick = {
                    val nextObj = obj.deepCopyObject()
                    var n = pairs.size + 1
                    var newKey = "key$n"
                    while (nextObj.has(newKey)) {
                        n++
                        newKey = "key$n"
                    }
                    nextObj.put(newKey, "")
                    onValuesChange(values.deepCopyObject().put(key, nextObj))
                },
                enabled = pairs.size < maxItems
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加键值对")
            }
        }
    }
}

@Composable
private fun SettingsList(field: JSONObject, values: JSONObject, onValuesChange: (JSONObject) -> Unit, depth: Int) {
    val key = field.optString("key")
    val arr = values.optJSONArray(key) ?: JSONArray()
    val items = field.optJSONArray("items") ?: JSONArray()
    val maxItems = field.optInt("maxItems", 64).coerceAtLeast(0)
    val uniqueKey = field.optString("uniqueKey", "")
    val expanded = remember(key) { mutableStateMapOf<Int, Boolean>() }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsLabel(field, Modifier.weight(1f))
                Text("${arr.length()}/$maxItems", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (uniqueKey.isNotBlank()) {
                Text("唯一键：$uniqueKey", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: JSONObject()
                val title = item.optString(uniqueKey, "").ifBlank { "第 ${i + 1} 项" }
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(
                                onClick = {
                                    val nextArr = JSONArray()
                                    for (j in 0 until arr.length()) if (j != i) nextArr.put(arr.opt(j))
                                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                            IconButton(
                                onClick = { expanded[i] = !(expanded[i] ?: true) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(if (expanded[i] == false) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = "展开/折叠")
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(onClick = {
                                val nextArr = arr.deepCopyArray()
                                if (i > 0) {
                                    val prev = nextArr.opt(i - 1)
                                    nextArr.put(i - 1, nextArr.opt(i))
                                    nextArr.put(i, prev)
                                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                                }
                            }, enabled = i > 0) { Text("上移") }
                            OutlinedButton(onClick = {
                                val nextArr = arr.deepCopyArray()
                                if (i < arr.length() - 1) {
                                    val next = nextArr.opt(i + 1)
                                    nextArr.put(i + 1, nextArr.opt(i))
                                    nextArr.put(i, next)
                                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                                }
                            }, enabled = i < arr.length() - 1) { Text("下移") }
                            OutlinedButton(onClick = {
                                if (arr.length() < maxItems) {
                                    val nextArr = JSONArray()
                                    for (j in 0 until arr.length()) {
                                        nextArr.put(arr.opt(j))
                                        if (j == i) nextArr.put(item.deepCopyObject())
                                    }
                                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                                }
                            }, enabled = arr.length() < maxItems) { Text("复制") }
                        }
                        if (expanded[i] != false) {
                            ScriptSettingsFields(
                                fields = items,
                                values = item,
                                onValuesChange = { newItem ->
                                    val nextArr = arr.deepCopyArray()
                                    nextArr.put(i, newItem)
                                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                                },
                                depth = depth + 1
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val nextArr = arr.deepCopyArray()
                    nextArr.put(defaultListItem(items))
                    onValuesChange(values.deepCopyObject().put(key, nextArr))
                },
                enabled = arr.length() < maxItems
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加列表项")
            }
        }
    }
}

@Composable
private fun SettingsLabel(field: JSONObject, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = field.optString("label", field.optString("key", "设置项")),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun defaultListItem(fields: JSONArray): JSONObject {
    val out = JSONObject()
    for (i in 0 until fields.length()) {
        val field = fields.optJSONObject(i) ?: continue
        if (field.optString("type") == "group") {
            val nested = defaultListItem(field.optJSONArray("items") ?: JSONArray())
            nested.keys().forEachRemainingCompat { key -> out.put(key, nested.opt(key)) }
            continue
        }
        val key = field.optString("key", "")
        if (key.isBlank() || out.has(key) && !field.has("default")) continue
        out.putJson(key, defaultValueForVisualField(field))
    }
    return out
}

private fun defaultValueForVisualField(field: JSONObject): Any? {
    if (field.has("default")) return field.opt("default").deepCopyJsonValue()
    return when (field.optString("type")) {
        "switch", "checkbox" -> false
        "number" -> if (field.optBoolean("integer", false)) 0 else 0.0
        "text" -> ""
        "select" -> field.optJSONArray("options")?.optJSONObject(0)?.opt("value") ?: ""
        "radio" -> field.opt("value") ?: false
        "tags" -> JSONArray()
        "custom" -> JSONObject()
        "list" -> JSONArray()
        else -> JSONObject.NULL
    }
}

private fun JSONObject.deepCopyObject(): JSONObject = JSONObject(this.toString())
private fun JSONArray.deepCopyArray(): JSONArray = JSONArray(this.toString())
private fun Any?.deepCopyJsonValue(): Any? = when (this) {
    is JSONObject -> this.deepCopyObject()
    is JSONArray -> this.deepCopyArray()
    else -> this
}

private fun JSONObject.putJson(key: String, value: Any?): JSONObject {
    put(key, value.deepCopyJsonValue())
    return this
}

private fun JSONObject.toPairs(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out.add(key to jsonString(opt(key)))
    }
    return out
}

private fun Iterator<String>.forEachRemainingCompat(block: (String) -> Unit) {
    while (hasNext()) block(next())
}

private fun jsonString(value: Any?): String = if (value == null || value == JSONObject.NULL) "" else value.toString()

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun normalizeNumberForUi(value: Double, min: Double?, max: Double?, integer: Boolean): Any {
    var out = value
    if (min != null) out = out.coerceAtLeast(min)
    if (max != null) out = out.coerceAtMost(max)
    return if (integer) out.toInt() else out
}

private fun trimNumber(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}

private fun optionLabel(options: JSONArray, current: Any?): String {
    val target = jsonString(current)
    for (i in 0 until options.length()) {
        val option = options.optJSONObject(i) ?: continue
        if (jsonString(option.opt("value")) == target) return option.optString("label", target)
    }
    return target
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
