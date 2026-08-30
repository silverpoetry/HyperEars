package dev.hyperears.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
import dev.hyperears.ui.components.HyperEarsPage
import dev.hyperears.integration.NoiseMode
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onSetNoiseMode: (address: String, sessionToken: String, mode: NoiseMode) -> Unit,
) {
    HyperEarsPage(title = "HyperEars") { pagePadding, scrollBehavior ->
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
            item(key = "runtime") {
                RuntimeCard(uiState, onRefresh)
            }
            item(key = "session-header") {
                SectionHeader(
                    title = "设备会话",
                    count = uiState.sessions.size,
                )
            }
            if (uiState.deviceCards.isEmpty()) {
                item(key = "empty-sessions") {
                    EmptySessionsCard()
                }
            } else {
                items(
                    items = uiState.deviceCards,
                    key = { session -> "${session.address}:${session.adapterId}" },
                ) { session ->
                    DeviceSessionCard(
                        session = session,
                        onSetNoiseMode = { mode ->
                            onSetNoiseMode(session.address, session.sessionToken, mode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "运行状态",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefresh) { Text("同步") }
            }
            RuntimeProcessRow(
                label = "蓝牙进程 Hook",
                status = if (uiState.runtimeResponsive) {
                    "已响应 · ${uiState.lastUpdatedAtMillis?.let(::formatTime) ?: "—"}"
                } else {
                    "未响应"
                },
                online = uiState.runtimeResponsive,
            )
            Spacer(Modifier.height(12.dp))
            RuntimeProcessRow(
                label = "MiLink 进程 Hook",
                status = if (uiState.miLinkProcesses.isEmpty()) {
                    "未响应"
                } else {
                    "${uiState.miLinkProcesses.size} 个进程响应"
                },
                online = uiState.miLinkProcesses.isNotEmpty(),
            )
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("状态接收", uiState.miLinkObservedCount, Modifier.weight(1f))
                SummaryMetric("身份查询", uiState.identityQueriedCount, Modifier.weight(1f))
                SummaryMetric("能力查询", uiState.capabilitiesQueriedCount, Modifier.weight(1f))
                SummaryMetric("活动会话", uiState.sessions.size, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RuntimeProcessRow(
    label: String,
    status: String,
    online: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = if (online) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptySessionsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "暂无活动设备会话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "受支持耳机连接后显示",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceSessionCard(
    session: DeviceSessionUiModel,
    onSetNoiseMode: (NoiseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Adapter  ${session.adapterName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "ID  ${session.adapterId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "蓝牙  ${session.address}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.size(12.dp))
                PhasePill(session.phase)
            }

            AdapterFacts(session)

            Text(
                text = "会话状态",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SessionStatusList(session.headsetLifecycle)

            Text(
                text = "MiLink 处理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LifecycleStrip(session.miLinkLifecycle)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            MetricStrip(
                metrics = session.metrics,
                noiseControl = session.noiseControl,
                onSetNoiseMode = onSetNoiseMode,
            )
        }
    }
}

@Composable
private fun AdapterFacts(session: DeviceSessionUiModel) {
    if (!session.adapterResolved) {
        Text(
            text = session.adapterSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = session.adapterSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "控制  ${session.controlSummary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionStatusList(
    stages: List<DeviceLinkStage>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.forEachIndexed { index, stage ->
            SessionStatusRow(stage)
            if (index != stages.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricStrip(
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
                NoiseModeMetric(
                    metric = metric,
                    control = noiseControl,
                    onSetNoiseMode = onSetNoiseMode,
                    modifier = Modifier.weight(1f),
                )
            } else {
                CompactMetric(metric.label, metric.value, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NoiseModeMetric(
    metric: DeviceMetric,
    control: NoiseControlUiModel,
    onSetNoiseMode: (NoiseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectable = control.enabled && control.supportedModes.size > 1

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = selectable) { expanded = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (selectable) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "切换模式",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        DropdownMenu(
            expanded = expanded && selectable,
            onDismissRequest = { expanded = false },
        ) {
            control.supportedModes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName()) },
                    onClick = {
                        expanded = false
                        if (mode != control.selectedMode) onSetNoiseMode(mode)
                    },
                    trailingIcon = if (mode == control.selectedMode) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "当前模式",
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionStatusRow(
    stage: DeviceLinkStage,
) {
    val color = when (stage.status) {
        DeviceLinkStatus.READY -> MaterialTheme.colorScheme.primary
        DeviceLinkStatus.ACTIVE -> MaterialTheme.colorScheme.tertiary
        DeviceLinkStatus.INACTIVE -> MaterialTheme.colorScheme.outline
        DeviceLinkStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Text(
            text = stage.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stage.value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LifecycleStrip(stages: List<DeviceLifecycleStage>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stages.forEach { stage ->
            val color = when {
                stage.complete -> MaterialTheme.colorScheme.primary
                stage.active -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outline
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = if (stage.complete || stage.active) {
                    color.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    StatusDot(color)
                    Text(
                        text = stage.label,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = stage.value,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
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
private fun CompactMetric(
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PhasePill(phase: DevicePhase) {
    val color = when (phase) {
        DevicePhase.SYSTEM_DISCONNECTED,
        DevicePhase.PROTOCOL_REJECTED,
        DevicePhase.TRANSPORT_DORMANT,
        -> MaterialTheme.colorScheme.error
        DevicePhase.TRANSPORT_CONNECTING,
        DevicePhase.TRANSPORT_RECOVERING,
        DevicePhase.PROTOCOL_CONFIRMING,
        -> MaterialTheme.colorScheme.secondary
        DevicePhase.WAITING_FOR_MILINK,
        DevicePhase.EXTERNAL_CONTROL_APP,
        -> MaterialTheme.colorScheme.tertiary
        DevicePhase.STATE_ACCEPTED,
        DevicePhase.IDENTITY_QUERIED,
        DevicePhase.CAPABILITIES_QUERIED,
        -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = phase.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))
