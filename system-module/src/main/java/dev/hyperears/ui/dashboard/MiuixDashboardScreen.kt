package dev.hyperears.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyperears.integration.NoiseMode
import dev.hyperears.ui.components.MiuixHyperEarsPage
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun MiuixDashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onSetNoiseMode: (address: String, sessionToken: String, mode: NoiseMode) -> Unit,
) {
    MiuixHyperEarsPage(title = "HyperEars") { pagePadding, scrollBehavior ->
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
            item(key = "runtime-status") { MiuixRuntimeStatusCard(uiState, onRefresh) }
            item(key = "runtime-stats") { MiuixRuntimeStats(uiState) }
            item(key = "runtime-header") { MiuixGroupTitle("MiLink 处理") }
            item(key = "runtime-details") { MiuixRuntimeDetailsCard(uiState, onRefresh) }
            item(key = "session-header") {
                MiuixSectionHeader("设备会话", uiState.sessions.size)
            }
            if (uiState.deviceCards.isEmpty()) {
                item(key = "empty-sessions") { MiuixEmptySessionsCard() }
            } else {
                items(
                    items = uiState.deviceCards,
                    key = { session -> "${session.address}:${session.adapterId}" },
                ) { session ->
                    MiuixDeviceSessionCard(session) { mode ->
                        onSetNoiseMode(session.address, session.sessionToken, mode)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixRuntimeStatusCard(uiState: DashboardUiState, onRefresh: () -> Unit) {
    val ready = uiState.runtimeResponsive && uiState.miLinkProcesses.isNotEmpty()
    val bluetoothOnly = uiState.runtimeResponsive && uiState.miLinkProcesses.isEmpty()
    val title = when {
        ready -> "运行正常"
        bluetoothOnly -> "等待 MiLink"
        else -> "模块未响应"
    }
    val summary = when {
        ready -> "蓝牙与 MiLink 已响应"
        bluetoothOnly -> "蓝牙进程已响应"
        else -> "未收到蓝牙或 MiLink 状态"
    }
    val dark = isSystemInDarkTheme()
    val container = when {
        ready && dark -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        dark -> Color(0xFF381A1A)
        else -> Color(0xFFFAEEEE)
    }
    val accent = if (ready) Color(0xFF36D167) else Color(0xFFF72727)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = container,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        onClick = onRefresh,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(50.dp, 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = if (ready) {
                        Icons.Rounded.CheckCircleOutline
                    } else {
                        Icons.Default.Warning
                    },
                    contentDescription = null,
                    modifier = Modifier.size(170.dp),
                    tint = accent,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(36.dp))
                Text(
                    text = "${uiState.connectedCount} 个活动会话 · ${uiState.miLinkProcesses.size} 个 MiLink 进程",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun MiuixRuntimeStats(uiState: DashboardUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiuixStatCard(
            "活动会话",
            uiState.connectedCount,
            Modifier.weight(1f).fillMaxHeight(),
        )
        MiuixStatCard(
            "协议已确认",
            uiState.handshakeCount,
            Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun MiuixStatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value.toString(),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MiuixRuntimeDetailsCard(uiState: DashboardUiState, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "处理进度",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = uiState.lastUpdatedAtMillis?.let(::formatMiuixTime) ?: "点击同步",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MiuixRuntimeMetric("状态", uiState.miLinkObservedCount, Modifier.weight(1f))
                MiuixRuntimeMetric("身份", uiState.identityQueriedCount, Modifier.weight(1f))
                MiuixRuntimeMetric("能力", uiState.capabilitiesQueriedCount, Modifier.weight(1f))
                MiuixRuntimeMetric(
                    "通知",
                    uiState.sessions.count { it.runtimeNotified },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiuixRuntimeMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value.toString(),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MiuixSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = count.toString(),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MiuixGroupTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

@Composable
private fun MiuixEmptySessionsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "暂无活动设备会话",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "受支持耳机连接后显示",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun MiuixDeviceSessionCard(
    session: DeviceSessionUiModel,
    onSetNoiseMode: (NoiseMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.deviceName,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiuixSecondaryText("Adapter  ${session.adapterName}")
                    MiuixSecondaryText("ID  ${session.adapterId}")
                    MiuixSecondaryText("蓝牙  ${session.address}")
                }
                Spacer(Modifier.size(12.dp))
                MiuixPhasePill(session.phase)
            }

            MiuixAdapterFacts(session)
            MiuixSectionLabel("会话状态")
            MiuixSessionStatusList(session.headsetLifecycle)
            MiuixSectionLabel("MiLink 处理")
            MiuixLifecycleStrip(session.miLinkLifecycle)
            MiuixMetricStrip(session.metrics, session.noiseControl, onSetNoiseMode)
        }
    }
}

@Composable
private fun MiuixSecondaryText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MiuixAdapterFacts(session: DeviceSessionUiModel) {
    val color = if (session.adapterResolved) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MiuixTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = session.adapterSummary,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
        )
        if (session.adapterResolved) {
            Text(
                text = "控制  ${session.controlSummary}",
                style = MiuixTheme.textStyles.footnote1,
                color = color,
            )
        }
    }
}

@Composable
private fun MiuixSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun MiuixSessionStatusList(stages: List<DeviceLinkStage>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.forEach { stage ->
            MiuixSessionStatusRow(stage)
        }
    }
}

@Composable
private fun MiuixSessionStatusRow(stage: DeviceLinkStage) {
    val color = when (stage.status) {
        DeviceLinkStatus.READY -> MiuixTheme.colorScheme.primary
        DeviceLinkStatus.ACTIVE -> MiuixTheme.colorScheme.secondary
        DeviceLinkStatus.INACTIVE -> MiuixTheme.colorScheme.outline
        DeviceLinkStatus.ERROR -> MiuixTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixStatusDot(color)
        Text(
            text = stage.label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = stage.value,
            style = MiuixTheme.textStyles.body2,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiuixLifecycleStrip(stages: List<DeviceLifecycleStage>) {
    val dark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stages.forEach { stage ->
            val color = when {
                stage.complete -> MiuixTheme.colorScheme.primary
                stage.active -> MiuixTheme.colorScheme.secondary
                else -> MiuixTheme.colorScheme.outline
            }
            Card(
                modifier = Modifier.weight(1f),
                cornerRadius = 14.dp,
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                    color = if (stage.complete || stage.active) {
                        color.copy(alpha = 0.12f)
                    } else if (dark) {
                        Color(0xFF2C2C2E)
                    } else {
                        Color(0xFFF2F2F2)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    MiuixStatusDot(color)
                    Text(
                        text = stage.label,
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = stage.value,
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.footnote2,
                        color = color,
                        fontWeight = if (stage.complete || stage.active) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixMetricStrip(
    metrics: List<DeviceMetric>,
    noiseControl: NoiseControlUiModel?,
    onSetNoiseMode: (NoiseMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.forEach { metric ->
            if (metric.kind == DeviceMetricKind.NOISE_MODE && noiseControl != null) {
                MiuixNoiseModeMetric(
                    metric = metric,
                    control = noiseControl,
                    onSetNoiseMode = onSetNoiseMode,
                    modifier = Modifier.weight(1f),
                )
            } else {
                MiuixCompactMetric(metric.label, metric.value, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiuixNoiseModeMetric(
    metric: DeviceMetric,
    control: NoiseControlUiModel,
    onSetNoiseMode: (NoiseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val selectable = control.enabled && control.supportedModes.size > 1
    Column(
        modifier = modifier.clickable(enabled = selectable) { showDialog = true },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = metric.value,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (selectable) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "切换模式",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
        Text(
            text = metric.label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
    WindowDialog(
        show = showDialog && selectable,
        title = "降噪模式",
        onDismissRequest = { showDialog = false },
    ) {
        Column {
            control.supportedModes.forEach { mode ->
                RadioButtonPreference(
                    title = mode.displayName(),
                    selected = mode == control.selectedMode,
                    onClick = {
                        showDialog = false
                        if (mode != control.selectedMode) onSetNoiseMode(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun MiuixCompactMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.headline2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MiuixPhasePill(phase: DevicePhase) {
    val color = when (phase) {
        DevicePhase.SYSTEM_DISCONNECTED,
        DevicePhase.PROTOCOL_REJECTED,
        DevicePhase.TRANSPORT_DORMANT,
        -> MiuixTheme.colorScheme.error

        DevicePhase.TRANSPORT_CONNECTING,
        DevicePhase.TRANSPORT_RECOVERING,
        DevicePhase.PROTOCOL_CONFIRMING,
        DevicePhase.WAITING_FOR_MILINK,
        DevicePhase.EXTERNAL_CONTROL_APP,
        -> MiuixTheme.colorScheme.secondary

        DevicePhase.STATE_ACCEPTED,
        DevicePhase.IDENTITY_QUERIED,
        DevicePhase.CAPABILITIES_QUERIED,
        -> MiuixTheme.colorScheme.primary
    }
    Card(
        cornerRadius = 12.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = color.copy(alpha = 0.12f),
            contentColor = color,
        ),
    ) {
        Text(
            text = phase.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun MiuixStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

private fun formatMiuixTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))
