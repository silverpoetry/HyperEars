package dev.hyperears.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hyperears.ui.components.HyperEarsPage
import dev.hyperears.ui.theme.UiPreferences
import dev.hyperears.ui.theme.UiThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    preferences: UiPreferences,
    onPreferencesChanged: (UiPreferences) -> Unit,
    onNavigateBack: () -> Unit,
) {
    HyperEarsPage(title = "界面设置", onNavigateBack = onNavigateBack) { padding, behavior ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "preview") {
                AppearancePreview(
                    preferences = preferences,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    surfaceColor = MaterialTheme.colorScheme.surfaceContainer,
                    cardColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    accentColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
            item(key = "theme-header") {
                AppearanceSectionTitle("主题")
            }
            item(key = "theme-mode") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UiThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = preferences.themeMode == mode,
                            onClick = {
                                if (mode != preferences.themeMode) {
                                    onPreferencesChanged(preferences.copy(themeMode = mode))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UiThemeMode.entries.size,
                            ),
                        ) {
                            Text(mode.displayName)
                        }
                    }
                }
            }
            item(key = "scale-header") {
                AppearanceSectionTitle("显示")
            }
            item(key = "scale") {
                AppearanceScalePreference(
                    preferences = preferences,
                    onPreferencesChanged = onPreferencesChanged,
                )
            }
        }
    }
}

@Composable
private fun AppearanceScalePreference(
    preferences: UiPreferences,
    onPreferencesChanged: (UiPreferences) -> Unit,
) {
    var value by remember(preferences.interfaceScale) {
        mutableFloatStateOf(preferences.interfaceScale)
    }
    AppearanceCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "界面缩放",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "调整应用内容与控件的整体大小。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${(value * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = {
                    onPreferencesChanged(preferences.copy(interfaceScale = value))
                },
                valueRange = UiPreferences.MIN_INTERFACE_SCALE..UiPreferences.MAX_INTERFACE_SCALE,
                steps = 1,
            )
        }
    }
}

@Composable
private fun AppearanceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppearanceSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
