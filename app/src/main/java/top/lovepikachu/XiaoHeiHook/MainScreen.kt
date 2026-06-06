package top.lovepikachu.XiaoHeiHook

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.lovepikachu.XiaoHeiHook.pages.AboutScreen
import top.lovepikachu.XiaoHeiHook.pages.AppsScreen
import top.lovepikachu.XiaoHeiHook.pages.HomeScreen

sealed class BottomNavScreen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    abstract val index: Int

    data object Home : BottomNavScreen("首页", Icons.Filled.Home) { override val index: Int = 0 }
    data object Apps : BottomNavScreen("应用", Icons.Filled.Apps) { override val index: Int = 1 }
    data object About : BottomNavScreen("关于", Icons.Filled.Info) { override val index: Int = 2 }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val screens = remember { listOf(BottomNavScreen.Home, BottomNavScreen.Apps, BottomNavScreen.About) }
    val pagerState = rememberPagerState(initialPage = BottomNavScreen.Home.index) { screens.size }
    val scope = rememberCoroutineScope()
    val navEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    val isAppsDetailVisible = remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(BottomNavScreen.Home.index) }
    var lastBackPressedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedIndex = page.coerceIn(0, screens.lastIndex)
        }
    }

    LaunchedEffect(isAppsDetailVisible.value) {
        if (isAppsDetailVisible.value && pagerState.currentPage != BottomNavScreen.Apps.index) {
            pagerState.scrollToPage(BottomNavScreen.Apps.index)
            selectedIndex = BottomNavScreen.Apps.index
        }
    }

    BackHandler(enabled = !isAppsDetailVisible.value) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt <= 1800L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressedAt = now
            Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            AnimatedVisibility(
                visible = !isAppsDetailVisible.value,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            selected = selectedIndex == screen.index,
                            onClick = {
                                if (selectedIndex != screen.index) {
                                    selectedIndex = screen.index
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            page = screen.index,
                                            animationSpec = tween(durationMillis = 420, easing = navEasing)
                                        )
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            beyondViewportPageCount = screens.lastIndex,
            userScrollEnabled = !isAppsDetailVisible.value
        ) { page ->
            when (screens[page]) {
                BottomNavScreen.Home -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        item { HomeScreen() }
                    }
                }

                BottomNavScreen.Apps -> {
                    AppsScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onDetailVisibleChange = { isAppsDetailVisible.value = it }
                    )
                }

                BottomNavScreen.About -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        item { AboutScreen() }
                    }
                }
            }
        }
    }
}
