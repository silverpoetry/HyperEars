package dev.hyperears.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyperears.R
import dev.hyperears.ui.components.FloatingBottomBar
import dev.hyperears.ui.components.FloatingBottomBarItem
import dev.hyperears.ui.components.MiuixBlurredBar
import dev.hyperears.ui.components.rememberMiuixBlurBackdrop
import dev.hyperears.ui.theme.UiPreferences
import dev.hyperears.ui.theme.UiStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class TopLevelDestination(
    val label: String,
    @DrawableRes val iconRes: Int,
) {
    DASHBOARD("主页", R.drawable.ic_dashboard),
    SETTINGS("设置", R.drawable.ic_settings),
    ABOUT("关于", R.drawable.ic_info_outline),
}

private const val TOP_LEVEL_PAGE_PRELOAD_COUNT = 1

/**
 * Serializes programmatic pager animations without mirroring pager selection into separate state.
 * The pager remains the only source of truth for both visual renderers.
 */
@Stable
private class TopLevelPagerController(
    private val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    val indicatorPage: Int
        get() = pagerState.targetPage

    private var navigationJob: Job? = null

    fun navigateTo(index: Int) {
        if (index !in 0 until pagerState.pageCount) return
        if (index == pagerState.targetPage) return

        navigationJob?.cancel()
        navigationJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.animateScrollToPage(index)
            } finally {
                if (navigationJob == currentJob) navigationJob = null
            }
        }
    }
}

@Composable
private fun rememberTopLevelPagerController(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
): TopLevelPagerController = remember(pagerState, coroutineScope) {
    TopLevelPagerController(pagerState, coroutineScope)
}

@Composable
internal fun HyperEarsTopLevelNavigation(
    preferences: UiPreferences,
    onDashboardVisibilityChanged: (Boolean) -> Unit,
    pageContent: @Composable (destination: TopLevelDestination, bottomPadding: Dp) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { TopLevelDestination.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val pagerController = rememberTopLevelPagerController(pagerState, coroutineScope)
    val selectedPageProvider = remember(pagerController) { { pagerController.indicatorPage } }
    val navigateToPage = remember(pagerController) { pagerController::navigateTo }
    val bottomContentPadding = topLevelBottomContentPadding(preferences)
    val dashboardVisible = pagerState.settledPage == TopLevelDestination.DASHBOARD.ordinal
    val currentVisibilityCallback = rememberUpdatedState(onDashboardVisibilityChanged)

    LaunchedEffect(dashboardVisible) {
        currentVisibilityCallback.value(dashboardVisible)
    }
    DisposableEffect(Unit) {
        onDispose { currentVisibilityCallback.value(false) }
    }

    val pagerContent: @Composable (Int) -> Unit = { index ->
        pageContent(TopLevelDestination.entries[index], bottomContentPadding)
    }
    when (preferences.style) {
        UiStyle.MATERIAL3 -> MaterialTopLevelShell(
            pagerState = pagerState,
            selectedPage = pagerState.settledPage,
            onNavigate = navigateToPage,
            pageContent = pagerContent,
        )

        UiStyle.MIUIX -> MiuixTopLevelShell(
            pagerState = pagerState,
            selectedPage = selectedPageProvider,
            onNavigate = navigateToPage,
            preferences = preferences,
            pageContent = pagerContent,
        )
    }
}

@Composable
private fun topLevelBottomContentPadding(preferences: UiPreferences): Dp {
    if (preferences.style == UiStyle.MATERIAL3) return 0.dp
    val navigationInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return navigationInset + if (preferences.floatingNavigationBar) 96.dp else 76.dp
}

@Composable
private fun MaterialTopLevelShell(
    pagerState: PagerState,
    selectedPage: Int,
    onNavigate: (Int) -> Unit,
    pageContent: @Composable (Int) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                TopLevelDestination.entries.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedPage == index,
                        onClick = { onNavigate(index) },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        TopLevelPager(pagerState, padding, pageContent = pageContent)
    }
}

@Composable
private fun MiuixTopLevelShell(
    pagerState: PagerState,
    selectedPage: () -> Int,
    onNavigate: (Int) -> Unit,
    preferences: UiPreferences,
    pageContent: @Composable (Int) -> Unit,
) {
    val icons = TopLevelDestination.entries.map { ImageVector.vectorResource(it.iconRes) }
    val standardBarBlurEnabled = preferences.navigationBlur &&
        !preferences.floatingNavigationBar
    val blurBackdrop = rememberMiuixBlurBackdrop(standardBarBlurEnabled)
    val surfaceColor = MiuixTheme.colorScheme.surface
    val floatingBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (blurBackdrop != null) {
                Modifier.layerBackdrop(blurBackdrop)
            } else {
                Modifier
            },
        ) {
            TopLevelPager(
                pagerState = pagerState,
                padding = PaddingValues(0.dp),
                modifier = if (
                    preferences.floatingNavigationBar &&
                    preferences.navigationBlur
                ) {
                    Modifier.layerBackdrop(floatingBarBackdrop)
                } else {
                    Modifier
                },
                pageContent = pageContent,
            )
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            MiuixBottomNavigation(
                selectedPage = selectedPage,
                onNavigate = onNavigate,
                preferences = preferences,
                icons = icons,
                blurBackdrop = blurBackdrop,
                floatingBarBackdrop = floatingBarBackdrop,
            )
        }
    }
}

@Composable
private fun MiuixBottomNavigation(
    selectedPage: () -> Int,
    onNavigate: (Int) -> Unit,
    preferences: UiPreferences,
    icons: List<ImageVector>,
    blurBackdrop: LayerBackdrop?,
    floatingBarBackdrop: Backdrop,
) {
    if (preferences.floatingNavigationBar) {
        FloatingBottomBar(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(
                    bottom = 12.dp + WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                ),
            selectedIndex = selectedPage,
            onSelected = onNavigate,
            backdrop = floatingBarBackdrop,
            tabsCount = TopLevelDestination.entries.size,
            backgroundBlurEnabled = preferences.navigationBlur,
        ) {
            TopLevelDestination.entries.forEachIndexed { index, destination ->
                FloatingBottomBarItem(
                    onClick = { onNavigate(index) },
                ) {
                    MiuixIcon(
                        imageVector = icons[index],
                        contentDescription = destination.label,
                    )
                    MiuixText(
                        text = destination.label,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    } else {
        MiuixBlurredBar(blurBackdrop) {
            MiuixNavigationBar(
                color = if (blurBackdrop != null) {
                    Color.Transparent
                } else {
                    MiuixTheme.colorScheme.surface
                },
            ) {
                TopLevelDestination.entries.forEachIndexed { index, destination ->
                    MiuixNavigationBarItem(
                        selected = selectedPage() == index,
                        onClick = { onNavigate(index) },
                        icon = icons[index],
                        label = destination.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopLevelPager(
    pagerState: PagerState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        key = { TopLevelDestination.entries[it].name },
        beyondViewportPageCount = TOP_LEVEL_PAGE_PRELOAD_COUNT,
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
        pageContent = { page -> pageContent(page) },
    )
}
