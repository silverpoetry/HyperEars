package dev.hyperears.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.hyperears.R
import dev.hyperears.integration.EarbudAdapterRegistry
import dev.hyperears.integration.NoiseMode
import dev.hyperears.root.RootAction
import dev.hyperears.root.RootActionState
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.ui.about.AboutScreen
import dev.hyperears.ui.about.CompatibilityScreen
import dev.hyperears.ui.about.MiuixAboutScreen
import dev.hyperears.ui.about.MiuixCompatibilityScreen
import dev.hyperears.ui.dashboard.DashboardScreen
import dev.hyperears.ui.dashboard.DashboardUiState
import dev.hyperears.ui.dashboard.MiuixDashboardScreen
import dev.hyperears.ui.settings.AdapterSettingsScreen
import dev.hyperears.ui.settings.DebugSettingsScreen
import dev.hyperears.ui.settings.MiuixAdapterSettingsScreen
import dev.hyperears.ui.settings.MiuixDebugSettingsScreen
import dev.hyperears.ui.settings.MiuixSettingsScreen
import dev.hyperears.ui.settings.SettingsScreen
import dev.hyperears.ui.theme.UiStyle
import dev.hyperears.update.ReleaseInfo
import dev.hyperears.update.UpdateCheckResult
import dev.hyperears.update.UpdateCheckUiState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.window.WindowDialog

private data class AppPage(
    val id: String,
    val label: String,
    val iconRes: Int,
)

private val appPages = listOf(
    AppPage("dashboard", "主页", R.drawable.ic_dashboard),
    AppPage("settings", "设置", R.drawable.ic_settings),
    AppPage("about", "关于", R.drawable.ic_info_outline),
)

private const val TOP_LEVEL_PAGE_PRELOAD_COUNT = 1

private enum class SecondaryDestination {
    DEBUG,
    ADAPTERS,
    COMPATIBILITY,
}

/**
 * Owns navigation state shared by both visual renderers. Material 3 and Miuix only render the
 * current destination; switching style never recreates the selected page or subpage destination.
 */
@Composable
fun HyperEarsApp(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onSetNoiseMode: (address: String, sessionToken: String, mode: NoiseMode) -> Unit,
    onDashboardVisibilityChanged: (Boolean) -> Unit,
    settings: ModuleSettings,
    autoCheckUpdates: Boolean,
    rootAvailable: Boolean?,
    rootActionState: RootActionState,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onRunRootAction: (RootAction) -> Unit,
    onExportLogs: () -> Unit,
    updateCheckState: UpdateCheckUiState,
    onCheckUpdates: () -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { appPages.size })
    val coroutineScope = rememberCoroutineScope()
    var secondaryDestination by rememberSaveable { mutableStateOf<SecondaryDestination?>(null) }

    BackHandler(enabled = secondaryDestination != null) {
        secondaryDestination = previousSecondaryDestination(secondaryDestination)
    }

    if (secondaryDestination != null) {
        RenderSecondaryDestination(
            style = uiStyle,
            destination = secondaryDestination ?: return,
            settings = settings,
            rootAvailable = rootAvailable,
            onSettingsChanged = onSettingsChanged,
            onExportLogs = onExportLogs,
            onOpenAdapters = { secondaryDestination = SecondaryDestination.ADAPTERS },
            onNavigateBack = {
                secondaryDestination = previousSecondaryDestination(secondaryDestination)
            },
        )
        return
    }

    val selectedPage = pagerState.settledPage
    val dashboardVisible = selectedPage == 0
    LaunchedEffect(dashboardVisible) {
        onDashboardVisibilityChanged(dashboardVisible)
    }
    DisposableEffect(Unit) {
        onDispose { onDashboardVisibilityChanged(false) }
    }

    val navigateToPage: (Int) -> Unit = remember(pagerState, coroutineScope) {
        { index ->
            if (index != pagerState.settledPage) {
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            }
        }
    }
    val pageContent: @Composable (Int) -> Unit = { page ->
        when (uiStyle) {
            UiStyle.MATERIAL3 -> when (page) {
                0 -> DashboardScreen(uiState, onRefresh, onSetNoiseMode)
                1 -> SettingsScreen(
                    settings = settings,
                    autoCheckUpdates = autoCheckUpdates,
                    rootAvailable = rootAvailable,
                    rootActionState = rootActionState,
                    uiStyle = uiStyle,
                    onSettingsChanged = onSettingsChanged,
                    onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                    onUiStyleChanged = onUiStyleChanged,
                    onRunRootAction = onRunRootAction,
                    onOpenDebug = { secondaryDestination = SecondaryDestination.DEBUG },
                )

                2 -> AboutScreen(
                    updateCheckState = updateCheckState,
                    onCheckUpdates = onCheckUpdates,
                    onOpenRelease = onOpenRelease,
                    onOpenCompatibility = {
                        secondaryDestination = SecondaryDestination.COMPATIBILITY
                    },
                )
            }

            UiStyle.MIUIX -> when (page) {
                0 -> MiuixDashboardScreen(uiState, onRefresh, onSetNoiseMode)
                1 -> MiuixSettingsScreen(
                    settings = settings,
                    autoCheckUpdates = autoCheckUpdates,
                    rootAvailable = rootAvailable,
                    rootActionState = rootActionState,
                    uiStyle = uiStyle,
                    onSettingsChanged = onSettingsChanged,
                    onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                    onUiStyleChanged = onUiStyleChanged,
                    onRunRootAction = onRunRootAction,
                    onOpenDebug = { secondaryDestination = SecondaryDestination.DEBUG },
                )

                2 -> MiuixAboutScreen(
                    updateCheckState = updateCheckState,
                    onCheckUpdates = onCheckUpdates,
                    onOpenRelease = onOpenRelease,
                    onOpenCompatibility = {
                        secondaryDestination = SecondaryDestination.COMPATIBILITY
                    },
                )
            }
        }
    }

    when (uiStyle) {
        UiStyle.MATERIAL3 -> MaterialAppShell(
            pagerState = pagerState,
            selectedPage = selectedPage,
            onNavigate = navigateToPage,
            pageContent = pageContent,
        )

        UiStyle.MIUIX -> MiuixAppShell(
            pagerState = pagerState,
            selectedPage = selectedPage,
            onNavigate = navigateToPage,
            pageContent = pageContent,
        )
    }

    val available = updateCheckState.result as? UpdateCheckResult.Available
    if (available != null && updateCheckState.showAvailableDialog) {
        when (uiStyle) {
            UiStyle.MATERIAL3 -> MaterialUpdateDialog(
                available = available,
                onDismiss = onDismissUpdate,
                onOpenRelease = onOpenRelease,
            )

            UiStyle.MIUIX -> MiuixUpdateDialog(
                available = available,
                onDismiss = onDismissUpdate,
                onOpenRelease = onOpenRelease,
            )
        }
    }
}

private fun previousSecondaryDestination(
    destination: SecondaryDestination?,
): SecondaryDestination? = when (destination) {
    SecondaryDestination.ADAPTERS -> SecondaryDestination.DEBUG
    SecondaryDestination.DEBUG,
    SecondaryDestination.COMPATIBILITY,
    null,
    -> null
}

@Composable
private fun RenderSecondaryDestination(
    style: UiStyle,
    destination: SecondaryDestination,
    settings: ModuleSettings,
    rootAvailable: Boolean?,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onExportLogs: () -> Unit,
    onOpenAdapters: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    when (style) {
        UiStyle.MATERIAL3 -> when (destination) {
            SecondaryDestination.ADAPTERS -> AdapterSettingsScreen(
                groups = EarbudAdapterRegistry.groups,
                settings = settings,
                onSettingsChanged = onSettingsChanged,
                onNavigateBack = onNavigateBack,
            )

            SecondaryDestination.DEBUG -> DebugSettingsScreen(
                settings = settings,
                rootAvailable = rootAvailable,
                onSettingsChanged = onSettingsChanged,
                onExportLogs = onExportLogs,
                onOpenAdapters = onOpenAdapters,
                onNavigateBack = onNavigateBack,
            )

            SecondaryDestination.COMPATIBILITY -> CompatibilityScreen(
                onNavigateBack = onNavigateBack,
            )
        }

        UiStyle.MIUIX -> when (destination) {
            SecondaryDestination.ADAPTERS -> MiuixAdapterSettingsScreen(
                groups = EarbudAdapterRegistry.groups,
                settings = settings,
                onSettingsChanged = onSettingsChanged,
                onNavigateBack = onNavigateBack,
            )

            SecondaryDestination.DEBUG -> MiuixDebugSettingsScreen(
                settings = settings,
                rootAvailable = rootAvailable,
                onSettingsChanged = onSettingsChanged,
                onExportLogs = onExportLogs,
                onOpenAdapters = onOpenAdapters,
                onNavigateBack = onNavigateBack,
            )

            SecondaryDestination.COMPATIBILITY -> MiuixCompatibilityScreen(
                onNavigateBack = onNavigateBack,
            )
        }
    }
}

@Composable
private fun MaterialAppShell(
    pagerState: PagerState,
    selectedPage: Int,
    onNavigate: (Int) -> Unit,
    pageContent: @Composable (Int) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                appPages.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = selectedPage == index,
                        onClick = { onNavigate(index) },
                        icon = {
                            Icon(
                                painter = painterResource(page.iconRes),
                                contentDescription = page.label,
                            )
                        },
                        label = { Text(page.label) },
                    )
                }
            }
        },
    ) { padding ->
        AppPager(pagerState, padding, pageContent)
    }
}

@Composable
private fun MiuixAppShell(
    pagerState: PagerState,
    selectedPage: Int,
    onNavigate: (Int) -> Unit,
    pageContent: @Composable (Int) -> Unit,
) {
    val icons = appPages.map { ImageVector.vectorResource(it.iconRes) }
    MiuixScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            MiuixNavigationBar {
                appPages.forEachIndexed { index, page ->
                    MiuixNavigationBarItem(
                        selected = selectedPage == index,
                        onClick = { onNavigate(index) },
                        icon = icons[index],
                        label = page.label,
                    )
                }
            }
        },
    ) { padding ->
        AppPager(pagerState, padding, pageContent)
    }
}

@Composable
private fun AppPager(
    pagerState: PagerState,
    padding: PaddingValues,
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        key = { appPages[it].id },
        beyondViewportPageCount = TOP_LEVEL_PAGE_PRELOAD_COUNT,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        pageContent = { page -> pageContent(page) },
    )
}

@Composable
private fun MaterialUpdateDialog(
    available: UpdateCheckResult.Available,
    onDismiss: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${available.release.version}") },
        text = { Text("可前往 GitHub Releases 下载更新。") },
        confirmButton = {
            TextButton(onClick = { onOpenRelease(available.release) }) {
                Text("查看 Release")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}

@Composable
private fun MiuixUpdateDialog(
    available: UpdateCheckResult.Available,
    onDismiss: () -> Unit,
    onOpenRelease: (ReleaseInfo) -> Unit,
) {
    WindowDialog(
        show = true,
        title = "发现新版本 ${available.release.version}",
        summary = "可前往 GitHub Releases 下载更新。",
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiuixTextButton(
                text = "稍后",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            MiuixTextButton(
                text = "查看 Release",
                onClick = { onOpenRelease(available.release) },
                modifier = Modifier.weight(1f),
                colors = MiuixButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
