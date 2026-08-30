package dev.hyperears.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudAdapterKind
import dev.hyperears.root.RootAction
import dev.hyperears.root.RootActionState
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.MoreSettingsTarget
import dev.hyperears.ui.components.MiuixHyperEarsPage
import dev.hyperears.ui.components.rememberSwitchHaptics
import dev.hyperears.ui.theme.UiStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixSettingsScreen(
    settings: ModuleSettings,
    autoCheckUpdates: Boolean,
    rootAvailable: Boolean?,
    rootActionState: RootActionState,
    uiStyle: UiStyle,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onUiStyleChanged: (UiStyle) -> Unit,
    onRunRootAction: (RootAction) -> Unit,
    onOpenDebug: () -> Unit,
) {
    MiuixHyperEarsPage(title = "设置") { pagePadding, scrollBehavior ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "module-header") {
                MiuixPreferenceSectionTitle("模块")
            }
            item(key = "module-preferences") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    MiuixSwitchPreference(
                        title = "暂停模块",
                        summary = "停用第三方耳机集成。",
                        checked = settings.modulePaused,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(modulePaused = it))
                        },
                    )
                    MiuixSwitchPreference(
                        title = "运行时退避",
                        summary = "厂商控制 App 运行时自动让出耳机私有控制通道，需勾选对应作用域。",
                        checked = settings.yieldToVendorControlApp,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(yieldToVendorControlApp = it))
                        },
                    )
                }
            }
            item(key = "application-header") {
                MiuixPreferenceSectionTitle("界面与行为")
            }
            item(key = "application-preferences") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    MiuixUiStylePreference(uiStyle, onUiStyleChanged)
                    MiuixMoreSettingsPreference(
                        selected = settings.moreSettingsTarget,
                        onSelected = { target ->
                            onSettingsChanged(settings.copy(moreSettingsTarget = target))
                        },
                    )
                    MiuixSwitchPreference(
                        title = "自动检查更新",
                        summary = "打开应用时检查 GitHub Release，每天最多一次。",
                        checked = autoCheckUpdates,
                        onCheckedChange = onAutoCheckUpdatesChanged,
                    )
                    ArrowPreference(
                        title = "调试",
                        summary = "适配器、详细日志与日志导出。",
                        onClick = onOpenDebug,
                    )
                }
            }
            item(key = "quick-actions-header") {
                MiuixPreferenceSectionTitle("快捷控制")
            }
            item(key = "quick-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rootAvailable != true) {
                        MiuixRootRequirement(rootAvailable)
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        RootAction.entries.forEach { action ->
                            MiuixActionPreference(
                                title = action.title,
                                summary = action.detail,
                                available = rootAvailable == true,
                                running = rootActionState is RootActionState.Running &&
                                    rootActionState.action == action,
                                onClick = { onRunRootAction(action) },
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
fun MiuixDebugSettingsScreen(
    settings: ModuleSettings,
    rootAvailable: Boolean?,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onExportLogs: () -> Unit,
    onOpenAdapters: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    MiuixHyperEarsPage(title = "调试", onNavigateBack = onNavigateBack) { padding, behavior ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "debug-header") {
                MiuixPreferenceSectionTitle("诊断")
            }
            item(key = "debug-preferences") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rootAvailable != true) {
                        Text(
                            text = if (rootAvailable == false) {
                                "导出 LSPosed 日志需要 Root 权限"
                            } else {
                                "正在检查 Root 权限"
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "适配器",
                            summary = "按品牌管理具体型号与家族回退。",
                            onClick = onOpenAdapters,
                        )
                        MiuixSwitchPreference(
                            title = "详细日志",
                            summary = "记录模块生命周期、协议与退避状态；需在 LSPosed 中允许详细日志并输出到守护进程。",
                            checked = settings.diagnosticLogging,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(diagnosticLogging = it))
                            },
                        )
                        MiuixActionPreference(
                            title = "导出日志",
                            summary = "导出 LSPosed 模块日志与应用操作日志。",
                            available = rootAvailable == true,
                            running = false,
                            onClick = onExportLogs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiuixAdapterSettingsScreen(
    groups: List<EarbudAdapterGroup>,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    MiuixHyperEarsPage(title = "适配器", onNavigateBack = onNavigateBack) { padding, behavior ->
        var expandedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = groups, key = EarbudAdapterGroup::id) { group ->
                val expanded = expandedGroupId == group.id
                val adapterIds = remember(group) {
                    group.adapters.mapTo(linkedSetOf()) { it.id }
                }
                val enabledCount = group.adapters.count { adapter ->
                    adapter.id !in settings.disabledAdapterIds
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiuixPreferenceSectionTitle(group.displayName)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        MiuixAdapterGroupHeader(
                            title = "全部适配器",
                            enabledCount = enabledCount,
                            totalCount = group.adapters.size,
                            expanded = expanded,
                            enabled = enabledCount > 0,
                            onEnabledChange = { enabled ->
                                val disabled = if (enabled) {
                                    settings.disabledAdapterIds - adapterIds
                                } else {
                                    settings.disabledAdapterIds + adapterIds
                                }
                                onSettingsChanged(settings.copy(disabledAdapterIds = disabled))
                            },
                            onClick = {
                                expandedGroupId = group.id.takeUnless { expanded }
                            },
                        )
                        if (expanded) {
                            EarbudAdapterKind.entries.forEach { kind ->
                                val adapters = group.adapters.filter { it.kind == kind }
                                if (adapters.isNotEmpty()) {
                                    Text(
                                        text = kind.miuixSectionTitle,
                                        modifier = Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 4.dp,
                                        ),
                                        style = MiuixTheme.textStyles.subtitle,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    adapters.forEach { adapter ->
                                        MiuixSwitchPreference(
                                            title = adapter.displayName,
                                            summary = adapter.id,
                                            checked = adapter.id !in settings.disabledAdapterIds,
                                            onCheckedChange = { enabled ->
                                                val disabled = if (enabled) {
                                                    settings.disabledAdapterIds - adapter.id
                                                } else {
                                                    settings.disabledAdapterIds + adapter.id
                                                }
                                                onSettingsChanged(
                                                    settings.copy(disabledAdapterIds = disabled),
                                                )
                                            },
                                        )
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

@Composable
private fun MiuixUiStylePreference(
    selected: UiStyle,
    onSelected: (UiStyle) -> Unit,
) {
    val items = UiStyle.entries.map { style -> DropdownItem(text = style.displayName) }
    WindowSpinnerPreference(
        items = items,
        selectedIndex = UiStyle.entries.indexOf(selected),
        title = "界面风格",
        onSelectedIndexChange = { index ->
            UiStyle.entries.getOrNull(index)?.let { style ->
                if (style != selected) onSelected(style)
            }
        },
    )
}

@Composable
private fun MiuixMoreSettingsPreference(
    selected: MoreSettingsTarget,
    onSelected: (MoreSettingsTarget) -> Unit,
) {
    val items = MoreSettingsTarget.entries.map { target ->
        DropdownItem(text = target.miuixActionLabel)
    }
    WindowSpinnerPreference(
        items = items,
        selectedIndex = MoreSettingsTarget.entries.indexOf(selected),
        title = "点击卡片“更多设置”",
        onSelectedIndexChange = { index ->
            MoreSettingsTarget.entries.getOrNull(index)?.let { target ->
                if (target != selected) onSelected(target)
            }
        },
    )
}

@Composable
private fun MiuixSwitchPreference(
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
        onCheckedChange = { updated ->
            haptics.perform(updated)
            onCheckedChange(updated)
        },
    )
}

@Composable
private fun MiuixAdapterGroupHeader(
    title: String,
    enabledCount: Int,
    totalCount: Int,
    expanded: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val haptics = rememberSwitchHaptics()
    BasicComponent(
        title = title,
        summary = "$enabledCount / $totalCount 已启用",
        onClick = onClick,
        endActions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { updated ->
                        haptics.perform(updated)
                        onEnabledChange(updated)
                    },
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
    )
}

@Composable
private fun MiuixActionPreference(
    title: String,
    summary: String,
    available: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = if (running) "$summary\n正在执行" else summary,
        enabled = available,
        onClick = { if (available && !running) onClick() },
        endActions = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
    )
}

@Composable
private fun MiuixRootRequirement(rootAvailable: Boolean?) {
    Text(
        text = if (rootAvailable == false) "需要 Root 权限" else "正在检查 Root 权限",
        modifier = Modifier.padding(horizontal = 12.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun MiuixPreferenceSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

private val MoreSettingsTarget.miuixActionLabel: String
    get() = when (this) {
        MoreSettingsTarget.SYSTEM_SETTINGS -> "打开系统设置"
        MoreSettingsTarget.VENDOR_APP -> "打开厂商 App"
        MoreSettingsTarget.HYPEREARS -> "打开 HyperEars"
    }

private val EarbudAdapterKind.miuixSectionTitle: String
    get() = when (this) {
        EarbudAdapterKind.MODEL -> "具体型号"
        EarbudAdapterKind.FAMILY -> "家族回退"
        EarbudAdapterKind.STANDARD -> "标准回退"
    }
