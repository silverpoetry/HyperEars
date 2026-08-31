package dev.hyperears.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import dev.hyperears.ui.settings.AppearanceSettingsScreen
import dev.hyperears.ui.settings.DebugSettingsScreen
import dev.hyperears.ui.settings.MiuixAdapterSettingsScreen
import dev.hyperears.ui.settings.MiuixAppearanceSettingsScreen
import dev.hyperears.ui.settings.MiuixDebugSettingsScreen
import dev.hyperears.ui.settings.MiuixSettingsScreen
import dev.hyperears.ui.settings.SettingsScreen
import dev.hyperears.ui.theme.UiPreferences
import dev.hyperears.ui.theme.UiStyle
import dev.hyperears.update.ReleaseInfo
import dev.hyperears.update.UpdateCheckResult
import dev.hyperears.update.UpdateCheckUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class SecondaryDestination {
    APPEARANCE,
    DEBUG,
    ADAPTERS,
    COMPATIBILITY,
}

/**
 * Routes shared application state into renderer-specific screens. Top-level pager and bottom-bar
 * state live in [HyperEarsTopLevelNavigation]; this function only owns secondary destinations and
 * application dialogs.
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
    uiPreferences: UiPreferences,
    onUiPreferencesChanged: (UiPreferences) -> Unit,
) {
    val uiStyle = uiPreferences.style
    var secondaryDestination by rememberSaveable { mutableStateOf<SecondaryDestination?>(null) }
    val navigationStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = secondaryDestination != null) {
        secondaryDestination = previousSecondaryDestination(secondaryDestination)
    }

    val activeSecondaryDestination = secondaryDestination
    if (activeSecondaryDestination != null) {
        navigationStateHolder.SaveableStateProvider(
            key = activeSecondaryDestination.stateKey,
        ) {
            RenderSecondaryDestination(
                style = uiStyle,
                destination = activeSecondaryDestination,
                uiPreferences = uiPreferences,
                onUiPreferencesChanged = onUiPreferencesChanged,
                settings = settings,
                rootAvailable = rootAvailable,
                onSettingsChanged = onSettingsChanged,
                onExportLogs = onExportLogs,
                onOpenAdapters = { secondaryDestination = SecondaryDestination.ADAPTERS },
                onNavigateBack = {
                    secondaryDestination = previousSecondaryDestination(secondaryDestination)
                },
            )
        }
        return
    }

    navigationStateHolder.SaveableStateProvider(key = TOP_LEVEL_STATE_KEY) {
        HyperEarsTopLevelNavigation(
            preferences = uiPreferences,
            onDashboardVisibilityChanged = onDashboardVisibilityChanged,
        ) { destination, bottomContentPadding ->
            when (uiStyle) {
                UiStyle.MATERIAL3 -> when (destination) {
                    TopLevelDestination.DASHBOARD -> DashboardScreen(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        onSetNoiseMode = onSetNoiseMode,
                        bottomContentPadding = bottomContentPadding,
                    )
                    TopLevelDestination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        autoCheckUpdates = autoCheckUpdates,
                        rootAvailable = rootAvailable,
                        rootActionState = rootActionState,
                        onSettingsChanged = onSettingsChanged,
                        onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                        onRunRootAction = onRunRootAction,
                        uiPreferences = uiPreferences,
                        onUiPreferencesChanged = onUiPreferencesChanged,
                        onOpenAppearance = {
                            secondaryDestination = SecondaryDestination.APPEARANCE
                        },
                        onOpenDebug = { secondaryDestination = SecondaryDestination.DEBUG },
                        bottomContentPadding = bottomContentPadding,
                    )

                    TopLevelDestination.ABOUT -> AboutScreen(
                        updateCheckState = updateCheckState,
                        onCheckUpdates = onCheckUpdates,
                        onOpenRelease = onOpenRelease,
                        onOpenCompatibility = {
                            secondaryDestination = SecondaryDestination.COMPATIBILITY
                        },
                        bottomContentPadding = bottomContentPadding,
                    )
                }

                UiStyle.MIUIX -> when (destination) {
                    TopLevelDestination.DASHBOARD -> MiuixDashboardScreen(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        onSetNoiseMode = onSetNoiseMode,
                        bottomContentPadding = bottomContentPadding,
                    )
                    TopLevelDestination.SETTINGS -> MiuixSettingsScreen(
                        settings = settings,
                        autoCheckUpdates = autoCheckUpdates,
                        rootAvailable = rootAvailable,
                        rootActionState = rootActionState,
                        onSettingsChanged = onSettingsChanged,
                        onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                        onRunRootAction = onRunRootAction,
                        uiPreferences = uiPreferences,
                        onUiPreferencesChanged = onUiPreferencesChanged,
                        onOpenAppearance = {
                            secondaryDestination = SecondaryDestination.APPEARANCE
                        },
                        onOpenDebug = { secondaryDestination = SecondaryDestination.DEBUG },
                        bottomContentPadding = bottomContentPadding,
                    )

                    TopLevelDestination.ABOUT -> MiuixAboutScreen(
                        updateCheckState = updateCheckState,
                        onCheckUpdates = onCheckUpdates,
                        onOpenRelease = onOpenRelease,
                        onOpenCompatibility = {
                            secondaryDestination = SecondaryDestination.COMPATIBILITY
                        },
                        bottomContentPadding = bottomContentPadding,
                    )
                }
            }
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
}

private const val TOP_LEVEL_STATE_KEY = "top-level"

private val SecondaryDestination.stateKey: String
    get() = "secondary-$name"

private fun previousSecondaryDestination(
    destination: SecondaryDestination?,
): SecondaryDestination? = when (destination) {
    SecondaryDestination.ADAPTERS -> SecondaryDestination.DEBUG
    SecondaryDestination.APPEARANCE,
    SecondaryDestination.DEBUG,
    SecondaryDestination.COMPATIBILITY,
    null,
    -> null
}

@Composable
private fun RenderSecondaryDestination(
    style: UiStyle,
    destination: SecondaryDestination,
    uiPreferences: UiPreferences,
    onUiPreferencesChanged: (UiPreferences) -> Unit,
    settings: ModuleSettings,
    rootAvailable: Boolean?,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onExportLogs: () -> Unit,
    onOpenAdapters: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    when (style) {
        UiStyle.MATERIAL3 -> when (destination) {
            SecondaryDestination.APPEARANCE -> AppearanceSettingsScreen(
                preferences = uiPreferences,
                onPreferencesChanged = onUiPreferencesChanged,
                onNavigateBack = onNavigateBack,
            )

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
            SecondaryDestination.APPEARANCE -> MiuixAppearanceSettingsScreen(
                preferences = uiPreferences,
                onPreferencesChanged = onUiPreferencesChanged,
                onNavigateBack = onNavigateBack,
            )

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
