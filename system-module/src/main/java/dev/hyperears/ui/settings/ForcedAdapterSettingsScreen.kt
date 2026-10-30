package dev.hyperears.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudState
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.withForcedAdapter
import dev.hyperears.ui.components.HyperEarsPage
import dev.hyperears.ui.components.MiuixHyperEarsPage
import dev.hyperears.ui.theme.UiStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** Shared selection and navigation; renderers only provide their native page/row/input styles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForcedAdapterSettingsScreen(
    style: UiStyle,
    states: List<EarbudState>,
    groups: List<EarbudAdapterGroup>,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val devices = remember(states, settings.forcedAdapterIds) { forcedAdapterDevices(states, settings.forcedAdapterIds) }
    val names = remember(groups) { groups.flatMap { it.adapters }.associate { it.id to it.displayName } }
    val filtered = remember(groups, query) { filterProbeGroups(groups, query) }
    val goBack = {
        if (selectedAddress != null) { selectedAddress = null; query = "" } else onNavigateBack()
    }
    BackHandler(enabled = selectedAddress != null, onBack = goBack)
    val title = if (selectedAddress == null) "强制适配器" else "选择适配器"
    val content: @Composable (PaddingValues, NestedScrollConnection) -> Unit = { padding, scroll ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding().nestedScroll(scroll),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val address = selectedAddress
            if (address == null) {
                item(key = "description") {
                    ProbeText(style, "为指定设备选择协议探测入口；确认兼容后开放功能。", false)
                }
                if (devices.isEmpty()) {
                    item(key = "empty") { ProbeText(style, "连接耳机后选择设备。", false) }
                }
                items(devices, key = ForcedAdapterDevice::address) { device ->
                    val selection = settings.forcedAdapterIds[device.address]
                    ProbeChoice(style, device.name,
                        (if (device.connected) "已连接" else "未连接") + " · " +
                            (selection?.let { names[it] ?: "适配器已不可用" } ?: "自动识别"),
                        selected = false, enabled = true, navigation = true,
                    ) { selectedAddress = device.address; query = "" }
                }
            } else {
                item(key = "device") {
                    ProbeText(style, devices.firstOrNull { it.address == address }?.name ?: address, true)
                    ProbeText(style, "跳过型号匹配，保留端点和协议校验。选择后立即重新探测。", false)
                }
                item(key = "search") { ProbeSearch(style, query) { query = it } }
                item(key = "automatic") {
                    ProbeChoice(style, "自动识别", "使用默认匹配规则", settings.forcedAdapterIds[address] == null,
                        enabled = true) { onSettingsChanged(settings.withForcedAdapter(address, null)) }
                }
                filtered.forEach { group ->
                    item(key = "group-${group.id}") { ProbeText(style, group.displayName, true) }
                    items(group.adapters, key = { "adapter-${it.id}" }) { adapter ->
                        val enabled = adapter.id !in settings.disabledAdapterIds
                        ProbeChoice(style, adapter.displayName,
                            if (enabled) adapter.id else "已停用，请先在适配器页启用",
                            selected = settings.forcedAdapterIds[address] == adapter.id, enabled = enabled,
                        ) { onSettingsChanged(settings.withForcedAdapter(address, adapter.id)) }
                    }
                }
                if (filtered.isEmpty()) item(key = "no-results") { ProbeText(style, "没有匹配的适配器", false) }
            }
        }
    }
    when (style) {
        UiStyle.MATERIAL3 -> HyperEarsPage(title = title, onNavigateBack = goBack) { padding, behavior ->
            content(padding, behavior.nestedScrollConnection)
        }
        UiStyle.MIUIX -> MiuixHyperEarsPage(title = title, onNavigateBack = goBack) { padding, behavior ->
            content(padding, behavior.nestedScrollConnection)
        }
    }
}

@Composable
private fun ProbeText(style: UiStyle, text: String, heading: Boolean) {
    val modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    when (style) {
        UiStyle.MATERIAL3 -> Text(text, modifier,
            style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        UiStyle.MIUIX -> MiuixText(text, modifier,
            style = if (heading) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun ProbeChoice(
    style: UiStyle, title: String, detail: String, selected: Boolean, enabled: Boolean,
    navigation: Boolean = false, onClick: () -> Unit,
) {
    val icon = if (navigation) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.Check
    when (style) {
        UiStyle.MATERIAL3 -> ListItem(
            headlineContent = { Text(title) }, supportingContent = { Text(detail) },
            trailingContent = { if (selected || navigation) Icon(icon, if (selected) "已选择" else null) },
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        )
        UiStyle.MIUIX -> MiuixCard(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(title = title, summary = detail, enabled = enabled, onClick = onClick,
                endActions = { if (selected || navigation) MiuixIcon(icon,
                    if (selected) "已选择" else null, tint = MiuixTheme.colorScheme.primary) })
        }
    }
}

@Composable
private fun ProbeSearch(style: UiStyle, query: String, onQuery: (String) -> Unit) {
    when (style) {
        UiStyle.MATERIAL3 -> TextField(value = query, onValueChange = onQuery, singleLine = true,
            label = { Text("搜索品牌或适配器") }, modifier = Modifier.fillMaxWidth())
        UiStyle.MIUIX -> {
            var expanded by rememberSaveable { mutableStateOf(false) }
            SearchBar(inputField = {
                InputField(query = query, onQueryChange = onQuery, onSearch = { expanded = false },
                    expanded = expanded, onExpandedChange = { expanded = it }, label = "搜索品牌或适配器")
            }, expanded = expanded, onExpandedChange = { expanded = it }) {}
        }
    }
}
