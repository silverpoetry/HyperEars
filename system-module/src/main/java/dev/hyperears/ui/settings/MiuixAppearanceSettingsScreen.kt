package dev.hyperears.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.hyperears.ui.components.MiuixHyperEarsPage
import dev.hyperears.ui.components.rememberSwitchHaptics
import dev.hyperears.ui.theme.UiPreferences
import dev.hyperears.ui.theme.UiThemeMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixAppearanceSettingsScreen(
    preferences: UiPreferences,
    onPreferencesChanged: (UiPreferences) -> Unit,
    onNavigateBack: () -> Unit,
) {
    MiuixHyperEarsPage(
        title = "界面设置",
        onNavigateBack = onNavigateBack,
    ) { padding, behavior ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "preview") {
                AppearancePreview(
                    preferences = preferences,
                    backgroundColor = MiuixTheme.colorScheme.background,
                    surfaceColor = MiuixTheme.colorScheme.surface,
                    cardColor = MiuixTheme.colorScheme.surfaceVariant,
                    accentColor = MiuixTheme.colorScheme.primary,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            }
            item(key = "theme-header") {
                MiuixAppearanceSectionTitle("主题")
            }
            item(key = "theme-mode") {
                TabRow(
                    tabs = UiThemeMode.entries.map(UiThemeMode::displayName),
                    selectedTabIndex = UiThemeMode.entries.indexOf(preferences.themeMode),
                    onTabSelected = { index ->
                        UiThemeMode.entries.getOrNull(index)?.let { mode ->
                            if (mode != preferences.themeMode) {
                                onPreferencesChanged(preferences.copy(themeMode = mode))
                            }
                        }
                    },
                )
            }
            item(key = "navigation-header") {
                MiuixAppearanceSectionTitle("菜单栏")
            }
            item(key = "navigation") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    MiuixAppearanceSwitch(
                        title = "模糊",
                        summary = "为底部菜单栏启用背景模糊。",
                        checked = preferences.navigationBlur,
                        onCheckedChange = { enabled ->
                            onPreferencesChanged(preferences.copy(navigationBlur = enabled))
                        },
                    )
                    MiuixAppearanceSwitch(
                        title = "悬浮底栏",
                        summary = "使用与屏幕边缘分离的悬浮底栏。",
                        checked = preferences.floatingNavigationBar,
                        onCheckedChange = { enabled ->
                            onPreferencesChanged(
                                preferences.copy(floatingNavigationBar = enabled),
                            )
                        },
                    )
                }
            }
            item(key = "display-header") {
                MiuixAppearanceSectionTitle("显示")
            }
            item(key = "display") {
                MiuixScalePreference(
                    preferences = preferences,
                    onPreferencesChanged = onPreferencesChanged,
                )
            }
        }
    }
}

@Composable
private fun MiuixScalePreference(
    preferences: UiPreferences,
    onPreferencesChanged: (UiPreferences) -> Unit,
) {
    var value by remember(preferences.interfaceScale) {
        mutableFloatStateOf(preferences.interfaceScale)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "界面缩放",
            summary = "调整应用内容与控件的整体大小。",
            endActions = {
                Text(
                    text = "${(value * 100).toInt()}%",
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            bottomAction = {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = {
                        onPreferencesChanged(preferences.copy(interfaceScale = value))
                    },
                    valueRange = UiPreferences.MIN_INTERFACE_SCALE..UiPreferences.MAX_INTERFACE_SCALE,
                    showKeyPoints = true,
                    keyPoints = listOf(0.9f, 1.0f, 1.1f),
                    magnetThreshold = 0.01f,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                )
            },
        )
    }
}

@Composable
private fun MiuixAppearanceSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberSwitchHaptics()
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { enabled ->
            haptics.perform(enabled)
            onCheckedChange(enabled)
        },
    )
}

@Composable
private fun MiuixAppearanceSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}
