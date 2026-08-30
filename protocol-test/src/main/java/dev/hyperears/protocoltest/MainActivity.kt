package dev.hyperears.protocoltest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyperears.protocol.starring.StarRingWireCodec
import dev.hyperears.protocol.starring.StarRingWireCodec.NoiseMode as StarRingNoiseMode
import dev.hyperears.protocol.vivo.VivoEarbudModelCatalog
import dev.hyperears.protocol.vivo.VivoTwsProtocol
import dev.hyperears.protocol.vivo.VivoTwsProtocol.NoiseMode
import dev.hyperears.protocol.vivo.VivoTwsProtocol.WireConfig

class MainActivity : ComponentActivity() {
    private val model: ProtocolTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperEarsTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    model.updatePermissionState()
                }

                LaunchedEffect(Unit) {
                    if (REQUIRED_BLUETOOTH_PERMISSIONS.any {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                                PackageManager.PERMISSION_GRANTED
                        }) {
                        permissionLauncher.launch(REQUIRED_BLUETOOTH_PERMISSIONS)
                    } else {
                        model.updatePermissionState()
                    }
                }

                ProtocolLabScreen(
                    state = state,
                    onRequestPermission = {
                        permissionLauncher.launch(REQUIRED_BLUETOOTH_PERMISSIONS)
                    },
                    onRefreshDevices = model::refreshPairedDevices,
                    onStartIdentityScan = model::startIdentityScan,
                    onStopIdentityScan = model::stopIdentityScan,
                    onSelectDevice = model::selectDevice,
                    onAddressChange = model::updateAddress,
                    onSelectTarget = model::selectTarget,
                    onConnect = model::connect,
                    onDisconnect = model::disconnect,
                    onSelectWireConfig = model::selectWireConfig,
                    onFullProbe = model::runReadOnlyProbe,
                    onHandshake = model::sendHandshake,
                    onQueryNoise = model::queryNoise,
                    onQueryBattery = model::queryBattery,
                    onSetNoise = model::setNoiseMode,
                    onSetStarRingNoise = model::setStarRingNoiseMode,
                    onRawChange = model::updateRawCommand,
                    onSendRaw = model::sendRaw,
                    onClearLogs = model::clearLogs,
                )
            }
        }
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
    }

    private fun handleAutomationIntent(intent: Intent) {
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_AUTO_PROBE, false)) {
            intent.removeExtra(EXTRA_AUTO_PROBE)
            intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                ?.takeIf(String::isNotBlank)
                ?.let(model::updateAddress)
            intent.getStringExtra(EXTRA_PROTOCOL_TARGET)
                ?.let { runCatching { ProtocolTarget.valueOf(it) }.getOrNull() }
                ?.let(model::selectTarget)
            model.connect()
        }
    }

    private companion object {
        const val EXTRA_AUTO_PROBE = "auto_probe"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_PROTOCOL_TARGET = "protocol_target"
        val REQUIRED_BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolLabScreen(
    state: ProtocolUiState,
    onRequestPermission: () -> Unit,
    onRefreshDevices: () -> Unit,
    onStartIdentityScan: () -> Unit,
    onStopIdentityScan: () -> Unit,
    onSelectDevice: (PairedDevice) -> Unit,
    onAddressChange: (String) -> Unit,
    onSelectTarget: (ProtocolTarget) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectWireConfig: (WireConfig) -> Unit,
    onFullProbe: () -> Unit,
    onHandshake: () -> Unit,
    onQueryNoise: () -> Unit,
    onQueryBattery: () -> Unit,
    onSetNoise: (NoiseMode) -> Unit,
    onSetStarRingNoise: (StarRingNoiseMode) -> Unit,
    onRawChange: (String) -> Unit,
    onSendRaw: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HyperEars")
                        Text(
                            text = "耳机私有协议实验室",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ConnectionCard(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            }

            if (!state.permissionGranted) {
                item {
                    LabCard(
                        title = "需要附近设备权限",
                        subtitle = "权限用于读取已配对设备、限时 BLE 判型扫描和本地 RFCOMM 连接。",
                    ) {
                        Button(onClick = onRequestPermission) {
                            Text("授予权限")
                        }
                    }
                }
            } else {
                item {
                    SectionHeader(
                        title = "目标耳机",
                        action = "刷新列表",
                        onAction = onRefreshDevices,
                    )
                }

                if (state.pairedDevices.isEmpty()) {
                    item {
                        LabCard(
                            title = "没有已配对设备",
                            subtitle = "请先在系统蓝牙设置中完成目标耳机配对，或在下方输入 MAC 地址。",
                        ) {}
                    }
                } else {
                    items(state.pairedDevices, key = { it.address }) { device ->
                        DeviceRow(
                            device = device,
                            selected = device.address == state.selectedAddress,
                            onClick = { onSelectDevice(device) },
                        )
                    }
                }

                item {
                    val focusManager = LocalFocusManager.current
                    OutlinedTextField(
                        value = state.selectedAddress,
                        onValueChange = onAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("蓝牙 MAC 地址") },
                        supportingText = {
                            Text(state.selectedName.ifBlank { "也可以手动输入 AA:BB:CC:DD:EE:FF" })
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    )
                }

                item {
                    ProtocolTargetCard(
                        selected = state.selectedTarget,
                        onSelect = onSelectTarget,
                    )
                }

                when (state.selectedTarget) {
                    ProtocolTarget.VIVO_TWS -> {
                        item {
                            VivoIdentityCard(
                                state = state,
                                onStart = onStartIdentityScan,
                                onStop = onStopIdentityScan,
                            )
                        }

                        item {
                            WireConfigCard(
                                selected = state.selectedWireConfig,
                                detected = state.detectedWireConfig,
                                onSelect = onSelectWireConfig,
                            )
                        }
                    }
                    ProtocolTarget.TECHNICS_RACE -> item { TechnicsEvidenceCard() }
                    else -> item { StarRingEvidenceCard() }
                }

                item {
                    StatusCard(state)
                }

                item {
                    ApiTestCard(
                        target = state.selectedTarget,
                        connected = state.phase == ConnectionPhase.CONNECTED,
                        handshakeStatus = state.handshakeStatus,
                        noiseStatus = state.noiseApiStatus,
                        batteryStatus = state.batteryApiStatus,
                        onFullProbe = onFullProbe,
                        onHandshake = onHandshake,
                        onQueryNoise = onQueryNoise,
                        onQueryBattery = onQueryBattery,
                    )
                }

                if (state.selectedTarget == ProtocolTarget.VIVO_TWS) {
                    item {
                        NoiseControlCard(
                            enabled = state.phase == ConnectionPhase.CONNECTED,
                            current = state.noise?.mode,
                            onSetNoise = onSetNoise,
                        )
                    }
                }

                if (state.selectedTarget == ProtocolTarget.STARRING_ULTRA) {
                    item {
                        StarRingNoiseControlCard(
                            enabled = state.phase == ConnectionPhase.CONNECTED,
                            current = state.starRingNoise,
                            onSetNoise = onSetStarRingNoise,
                        )
                    }
                }

                if (state.selectedTarget != ProtocolTarget.TECHNICS_RACE) {
                    item {
                        RawCommandCard(
                            target = state.selectedTarget,
                            enabled = state.phase == ConnectionPhase.CONNECTED,
                            value = state.rawCommand,
                            onValueChange = onRawChange,
                            onSend = onSendRaw,
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = "原始收发日志",
                    action = "清空",
                    onAction = onClearLogs,
                )
            }

            if (state.logs.isEmpty()) {
                item {
                    Text(
                        text = "连接过程和每个协议帧都会显示在这里。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.logs, key = { it.id }) { log ->
                    LogRow(log)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ProtocolUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state.phase == ConnectionPhase.CONNECTED
    val color = when (state.phase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionPhase.CONNECTING -> Color(0xFFF59E0B)
        ConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(11.dp)
                        .background(color, CircleShape),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.connectionMessage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.endpoint ?: when (state.selectedTarget) {
                            ProtocolTarget.VIVO_TWS ->
                                "将依次探测 vivo UUID、SPP、通道 12/13"

                            ProtocolTarget.STARRING_ULTRA ->
                                "将依次探测通道 28、SPP、兼容通道 5"

                            ProtocolTarget.BOSE_BMAP ->
                                "将依次探测通道 8、SPP、iAP2 传输 UUID、兼容通道 2"

                            ProtocolTarget.EDIFIER_BES ->
                                "将依次探测 Edifier SPP UUID、通道 1、标准 SPP"

                            ProtocolTarget.ROSE_BUDSFEEL ->
                                "将依次探测 BudsFeel UUID 0cf12d31、标准 SPP、通道 1/5"

                            ProtocolTarget.TECHNICS_RACE ->
                                "将依次探测 Technics 厂商 UUID、标准 SPP"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (connected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("断开 RFCOMM")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.phase != ConnectionPhase.CONNECTING && state.permissionGranted,
                ) {
                    Text(if (state.phase == ConnectionPhase.CONNECTING) "正在连接…" else "连接并自动只读探测")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: PairedDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (selected) "已选择" else device.suggestedTarget?.label.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProtocolTargetCard(
    selected: ProtocolTarget,
    onSelect: (ProtocolTarget) -> Unit,
) {
    LabCard(
        title = "实验协议",
        subtitle = "选择设备时会按名称自动切换，也可在此手动指定。",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProtocolTarget.entries.forEach { target ->
                FilterChip(
                    selected = selected == target,
                    onClick = { onSelect(target) },
                    label = { Text(target.label) },
                )
            }
        }
    }
}

@Composable
private fun StarRingEvidenceCard() {
    LabCard(
        title = "StarRing 抓包协议",
        subtitle = "业务帧来自官方 App；本版按最新实测使用 BLE GATT 写入。",
    ) {
        Text(
            "查询帧",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                "08 EE 00 00 00 01 01 0A 00 02",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "预期回报：group 0x01 / command 0x01；左右耳取 payload[2]/[3]，" +
                "充电盒取 payload[6]，0xFF 表示不可用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TechnicsEvidenceCard() {
    LabCard(
        title = "Technics RACE 只读协议",
        subtitle = "参考实现已覆盖多个 EAH-AZ 型号；HyperEars 本次实机仅验证 AZ80。",
    ) {
        Text(
            "探测范围：左右耳和充电盒电量；外部控制 0x000A。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "仅发送 GET 查询，不提供设置命令或原始帧写入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StarRingNoiseControlCard(
    enabled: Boolean,
    current: StarRingNoiseMode?,
    onSetNoise: (StarRingNoiseMode) -> Unit,
) {
    LabCard(
        title = "StarRing 官方 GATT 模式测试",
        subtitle = "每次点击只发一条 GATT Write Request；不追加查询，不经过 MiLink。",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StarRingWireCodec.NoiseMode.entries.forEach { mode ->
                if (mode == current) {
                    Button(onClick = { onSetNoise(mode) }, enabled = enabled) {
                        Text("${mode.label} · 当前")
                    }
                } else {
                    OutlinedButton(onClick = { onSetNoise(mode) }, enabled = enabled) {
                        Text(mode.label)
                    }
                }
            }
        }
        Text(
            "日志中同一编号应只有一条 TX 和一条 GATT TX_OK；MODE 仅来自耳机通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VivoIdentityCard(
    state: ProtocolUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scanning = state.identityScanPhase == IdentityScanPhase.SCANNING
    LabCard(
        title = "vivo 系型号判型",
        subtitle = state.identityScanMessage,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (scanning) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("停止扫描")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("扫描 20 秒")
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${state.observedVivoAdvertisements} / ${state.observedAdvertisements}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "vivo 广播 / 全部广播",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.identityDetections.isEmpty()) {
            Text(
                text = if (scanning) {
                    "等待命中 vivo 官方广播结构…"
                } else {
                    "没有判型记录"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.identityDetections.forEach { detection ->
                VivoIdentityDetectionCard(
                    detection = detection,
                    selectedAddress = state.selectedAddress,
                    selectedName = state.selectedName,
                )
            }
        }
    }
}

@Composable
private fun VivoIdentityDetectionCard(
    detection: VivoIdentityDetection,
    selectedAddress: String,
    selectedName: String,
) {
    val modelLabel = VivoEarbudModelCatalog.label(detection.identity.modelId)
    val sameAddress = selectedAddress.equals(detection.address, ignoreCase = true)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        detection.name ?: "未广播名称",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        detection.address,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "model ${detection.identity.modelId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        modelLabel ?: "官方表未收录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${detection.identity.uuidLabel} · ${detection.identity.layout.label} · " +
                    "AD 0x${detection.identity.advertisementType.toString(16).uppercase().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    sameAddress -> "地址关系：与当前目标经典蓝牙地址一致"
                    selectedAddress.isBlank() -> "地址关系：尚未选择目标耳机"
                    else -> "地址关系：BLE 地址不同于 $selectedAddress，需确认双地址映射"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (sameAddress) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!sameAddress && selectedName.isNotBlank()) {
                Text(
                    "当前目标：$selectedName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "RSSI ${detection.rssi} dBm · 已见 ${detection.seenCount} 次 · ${detection.lastSeen}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(
                "原始 ScanRecord",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    detection.rawAdvertisement,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WireConfigCard(
    selected: WireConfig,
    detected: WireConfig?,
    onSelect: (WireConfig) -> Unit,
) {
    LabCard(
        title = "协议画像",
        subtitle = detected?.let { "响应特征推断：${it.label}" }
            ?: "读操作可以全部探测；写操作只发送当前选择的变体。",
    ) {
        WireConfig.entries.forEach { configuration ->
            FilterChip(
                selected = selected == configuration,
                onClick = { onSelect(configuration) },
                label = {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(configuration.label)
                        Text(
                            configuration.note,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatusCard(state: ProtocolUiState) {
    LabCard(
        title = "耳机实时状态",
        subtitle = "数值仅在收到当前所选协议的耳机响应后展示，不使用占位假数据。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BatteryTile(
                modifier = Modifier.weight(1f),
                label = "左耳",
                percent = state.battery?.leftPercent,
                charging = state.battery?.leftCharging == true,
            )
            BatteryTile(
                modifier = Modifier.weight(1f),
                label = "右耳",
                percent = state.battery?.rightPercent,
                charging = state.battery?.rightCharging == true,
            )
            BatteryTile(
                modifier = Modifier.weight(1f),
                label = "充电盒",
                percent = state.battery?.casePercent,
                charging = state.battery?.caseCharging == true,
            )
        }
        if (state.selectedTarget == ProtocolTarget.VIVO_TWS) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("当前降噪", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.noise?.mode?.label ?: "尚未读取",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.noise?.let { noise ->
                Text(
                    "effect=${noise.noiseEffect}, transparency=" +
                        "${noise.transparencyEffect}, frame=v${noise.version}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatteryTile(
    modifier: Modifier,
    label: String,
    percent: Int?,
    charging: Boolean,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = percent?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (charging) "充电中" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ApiTestCard(
    target: ProtocolTarget,
    connected: Boolean,
    handshakeStatus: String,
    noiseStatus: String,
    batteryStatus: String,
    onFullProbe: () -> Unit,
    onHandshake: () -> Unit,
    onQueryNoise: () -> Unit,
    onQueryBattery: () -> Unit,
) {
    LabCard(
        title = "只读 API 验证",
        subtitle = when (target) {
            ProtocolTarget.VIVO_TWS ->
                "发送 v4 握手、v3/v4 降噪查询和 v4 电量查询，不改变耳机设置。"

            ProtocolTarget.STARRING_ULTRA ->
                "只发送抓包确认的 StarRing 电量查询，不改变耳机设置。"

            ProtocolTarget.BOSE_BMAP ->
                "先读取 BMAP 产品 ID，再读取 [2.2] 组件电量；两条命令均为只读。"

            ProtocolTarget.EDIFIER_BES ->
                "发送 Edifier 电量、降噪状态和设备功能查询，不改变耳机设置。"

            ProtocolTarget.ROSE_BUDSFEEL ->
                "发送 BudsFeel 状态查询（0x1E），读取电量与噪声模式，不改变耳机设置。"

            ProtocolTarget.TECHNICS_RACE ->
                "发送左右耳/充电盒电量与外部控制（0x000A）GET，不改变耳机设置。"
        },
    ) {
        if (target == ProtocolTarget.VIVO_TWS || target == ProtocolTarget.BOSE_BMAP ||
            target == ProtocolTarget.EDIFIER_BES || target == ProtocolTarget.ROSE_BUDSFEEL ||
            target == ProtocolTarget.TECHNICS_RACE) {
            ApiStatusRow(
                when (target) {
                    ProtocolTarget.BOSE_BMAP -> "产品判型"
                    ProtocolTarget.EDIFIER_BES -> "设备功能"
                    ProtocolTarget.TECHNICS_RACE -> "协议响应"
                    else -> "握手"
                },
                handshakeStatus,
            )
        }
        if (target == ProtocolTarget.VIVO_TWS || target == ProtocolTarget.EDIFIER_BES ||
            target == ProtocolTarget.ROSE_BUDSFEEL || target == ProtocolTarget.TECHNICS_RACE) {
            ApiStatusRow("降噪查询", noiseStatus)
        }
        ApiStatusRow("电量查询", batteryStatus)
        Button(
            onClick = onFullProbe,
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (target) {
                    ProtocolTarget.VIVO_TWS -> "运行完整只读探测"
                    ProtocolTarget.STARRING_ULTRA -> "查询 StarRing 电量"
                    ProtocolTarget.BOSE_BMAP -> "读取 Bose 型号与电量"
                    ProtocolTarget.EDIFIER_BES -> "运行 Edifier 只读探测"
                    ProtocolTarget.ROSE_BUDSFEEL -> "运行 BudsFeel 只读探测"
                    ProtocolTarget.TECHNICS_RACE -> "运行 Technics 只读探测"
                },
            )
        }
        if (target == ProtocolTarget.VIVO_TWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onHandshake, enabled = connected) { Text("握手") }
                OutlinedButton(onClick = onQueryNoise, enabled = connected) {
                    Text("读取降噪")
                }
                OutlinedButton(onClick = onQueryBattery, enabled = connected) {
                    Text("读取电量")
                }
            }
        }
    }
}

@Composable
private fun ApiStatusRow(label: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                status.startsWith("可用") -> MaterialTheme.colorScheme.primary
                status.startsWith("超时") || status.contains("拒绝") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun NoiseControlCard(
    enabled: Boolean,
    current: NoiseMode?,
    onSetNoise: (NoiseMode) -> Unit,
) {
    LabCard(
        title = "降噪写入测试",
        subtitle = "这是设置型命令。请先确认上方选择了与耳机匹配的协议变体。",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoiseMode.entries.forEach { mode ->
                if (mode == current) {
                    Button(onClick = { onSetNoise(mode) }, enabled = enabled) {
                        Text("${mode.label} · 当前")
                    }
                } else {
                    OutlinedButton(onClick = { onSetNoise(mode) }, enabled = enabled) {
                        Text(mode.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun RawCommandCard(
    target: ProtocolTarget,
    enabled: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    LabCard(
        title = "原始命令",
        subtitle = when (target) {
            ProtocolTarget.VIVO_TWS ->
                "高级诊断入口；输入完整 GAIA 帧，例如 FF 04 00 00 00 1B 02 07。"

            ProtocolTarget.STARRING_ULTRA ->
                "高级诊断入口；输入完整 StarRing 业务帧，不包含 RFCOMM 头/FCS。"

            ProtocolTarget.BOSE_BMAP ->
                "高级诊断入口；输入完整 BMAP 帧，例如电量只读请求 02 02 01 00。"

            ProtocolTarget.EDIFIER_BES ->
                "高级诊断入口；输入完整 Edifier BES 帧，例如电量查询 BB EC D0 00 00 xx。"

            ProtocolTarget.ROSE_BUDSFEEL ->
                "高级诊断入口；输入完整 BudsFeel 帧，例如状态查询 FF 00 1E FA 01 07 08 09 0C … E9 AA。"

            ProtocolTarget.TECHNICS_RACE ->
                "Technics 目标禁用原始命令发送，仅允许内置只读 GET。"
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("十六进制") },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送原始帧")
        }
    }
}

@Composable
private fun LogRow(log: ProtocolLog) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row {
                Text(
                    text = log.direction,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (log.direction) {
                        "TX" -> Color(0xFF2563EB)
                        "TX_OK" -> Color(0xFF7C3AED)
                        "RX", "FRAME", "MODE" -> Color(0xFF059669)
                        "ERR" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = log.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(log.message, style = MaterialTheme.typography.bodySmall)
            log.hex?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onAction) {
            Text(action)
        }
    }
}

@Composable
private fun HyperEarsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF8AB4F8),
            secondary = Color(0xFF86D7C4),
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22),
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF1769AA),
            secondary = Color(0xFF087F6A),
            background = Color(0xFFF4F7FB),
            surface = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
