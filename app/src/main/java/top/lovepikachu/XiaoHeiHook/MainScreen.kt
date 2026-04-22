package top.lovepikachu.XiaoHeiHook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {

    val currentScreen = remember { mutableStateOf<BottomNavScreen>(BottomNavScreen.Home) }
    val isBottomBarVisible = remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // ==================== 改进后的 NestedScrollConnection ====================
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 允许在顶部继续下拉（类似 pull-to-refresh）
                if (available.y > 0 && !isBottomBarVisible.value) {
                    isBottomBarVisible.value = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.y < -15f) isBottomBarVisible.value = false
                return Offset.Zero
            }
        }
    }

    val screens = listOf(BottomNavScreen.Home, BottomNavScreen.Apps, BottomNavScreen.About)
    val pagerState = rememberPagerState(initialPage = currentScreen.value.index) { screens.size }

    LaunchedEffect(pagerState.currentPage) {
        currentScreen.value = screens[pagerState.currentPage]
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ==================== 内容区域（全屏，无底部 padding） ====================
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
            ) { pageIndex ->
                val screen = screens[pageIndex]

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    // 不再在这里加 nestedScroll
                ) {
                    item {
                        when (screen) {
                            BottomNavScreen.Home -> HomeScreen()
                            BottomNavScreen.Apps -> AppsScreen()
                            BottomNavScreen.About -> AboutScreen()
                        }
                    }
                }
            }

            // ==================== 可隐藏的底部导航栏（绝对定位） ====================
            AnimatedVisibility(
                visible = isBottomBarVisible.value,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen.value == screen,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(screen.index)
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
    }
}