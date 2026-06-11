package top.lovepikachu.XiaoHeiHook

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.lovepikachu.XiaoHeiHook.pages.AboutScreen
import top.lovepikachu.XiaoHeiHook.pages.AppsScreen
import top.lovepikachu.XiaoHeiHook.pages.HomeScreen

sealed class BottomNavScreen(val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    abstract val index: Int

    data object Home : BottomNavScreen(R.string.nav_home, Icons.Filled.Home) { override val index: Int = 0 }
    data object Apps : BottomNavScreen(R.string.nav_apps, Icons.Filled.Apps) { override val index: Int = 1 }
    data object About : BottomNavScreen(R.string.nav_about, Icons.Filled.Info) { override val index: Int = 2 }
}

private val BottomNavExpandedHeight = 86.dp
private const val BottomNavPageSlideDurationMillis = 1000

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
    val loadedPages = remember { mutableStateListOf(BottomNavScreen.Home.index) }

    fun markPageLoaded(index: Int) {
        if (!loadedPages.contains(index)) {
            loadedPages.add(index)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedIndex = page.coerceIn(0, screens.lastIndex)
            markPageLoaded(selectedIndex)
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
            Toast.makeText(context, context.getString(R.string.toast_press_back_again), Toast.LENGTH_SHORT).show()
        }
    }

    val bottomNavVisible = !isAppsDetailVisible.value
    val bottomNavHeight by animateDpAsState(
        targetValue = if (bottomNavVisible) BottomNavExpandedHeight else 0.dp,
        animationSpec = tween(durationMillis = 180, easing = navEasing),
        label = "BottomNavHeight"
    )
    val bottomNavAlpha by animateFloatAsState(
        targetValue = if (bottomNavVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = navEasing),
        label = "BottomNavAlpha"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomNavHeight)
                    .clipToBounds()
            ) {
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = bottomNavAlpha
                            translationY = (1f - bottomNavAlpha) * 32f
                        },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    tonalElevation = 0.dp
                ) {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            selected = selectedIndex == screen.index,
                            onClick = {
                                if (selectedIndex != screen.index) {
                                    selectedIndex = screen.index
                                    markPageLoaded(screen.index)
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            page = screen.index,
                                            animationSpec = tween(durationMillis = BottomNavPageSlideDurationMillis, easing = navEasing)
                                        )
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = stringResource(screen.titleRes),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(screen.titleRes),
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
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
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
            beyondViewportPageCount = 0,
            userScrollEnabled = !isAppsDetailVisible.value
        ) { page ->
            if (!loadedPages.contains(page)) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                when (screens[page]) {
                    BottomNavScreen.Home -> {
                        HomeScreen(modifier = Modifier.fillMaxSize())
                    }

                    BottomNavScreen.Apps -> {
                        AppsScreen(
                            modifier = Modifier.fillMaxSize(),
                            onDetailVisibleChange = { isAppsDetailVisible.value = it }
                        )
                    }

                    BottomNavScreen.About -> {
                        AboutScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
