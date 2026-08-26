package dev.hyperears.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hyperears.integration.EarbudAdapterDescriptor
import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudAdapterKind
import dev.hyperears.root.RootAction
import dev.hyperears.root.RootActionState
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.MoreSettingsTarget
import dev.hyperears.ui.components.HyperEarsPage
import dev.hyperears.ui.components.rememberSwitchHaptics
import dev.hyperears.ui.theme.UiStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
    HyperEarsPage(title = "设置") { pagePadding, scrollBehavior ->
        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "module-header") {
                PreferenceSectionTitle("模块")
            }
            item(key = "module-preferences") {
                SettingsGroupCard {
                    TogglePreference(
                        title = "暂停模块",
                        detail = "停用第三方耳机集成。",
                        checked = settings.modulePaused,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(modulePaused = it))
                        },
                    )
                    PreferenceDivider()
                    TogglePreference(
                        title = "运行时退避",
                        detail = "厂商控制 App 运行时自动让出耳机私有控制通道，需勾选对应作用域。",
                        checked = settings.yieldToVendorControlApp,
                        onCheckedChange = {
                            onSettingsChanged(settings.copy(yieldToVendorControlApp = it))
                        },
                    )
                }
            }
            item(key = "application-header") {
                PreferenceSectionTitle("界面与行为")
            }
            item(key = "application-preferences") {
                SettingsGroupCard {
                    UiStylePreference(
                        selected = uiStyle,
                        onSelected = onUiStyleChanged,
                    )
                    PreferenceDivider()
                    MoreSettingsTargetPreference(
                        selected = settings.moreSettingsTarget,
                        onSelected = { target ->
                            onSettingsChanged(settings.copy(moreSettingsTarget = target))
                        },
                    )
                    PreferenceDivider()
                    TogglePreference(
                        title = "自动检查更新",
                        detail = "打开应用时检查 GitHub Release，每天最多一次。",
                        checked = autoCheckUpdates,
                        onCheckedChange = onAutoCheckUpdatesChanged,
                    )
                    PreferenceDivider()
                    NavigationPreference(
                        title = "调试",
                        detail = "适配器、详细日志与日志导出。",
                        onClick = onOpenDebug,
                    )
                }
            }
            item(key = "quick-actions-header") {
                PreferenceSectionTitle("快捷控制")
            }
            item(key = "quick-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rootAvailable != true) {
                        Text(
                            text = if (rootAvailable == false) {
                                "需要 Root 权限"
                            } else {
                                "正在检查 Root 权限"
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsGroupCard {
                        RootAction.entries.forEachIndexed { index, action ->
                            ActionPreference(
                                title = action.title,
                                detail = action.detail,
                                available = rootAvailable == true,
                                running = rootActionState is RootActionState.Running &&
                                    rootActionState.action == action,
                                onClick = { onRunRootAction(action) },
                            )
                            if (index != RootAction.entries.lastIndex) {
                                PreferenceDivider()
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun PreferenceSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun UiStylePreference(
    selected: UiStyle,
    onSelected: (UiStyle) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = "界面风格",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selected.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "选择界面风格",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        UiStyle.entries.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(style.displayName) },
                                onClick = {
                                    expanded = false
                                    if (style != selected) onSelected(style)
                                },
                                trailingIcon = if (style == selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "当前选项",
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        )
    }
}

@Composable
private fun MoreSettingsTargetPreference(
    selected: MoreSettingsTarget,
    onSelected: (MoreSettingsTarget) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = "点击卡片“更多设置”",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            },
            trailingContent = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selected.actionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "选择打开方式",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        MoreSettingsTarget.entries.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.actionLabel) },
                                onClick = {
                                    expanded = false
                                    if (target != selected) onSelected(target)
                                },
                                trailingIcon = if (target == selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "当前选项",
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        )
    }
}

private val MoreSettingsTarget.actionLabel: String
    get() = when (this) {
        MoreSettingsTarget.SYSTEM_SETTINGS -> "打开系统设置"
        MoreSettingsTarget.VENDOR_APP -> "打开厂商 App"
        MoreSettingsTarget.HYPEREARS -> "打开 HyperEars"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(
    settings: ModuleSettings,
    rootAvailable: Boolean?,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onExportLogs: () -> Unit,
    onOpenAdapters: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    HyperEarsPage(title = "调试", onNavigateBack = onNavigateBack) { pagePadding, scrollBehavior ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .navigationBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "debug-preferences") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rootAvailable != true) {
                        Text(
                            text = if (rootAvailable == false) {
                                "导出 LSPosed 日志需要 Root 权限"
                            } else {
                                "正在检查 Root 权限"
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsGroupCard {
                        NavigationPreference(
                            title = "适配器",
                            detail = "按品牌管理具体型号与家族回退。",
                            onClick = onOpenAdapters,
                        )
                        PreferenceDivider()
                        TogglePreference(
                            title = "详细日志",
                            detail = "记录模块生命周期、协议与退避状态；需在 LSPosed 中允许详细日志并输出到守护进程。",
                            checked = settings.diagnosticLogging,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(diagnosticLogging = it))
                            },
                        )
                        PreferenceDivider()
                        ActionPreference(
                            title = "导出日志",
                            detail = "导出 LSPosed 模块日志与应用操作日志。",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterSettingsScreen(
    groups: List<EarbudAdapterGroup>,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    HyperEarsPage(title = "适配器", onNavigateBack = onNavigateBack) { pagePadding, scrollBehavior ->
        val listState = rememberLazyListState()
        var expandedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
        val rows = remember(groups, expandedGroupId) {
            buildAdapterRows(groups, expandedGroupId)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .navigationBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 16.dp,
            ),
        ) {
            items(
                items = rows,
                key = AdapterListRow::key,
                contentType = AdapterListRow::contentType,
            ) { row ->
                when (row) {
                    is AdapterListRow.GroupHeader -> {
                        val group = row.group
                        val enabledCount = group.adapters.count { adapter ->
                            adapter.id !in settings.disabledAdapterIds
                        }
                        AdapterListSurface(
                            position = if (row.expanded) {
                                AdapterListPosition.TOP
                            } else {
                                AdapterListPosition.SINGLE
                            },
                        ) {
                            AdapterGroupHeader(
                                title = group.displayName,
                                enabledCount = enabledCount,
                                totalCount = group.adapters.size,
                                expanded = row.expanded,
                                enabled = enabledCount > 0,
                                onEnabledChange = { enabled ->
                                    val disabled = if (enabled) {
                                        settings.disabledAdapterIds - row.adapterIds
                                    } else {
                                        settings.disabledAdapterIds + row.adapterIds
                                    }
                                    onSettingsChanged(
                                        settings.copy(disabledAdapterIds = disabled),
                                    )
                                },
                                onClick = {
                                    expandedGroupId = group.id.takeUnless { row.expanded }
                                },
                            )
                        }
                    }

                    is AdapterListRow.SectionHeader -> {
                        AdapterListSurface(position = AdapterListPosition.MIDDLE) {
                            PreferenceDivider()
                            Text(
                                text = row.kind.sectionTitle,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 4.dp,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    is AdapterListRow.AdapterToggle -> {
                        AdapterListSurface(
                            position = if (row.endsGroup) {
                                AdapterListPosition.BOTTOM
                            } else {
                                AdapterListPosition.MIDDLE
                            },
                        ) {
                            if (row.showTopDivider) PreferenceDivider()
                            TogglePreference(
                                title = row.adapter.displayName,
                                detail = row.adapter.id,
                                checked = row.adapter.id !in settings.disabledAdapterIds,
                                onCheckedChange = { enabled ->
                                    val disabled = if (enabled) {
                                        settings.disabledAdapterIds - row.adapter.id
                                    } else {
                                        settings.disabledAdapterIds + row.adapter.id
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

private sealed interface AdapterListRow {
    val key: String
    val contentType: String

    data class GroupHeader(
        val group: EarbudAdapterGroup,
        val expanded: Boolean,
        val adapterIds: Set<String>,
    ) : AdapterListRow {
        override val key: String = "group:${group.id}"
        override val contentType: String = "group"
    }

    data class SectionHeader(
        val groupId: String,
        val kind: EarbudAdapterKind,
    ) : AdapterListRow {
        override val key: String = "section:$groupId:${kind.name}"
        override val contentType: String = "section"
    }

    data class AdapterToggle(
        val adapter: EarbudAdapterDescriptor,
        val showTopDivider: Boolean,
        val endsGroup: Boolean,
    ) : AdapterListRow {
        override val key: String = "adapter:${adapter.id}"
        override val contentType: String = "adapter"
    }
}

private fun buildAdapterRows(
    groups: List<EarbudAdapterGroup>,
    expandedGroupId: String?,
): List<AdapterListRow> = buildList {
    groups.forEach { group ->
        val expanded = group.id == expandedGroupId
        add(
            AdapterListRow.GroupHeader(
                group = group,
                expanded = expanded,
                adapterIds = group.adapters.mapTo(linkedSetOf(), EarbudAdapterDescriptor::id),
            ),
        )
        if (!expanded) return@forEach

        val sections = EarbudAdapterKind.entries.mapNotNull { kind ->
            group.adapters.filter { it.kind == kind }
                .takeIf(List<EarbudAdapterDescriptor>::isNotEmpty)
                ?.let { kind to it }
        }
        sections.forEachIndexed { sectionIndex, (kind, adapters) ->
            add(AdapterListRow.SectionHeader(group.id, kind))
            adapters.forEachIndexed { adapterIndex, adapter ->
                add(
                    AdapterListRow.AdapterToggle(
                        adapter = adapter,
                        showTopDivider = adapterIndex > 0,
                        endsGroup = sectionIndex == sections.lastIndex &&
                            adapterIndex == adapters.lastIndex,
                    ),
                )
            }
        }
    }
}

private enum class AdapterListPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM,
}

@Composable
private fun AdapterListSurface(
    position: AdapterListPosition,
    content: @Composable () -> Unit,
) {
    val shape = when (position) {
        AdapterListPosition.SINGLE -> RoundedCornerShape(24.dp)
        AdapterListPosition.TOP -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
        )

        AdapterListPosition.MIDDLE -> RoundedCornerShape(0.dp)
        AdapterListPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = 24.dp,
            bottomEnd = 24.dp,
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (position.endsGroup) 12.dp else 0.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

private val AdapterListPosition.endsGroup: Boolean
    get() = this == AdapterListPosition.SINGLE || this == AdapterListPosition.BOTTOM

private val EarbudAdapterKind.sectionTitle: String
    get() = when (this) {
        EarbudAdapterKind.MODEL -> "具体型号"
        EarbudAdapterKind.FAMILY -> "家族回退"
        EarbudAdapterKind.STANDARD -> "标准回退"
    }

@Composable
private fun AdapterGroupHeader(
    title: String,
    enabledCount: Int,
    totalCount: Int,
    expanded: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val haptics = rememberSwitchHaptics()
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = {
            Text(
                text = "$enabledCount / $totalCount 已启用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { updated ->
                        haptics.perform(updated)
                        onEnabledChange(updated)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SettingsGroupCard(
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column { content() }
    }
}

@Composable
private fun PreferenceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun TogglePreference(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberSwitchHaptics()
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { updated ->
                    haptics.perform(updated)
                    onCheckedChange(updated)
                },
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun NavigationPreference(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ActionPreference(
    title: String,
    detail: String,
    available: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (available) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
        },
        supportingContent = {
            Text(
                text = if (running) "$detail\n正在执行" else detail,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier.clickable(enabled = available && !running, onClick = onClick),
    )
}
