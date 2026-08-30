package dev.hyperears.protocoltest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hyperears.protocol.bose.BoseBmapWireCodec
import dev.hyperears.protocol.bose.BoseProductCatalog
import dev.hyperears.protocol.edifier.EdifierWireCodec
import dev.hyperears.protocol.rose.RoseBudsFeelMk2WireCodec
import dev.hyperears.protocol.starring.StarRingWireCodec
import dev.hyperears.protocol.technics.TechnicsRaceWireCodec
import dev.hyperears.protocol.vivo.VivoTwsProtocol
import dev.hyperears.protocol.vivo.VivoTwsProtocol.NoiseMode
import dev.hyperears.protocol.vivo.VivoTwsProtocol.WireConfig
import dev.hyperears.protocol.vivo.VivoFastPairIdentity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class PairedDevice(
    val name: String,
    val address: String,
    val suggestedTarget: ProtocolTarget?,
)

internal enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

internal enum class IdentityScanPhase {
    IDLE,
    SCANNING,
    FAILED,
}

private enum class ActiveTransport {
    NONE,
    RFCOMM,
    STARRING_GATT,
}

internal data class VivoIdentityDetection(
    val id: String,
    val address: String,
    val name: String?,
    val rssi: Int,
    val identity: VivoFastPairIdentity,
    val rawAdvertisement: String,
    val seenCount: Int,
    val lastSeen: String,
)

internal data class ProtocolLog(
    val id: Long,
    val time: String,
    val direction: String,
    val message: String,
    val hex: String? = null,
)

internal data class BatteryObservation(
    val leftPercent: Int?,
    val rightPercent: Int?,
    val casePercent: Int?,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
)

internal data class ProtocolUiState(
    val permissionGranted: Boolean = false,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val selectedAddress: String = "",
    val selectedName: String = "",
    val selectedTarget: ProtocolTarget = ProtocolTarget.VIVO_TWS,
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionMessage: String = "尚未连接",
    val endpoint: String? = null,
    val selectedWireConfig: WireConfig = WireConfig.AIR3_PRO_CAPTURED,
    val detectedWireConfig: WireConfig? = null,
    val battery: BatteryObservation? = null,
    val noise: VivoTwsProtocol.NoiseState? = null,
    val starRingNoise: StarRingWireCodec.NoiseMode? = null,
    val handshakeStatus: String = "未测试",
    val noiseApiStatus: String = "未测试",
    val batteryApiStatus: String = "未测试",
    val identityScanPhase: IdentityScanPhase = IdentityScanPhase.IDLE,
    val identityScanMessage: String = "尚未扫描",
    val observedAdvertisements: Int = 0,
    val observedVivoAdvertisements: Int = 0,
    val identityDetections: List<VivoIdentityDetection> = emptyList(),
    val rawCommand: String = "",
    val logs: List<ProtocolLog> = emptyList(),
)

@SuppressLint("MissingPermission")
internal class ProtocolTestViewModel(application: Application) : AndroidViewModel(application) {
    private val adapter =
        (application.getSystemService(Application.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val client = RfcommProbeClient(application)
    private val starRingGattClient = StarRingGattProbeClient(application)
    private val identityScanner = VivoIdentityScanner(application)
    private val vivoDecoder = VivoTwsProtocol.Decoder()
    private val starRingDecoder = StarRingWireCodec.Decoder()
    private val boseDecoder = BoseBmapWireCodec.Decoder()
    private val edifierDecoder = EdifierWireCodec.Decoder()
    private val roseBudsFeelDecoder = RoseBudsFeelMk2WireCodec.Decoder()
    private val technicsDecoder = TechnicsRaceWireCodec.Decoder()
    private val mutableState = MutableStateFlow(ProtocolUiState())
    val state: StateFlow<ProtocolUiState> = mutableState.asStateFlow()
    private val logId = AtomicLong()
    private val txId = AtomicLong()
    private var connectionJob: Job? = null
    private var identityScanTimeoutJob: Job? = null
    private var activeTransport = ActiveTransport.NONE

    /** File log writer — mirrors every UI log line to the app's private storage for external reading. */
    private val fileLog: java.io.FileWriter? by lazy {
        runCatching {
            val file = java.io.File(application.filesDir, "probe.log")
            java.io.FileWriter(file, true).also { writer ->
                writer.write("\n=== HyperEars Protocol Lab Session ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
                writer.flush()
            }
        }.getOrNull()
    }

    init {
        viewModelScope.launch {
            client.events.collect(::handleClientEvent)
        }
        viewModelScope.launch {
            starRingGattClient.events.collect(::handleStarRingGattEvent)
        }
        viewModelScope.launch {
            identityScanner.events.collect(::handleIdentityScanEvent)
        }
        updatePermissionState()
    }

    fun updatePermissionState() {
        val granted = hasBluetoothPermission()
        mutableState.value = mutableState.value.copy(permissionGranted = granted)
        if (granted) refreshPairedDevices()
    }

    fun refreshPairedDevices() {
        if (!hasBluetoothPermission()) {
            mutableState.value = mutableState.value.copy(
                permissionGranted = false,
                connectionMessage = "需要附近设备权限",
            )
            return
        }
        val devices = runCatching {
            adapter?.bondedDevices.orEmpty()
                .map { device ->
                    val name = device.name?.takeIf { it.isNotBlank() } ?: "未命名蓝牙设备"
                    PairedDevice(
                        name = name,
                        address = device.address,
                        suggestedTarget = ProtocolTarget.fromDevice(name, device.address),
                    )
                }
                .sortedWith(
                    compareByDescending<PairedDevice> { it.suggestedTarget != null }
                        .thenBy { it.name },
                )
        }.getOrElse {
            addLog("ERR", "读取已配对设备失败：${it.conciseMessage()}")
            emptyList()
        }

        val current = mutableState.value
        val selected = devices.firstOrNull { it.address == current.selectedAddress }
            ?: devices.firstOrNull { it.suggestedTarget != null }
        mutableState.value = current.copy(
            permissionGranted = true,
            pairedDevices = devices,
            selectedAddress = selected?.address ?: current.selectedAddress,
            selectedName = selected?.name ?: current.selectedName,
            selectedTarget = selected?.suggestedTarget ?: current.selectedTarget,
            selectedWireConfig = selected?.suggestedVivoWireConfig() ?: current.selectedWireConfig,
        )
        addLog(
            "SYS",
            "发现 ${devices.size} 个已配对设备，其中 " +
                "${devices.count { it.suggestedTarget != null }} 个命中已知实验协议",
        )
    }

    fun selectDevice(device: PairedDevice) {
        if (mutableState.value.phase != ConnectionPhase.DISCONNECTED) disconnect()
        mutableState.value = mutableState.value.copy(
            selectedAddress = device.address,
            selectedName = device.name,
            selectedTarget = device.suggestedTarget ?: mutableState.value.selectedTarget,
            selectedWireConfig = device.suggestedVivoWireConfig(),
            battery = null,
            noise = null,
            starRingNoise = null,
            handshakeStatus = "未测试",
            noiseApiStatus = "未测试",
            batteryApiStatus = "未测试",
        )
    }

    fun updateAddress(address: String) {
        val paired = mutableState.value.pairedDevices
            .firstOrNull { it.address.equals(address.trim(), ignoreCase = true) }
        mutableState.value = mutableState.value.copy(
            selectedAddress = address.trim().uppercase(Locale.US),
            selectedName = paired?.name.orEmpty(),
            selectedTarget = paired?.suggestedTarget ?: mutableState.value.selectedTarget,
            selectedWireConfig = paired?.suggestedVivoWireConfig()
                ?: mutableState.value.selectedWireConfig,
        )
    }

    fun selectTarget(target: ProtocolTarget) {
        if (mutableState.value.phase != ConnectionPhase.DISCONNECTED) disconnect()
        mutableState.value = mutableState.value.copy(
            selectedTarget = target,
            battery = null,
            noise = null,
            starRingNoise = null,
            handshakeStatus = "未测试",
            noiseApiStatus = "未测试",
            batteryApiStatus = "未测试",
        )
        addLog("SYS", "选择实验协议：${target.label}")
    }

    fun selectWireConfig(wireConfig: WireConfig) {
        mutableState.value = mutableState.value.copy(selectedWireConfig = wireConfig)
        addLog("SYS", "选择线格式：${wireConfig.label}")
    }

    fun updateRawCommand(value: String) {
        mutableState.value = mutableState.value.copy(rawCommand = value)
    }

    fun connect() {
        if (!hasBluetoothPermission()) {
            updatePermissionState()
            return
        }
        val address = mutableState.value.selectedAddress.trim()
        if (!MAC_ADDRESS.matches(address)) {
            mutableState.value = mutableState.value.copy(
                phase = ConnectionPhase.FAILED,
                connectionMessage = "请输入有效的蓝牙 MAC 地址",
            )
            return
        }

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            client.close()
            starRingGattClient.close()
            activeTransport = ActiveTransport.NONE
            vivoDecoder.reset()
            starRingDecoder.reset()
            boseDecoder.reset()
            edifierDecoder.reset()
            technicsDecoder.reset()
            val target = mutableState.value.selectedTarget
            mutableState.value = mutableState.value.copy(
                phase = ConnectionPhase.CONNECTING,
                connectionMessage = if (target == ProtocolTarget.STARRING_ULTRA) {
                    "正在连接官方 BLE GATT 通道…"
                } else {
                    "正在探测 ${target.label} RFCOMM 入口…"
                },
                endpoint = null,
                detectedWireConfig = null,
                battery = null,
                noise = null,
                starRingNoise = null,
                handshakeStatus = "未测试",
                noiseApiStatus = "未测试",
                batteryApiStatus = "未测试",
            )
            runCatching {
                if (target == ProtocolTarget.STARRING_ULTRA) {
                    starRingGattClient.connect(address).label
                } else {
                    client.connect(address, target.endpoints).label
                }
            }
                .onSuccess { endpointLabel ->
                    activeTransport = if (target == ProtocolTarget.STARRING_ULTRA) {
                        ActiveTransport.STARRING_GATT
                    } else {
                        ActiveTransport.RFCOMM
                    }
                    mutableState.value = mutableState.value.copy(
                        phase = ConnectionPhase.CONNECTED,
                        connectionMessage = if (target == ProtocolTarget.STARRING_ULTRA) {
                            "官方 BLE GATT 已连接"
                        } else {
                            "RFCOMM 已连接"
                        },
                        endpoint = endpointLabel,
                    )
                    runReadOnlyProbe()
                }
                .onFailure { failure ->
                    if (mutableState.value.phase == ConnectionPhase.CONNECTING) {
                        mutableState.value = mutableState.value.copy(
                            phase = ConnectionPhase.FAILED,
                            connectionMessage = failure.conciseMessage(),
                        )
                        addLog("ERR", "连接失败：${failure.conciseMessage()}")
                    }
                }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        activeTransport = ActiveTransport.NONE
        client.close()
        starRingGattClient.close()
        mutableState.value = mutableState.value.copy(
            phase = ConnectionPhase.DISCONNECTED,
            connectionMessage = "已断开",
            endpoint = null,
        )
    }

    fun runReadOnlyProbe() {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            when (mutableState.value.selectedTarget) {
                ProtocolTarget.VIVO_TWS -> runVivoReadOnlyProbe()
                ProtocolTarget.STARRING_ULTRA -> {
                    mutableState.value = mutableState.value.copy(
                        handshakeStatus = "不适用",
                        noiseApiStatus = "未测试",
                        batteryApiStatus = "等待响应",
                    )
                    send(StarRingWireCodec.queryBattery, "StarRing 查询左右耳/充电盒电量")
                }
                ProtocolTarget.BOSE_BMAP -> {
                    mutableState.value = mutableState.value.copy(
                        handshakeStatus = "等待产品 ID",
                        noiseApiStatus = "不适用",
                        batteryApiStatus = "等待响应",
                    )
                    send(BoseBmapWireCodec.queryProductIdentity, "Bose 查询产品 ID/变体")
                    delay(PROBE_GAP_MS)
                    send(BoseBmapWireCodec.queryBattery, "Bose 查询组件电量")
                }
                ProtocolTarget.EDIFIER_BES -> {
                    mutableState.value = mutableState.value.copy(
                        handshakeStatus = "等待设备功能响应",
                        noiseApiStatus = "等待响应",
                        batteryApiStatus = "等待响应",
                    )
                    send(EdifierWireCodec.queryBattery, "Edifier 查询电量")
                    delay(PROBE_GAP_MS)
                    send(EdifierWireCodec.queryAnc, "Edifier 查询降噪状态")
                    delay(PROBE_GAP_MS)
                    send(EdifierWireCodec.queryFunction, "Edifier 查询设备功能")
                }
                ProtocolTarget.ROSE_BUDSFEEL -> {
                    mutableState.value = mutableState.value.copy(
                        handshakeStatus = "等待响应",
                        noiseApiStatus = "等待响应",
                        batteryApiStatus = "等待响应",
                    )
                    send(RoseBudsFeelMk2WireCodec.queryStatus(0), "BudsFeel 状态查询")
                    delay(PROBE_GAP_MS)
                    send(RoseBudsFeelMk2WireCodec.queryStatus(1), "BudsFeel 状态查询（重试）")
                }
                ProtocolTarget.TECHNICS_RACE -> {
                    mutableState.value = mutableState.value.copy(
                        handshakeStatus = "等待协议响应",
                        noiseApiStatus = "等待响应",
                        batteryApiStatus = "等待响应",
                    )
                    send(TechnicsRaceWireCodec.queryAgentBattery, "Technics 查询右耳电量（Agent）")
                    delay(PROBE_GAP_MS)
                    send(TechnicsRaceWireCodec.queryClientBattery, "Technics 查询左耳电量（Client）")
                    delay(PROBE_GAP_MS)
                    send(TechnicsRaceWireCodec.queryCaseBattery, "Technics 查询充电盒电量")
                    delay(PROBE_GAP_MS)
                    send(TechnicsRaceWireCodec.queryOutsideControl, "Technics 查询外部控制状态")
                }
            }
            markTimeoutsLater()
        }
    }

    private suspend fun runVivoReadOnlyProbe() {
        mutableState.value = mutableState.value.copy(
            handshakeStatus = "等待响应",
            noiseApiStatus = "等待响应",
            batteryApiStatus = "等待响应",
        )
        send(VivoTwsProtocol.handshake(), "v4 握手")
        delay(PROBE_GAP_MS)
        send(
            VivoTwsProtocol.queryNoiseMode(WireConfig.AIR3_PRO_CAPTURED),
            "查询降噪（Air3 Pro v3）",
        )
        delay(PROBE_GAP_MS)
        send(
            VivoTwsProtocol.queryNoiseMode(WireConfig.FAMILY_DEFAULT_V4),
            "查询降噪（公开资料 v4）",
        )
        delay(PROBE_GAP_MS)
        send(VivoTwsProtocol.queryBattery(), "查询左右耳/充电盒电量")
    }

    fun sendHandshake() {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            if (!ensureVivoTarget()) return@launch
            mutableState.value = mutableState.value.copy(handshakeStatus = "等待响应")
            send(VivoTwsProtocol.handshake(), "v4 握手")
            markTimeoutsLater()
        }
    }

    fun queryNoise() {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            if (!ensureVivoTarget()) return@launch
            val wireConfig = mutableState.value.selectedWireConfig
            mutableState.value = mutableState.value.copy(noiseApiStatus = "等待响应")
            send(VivoTwsProtocol.queryNoiseMode(wireConfig), "查询降噪（${wireConfig.label}）")
            markTimeoutsLater()
        }
    }

    fun queryBattery() {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            mutableState.value = mutableState.value.copy(batteryApiStatus = "等待响应")
            when (mutableState.value.selectedTarget) {
                ProtocolTarget.VIVO_TWS ->
                    send(VivoTwsProtocol.queryBattery(), "查询左右耳/充电盒电量")

                ProtocolTarget.STARRING_ULTRA ->
                    send(StarRingWireCodec.queryBattery, "StarRing 查询左右耳/充电盒电量")

                ProtocolTarget.BOSE_BMAP ->
                    send(BoseBmapWireCodec.queryBattery, "Bose 查询组件电量")

                ProtocolTarget.EDIFIER_BES ->
                    send(EdifierWireCodec.queryBattery, "Edifier 查询电量")

                ProtocolTarget.ROSE_BUDSFEEL ->
                    send(RoseBudsFeelMk2WireCodec.queryStatus(0), "BudsFeel 状态查询")

                ProtocolTarget.TECHNICS_RACE -> {
                    send(TechnicsRaceWireCodec.queryAgentBattery, "Technics 查询右耳电量（Agent）")
                    delay(PROBE_GAP_MS)
                    send(TechnicsRaceWireCodec.queryClientBattery, "Technics 查询左耳电量（Client）")
                    delay(PROBE_GAP_MS)
                    send(TechnicsRaceWireCodec.queryCaseBattery, "Technics 查询充电盒电量")
                }
            }
            markTimeoutsLater()
        }
    }

    fun setNoiseMode(mode: NoiseMode) {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            if (!ensureVivoTarget()) return@launch
            val wireConfig = mutableState.value.selectedWireConfig
            mutableState.value = mutableState.value.copy(noiseApiStatus = "等待设置确认")
            send(
                VivoTwsProtocol.setNoiseMode(mode, wireConfig),
                "设置${mode.label}（${wireConfig.label}）",
            )
            markTimeoutsLater()
        }
    }

    fun setStarRingNoiseMode(mode: StarRingWireCodec.NoiseMode) {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            if (!ensureStarRingTarget()) return@launch
            mutableState.value = mutableState.value.copy(
                noiseApiStatus = "等待耳机主动回报",
            )
            send(
                StarRingWireCodec.setNoiseMode(mode),
                "StarRing 单帧设置${mode.label}",
            )
            markTimeoutsLater()
        }
    }

    fun sendRaw() {
        viewModelScope.launch {
            if (!ensureConnected()) return@launch
            if (mutableState.value.selectedTarget == ProtocolTarget.TECHNICS_RACE) {
                addLog("ERR", "Technics 实验目标仅允许只读探测")
                return@launch
            }
            val raw = parseHex(mutableState.value.rawCommand)
            if (raw == null || raw.isEmpty()) {
                addLog("ERR", "原始命令不是有效的十六进制字节")
                return@launch
            }
            send(raw, "发送原始命令")
        }
    }

    fun clearLogs() {
        mutableState.value = mutableState.value.copy(logs = emptyList())
    }

    fun startIdentityScan() {
        if (!hasBluetoothPermission()) {
            updatePermissionState()
            return
        }
        identityScanTimeoutJob?.cancel()
        mutableState.value = mutableState.value.copy(
            identityScanPhase = IdentityScanPhase.SCANNING,
            identityScanMessage = "正在启动 BLE 扫描…",
            observedAdvertisements = 0,
            observedVivoAdvertisements = 0,
            identityDetections = emptyList(),
        )
        runCatching { identityScanner.start() }
            .onFailure { failure ->
                mutableState.value = mutableState.value.copy(
                    identityScanPhase = IdentityScanPhase.FAILED,
                    identityScanMessage = failure.conciseMessage(),
                )
                addLog("ERR", "vivo 判型扫描启动失败：${failure.conciseMessage()}")
                return
            }
        identityScanTimeoutJob = viewModelScope.launch {
            delay(IDENTITY_SCAN_DURATION_MS)
            identityScanner.stop()
        }
    }

    fun stopIdentityScan() {
        identityScanTimeoutJob?.cancel()
        identityScanTimeoutJob = null
        identityScanner.stop()
    }

    private suspend fun send(packet: ByteArray, message: String) {
        val sequence = txId.incrementAndGet()
        addLog("TX", "#$sequence $message", packet.hexBytes())
        runCatching {
            when (activeTransport) {
                ActiveTransport.RFCOMM -> client.send(packet)
                ActiveTransport.STARRING_GATT -> starRingGattClient.send(packet)
                ActiveTransport.NONE -> error("没有可用的协议传输")
            }
        }
            .onSuccess {
                val transport = when (activeTransport) {
                    ActiveTransport.RFCOMM -> "RFCOMM write + flush"
                    ActiveTransport.STARRING_GATT -> "BLE GATT Write Request"
                    ActiveTransport.NONE -> "未知传输"
                }
                addLog("TX_OK", "#$sequence $transport 完成，共 ${packet.size} 字节")
            }
            .onFailure {
                addLog("ERR", "#$sequence 发送失败：${it.conciseMessage()}")
                mutableState.value = mutableState.value.copy(
                    phase = ConnectionPhase.FAILED,
                    connectionMessage = it.conciseMessage(),
                )
            }
    }

    private fun handleClientEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.Attempt ->
                addLog("CONN", "尝试 ${event.endpoint.label}")

            is ClientEvent.AttemptFailed ->
                addLog("CONN", "${event.endpoint.label} 失败：${event.reason}")

            is ClientEvent.Connected ->
                addLog("CONN", "已连接 ${event.endpoint.label}")

            is ClientEvent.Incoming ->
                handleIncoming(event.bytes)

            is ClientEvent.Disconnected -> {
                addLog("CONN", event.reason)
                if (mutableState.value.phase == ConnectionPhase.CONNECTED) {
                    mutableState.value = mutableState.value.copy(
                        phase = ConnectionPhase.DISCONNECTED,
                        connectionMessage = event.reason,
                        endpoint = null,
                    )
                }
            }
        }
    }

    private fun handleStarRingGattEvent(event: StarRingGattEvent) {
        when (event) {
            is StarRingGattEvent.Services ->
                addLog("GATT", "服务发现完成", event.summary)

            is StarRingGattEvent.Incoming ->
                handleIncoming(event.bytes)

            is StarRingGattEvent.Disconnected -> {
                addLog("GATT", event.reason)
                if (
                    activeTransport == ActiveTransport.STARRING_GATT &&
                    mutableState.value.phase == ConnectionPhase.CONNECTED
                ) {
                    activeTransport = ActiveTransport.NONE
                    mutableState.value = mutableState.value.copy(
                        phase = ConnectionPhase.DISCONNECTED,
                        connectionMessage = event.reason,
                        endpoint = null,
                    )
                }
            }
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        addLog("RX", "收到 ${bytes.size} 字节", bytes.hexBytes())
        when (mutableState.value.selectedTarget) {
            ProtocolTarget.VIVO_TWS -> handleVivoIncoming(bytes)
            ProtocolTarget.STARRING_ULTRA -> handleStarRingIncoming(bytes)
            ProtocolTarget.BOSE_BMAP -> handleBoseIncoming(bytes)
            ProtocolTarget.EDIFIER_BES -> handleEdifierIncoming(bytes)
            ProtocolTarget.ROSE_BUDSFEEL -> handleRoseBudsFeelIncoming(bytes)
            ProtocolTarget.TECHNICS_RACE -> handleTechnicsIncoming(bytes)
        }
    }

    private fun handleVivoIncoming(bytes: ByteArray) {
        vivoDecoder.offer(bytes).forEach { frame ->
            addLog(
                "FRAME",
                "v${frame.version} vendor=0x${frame.vendor.hex4()} cmd=0x${frame.command.hex4()} payload=${frame.payload.size}",
                VivoTwsProtocol.run { frame.payload.hex() },
            )
            VivoTwsProtocol.parseHandshakeState(frame)?.let { handshake ->
                mutableState.value = mutableState.value.copy(
                    handshakeStatus = if (handshake.accepted) "可用 · 响应 v${handshake.version}" else "耳机拒绝",
                )
            }
            VivoTwsProtocol.parseNoiseState(frame)?.let { noise ->
                val inferred = inferWireConfig(noise)
                mutableState.value = mutableState.value.copy(
                    noise = noise,
                    detectedWireConfig = inferred ?: mutableState.value.detectedWireConfig,
                    noiseApiStatus = "可用 · ${noise.mode.label} · 响应 v${noise.version}",
                )
            }
            VivoTwsProtocol.parseBatteryState(frame)?.let { battery ->
                mutableState.value = mutableState.value.copy(
                    battery = BatteryObservation(
                        leftPercent = battery.leftPercent,
                        rightPercent = battery.rightPercent,
                        casePercent = battery.casePercent,
                        leftCharging = battery.leftCharging,
                        rightCharging = battery.rightCharging,
                        caseCharging = battery.caseCharging,
                    ),
                    batteryApiStatus = "可用 · 响应 v${battery.version}",
                )
            }
        }
    }

    private fun handleStarRingIncoming(bytes: ByteArray) {
        starRingDecoder.offer(bytes).forEach { frame ->
            addLog(
                "FRAME",
                "StarRing group=0x${frame.group.hex2()} cmd=0x${frame.command.hex2()} " +
                    "payload=${frame.payload.size}",
                StarRingWireCodec.run { frame.bytes.hex() },
            )
            StarRingWireCodec.parseBatteryState(frame)?.let { battery ->
                mutableState.value = mutableState.value.copy(
                    battery = BatteryObservation(
                        leftPercent = battery.leftPercent,
                        rightPercent = battery.rightPercent,
                        casePercent = battery.casePercent,
                    ),
                    batteryApiStatus = "可用 · StarRing 私有协议响应",
                )
                addLog(
                    "BAT",
                    "左=${battery.leftPercent ?: "—"}% 右=${battery.rightPercent ?: "—"}% " +
                        "盒=${battery.casePercent ?: "—"}%",
                    StarRingWireCodec.run { battery.rawPayload.hex() },
                )
            }
            StarRingWireCodec.parseNoiseState(frame)?.let { noise ->
                mutableState.value = mutableState.value.copy(
                    starRingNoise = noise.mode,
                    noiseApiStatus = "可用 · ${noise.mode.label} · 耳机主动回报",
                )
                addLog(
                    "MODE",
                    "StarRing 当前模式=${noise.mode.label}",
                    StarRingWireCodec.run { noise.rawPayload.hex() },
                )
            }
        }
    }

    private fun handleBoseIncoming(bytes: ByteArray) {
        boseDecoder.offer(bytes).forEach { frame ->
            addLog(
                "FRAME",
                "BMAP [${frame.functionBlock}.${frame.function}] " +
                    "op=${frame.operator?.name ?: "0x${frame.flags.hex2()}"} " +
                    "payload=${frame.payload.size}",
                BoseBmapWireCodec.run { frame.bytes.hex() },
            )
            BoseBmapWireCodec.parseProductIdentity(frame)?.let { identity ->
                val model = BoseProductCatalog.find(identity.productId)
                    ?.let { "${it.displayName} / ${it.codename}" }
                    ?: "未登记 Bose 型号"
                mutableState.value = mutableState.value.copy(
                    handshakeStatus =
                        "可用 · product=0x${identity.productId.hex4()} · variant=${identity.variant}",
                )
                addLog(
                    "ID",
                    "$model · product=0x${identity.productId.hex4()} variant=${identity.variant}",
                )
            }
            BoseBmapWireCodec.parseBatteryState(frame)?.let { battery ->
                val overall = battery.overallPercent
                mutableState.value = mutableState.value.copy(
                    battery = BatteryObservation(
                        leftPercent = battery.leftPercent ?: overall,
                        rightPercent = battery.rightPercent ?: overall,
                        casePercent = battery.casePercent,
                    ),
                    batteryApiStatus = "可用 · Bose BMAP [2.2] 响应",
                )
                addLog(
                    "BAT",
                    "总=${overall ?: "—"}% 左=${battery.leftPercent ?: "—"}% " +
                        "右=${battery.rightPercent ?: "—"}% 盒=${battery.casePercent ?: "—"}%",
                    BoseBmapWireCodec.run { battery.rawPayload.hex() },
                )
            }
        }
    }

    private fun handleEdifierIncoming(bytes: ByteArray) {
        edifierDecoder.offer(bytes).forEach { frame ->
            addLog(
                "FRAME",
                "Edifier header=0x${frame.header.hex2()} appCode=0x${frame.appCode.hex2()} " +
                    "cmd=0x${frame.commandIndex.hex2()} payload=${frame.payload.size}",
                EdifierWireCodec.run { frame.bytes.hex() },
            )
            EdifierWireCodec.parseBatteryState(frame)?.let { battery ->
                val observation: BatteryObservation
                val summary: String
                when (battery) {
                    is EdifierWireCodec.BatteryState.Aggregate -> {
                        observation = BatteryObservation(
                            leftPercent = battery.percent,
                            rightPercent = battery.percent,
                            casePercent = null,
                        )
                        summary = "整机=${battery.percent}%"
                    }

                    is EdifierWireCodec.BatteryState.TwsComponents -> {
                        observation = BatteryObservation(
                            leftPercent = battery.leftPercent,
                            rightPercent = battery.rightPercent,
                            casePercent = null,
                        )
                        summary = "左=${battery.leftPercent}% 右=${battery.rightPercent}% 盒=—"
                    }
                }
                mutableState.value = mutableState.value.copy(
                    battery = observation,
                    batteryApiStatus = "可用 · Edifier 响应",
                )
                addLog("BAT", summary)
            }
            EdifierWireCodec.parseAncState(frame)?.let { anc ->
                val modeLabel = when (anc.mode) {
                    EdifierWireCodec.ANC_INDEX -> when (anc.level) {
                        EdifierWireCodec.ANC_VALUE_DEEP -> "深度降噪"
                        EdifierWireCodec.ANC_VALUE_COMFORT -> "舒适降噪"
                        EdifierWireCodec.ANC_VALUE_WIND -> "防风噪"
                        EdifierWireCodec.ANC_VALUE_AMBIENT -> "环境声"
                        EdifierWireCodec.ANC_VALUE_OFF -> "降噪关"
                        else -> "未知值(${anc.level})"
                    }
                    else -> "未知index(${anc.mode})"
                }
                mutableState.value = mutableState.value.copy(
                    noiseApiStatus = "可用 · $modeLabel",
                )
                addLog("ANC", "ancIndex=${anc.mode} ancValue=${anc.level} → $modeLabel")
            }
            // D8 function query response
            if (frame.commandIndex == EdifierWireCodec.CMD_FUNCTION_QUERY && frame.payload.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(
                    handshakeStatus = "可用 · D8 功能响应 ${frame.payload.size} 字节",
                )
                addLog("FUNC", "设备功能响应: ${frame.payload.size} 字节", EdifierWireCodec.run { frame.payload.hex() })
            }
        }
    }

    private fun handleRoseBudsFeelIncoming(bytes: ByteArray) {
        roseBudsFeelDecoder.offer(bytes).forEach { state ->
            when (state) {
                is RoseBudsFeelMk2WireCodec.State.Battery -> {
                    mutableState.value = mutableState.value.copy(
                        battery = BatteryObservation(
                            leftPercent = state.leftPercent,
                            rightPercent = state.rightPercent,
                            casePercent = state.casePercent,
                        ),
                        batteryApiStatus = "可用 · BudsFeel 响应",
                    )
                    addLog(
                        "BAT",
                        "左耳=${state.leftPercent ?: "—"}% 右耳=${state.rightPercent ?: "—"}% " +
                            "充电盒=${state.casePercent ?: "—"}%",
                    )
                }

                is RoseBudsFeelMk2WireCodec.State.Noise -> {
                    val modeLabel = when (state.mode) {
                        RoseBudsFeelMk2WireCodec.NoiseMode.ANC -> "降噪"
                        RoseBudsFeelMk2WireCodec.NoiseMode.ADAPTIVE_ANC -> "自适应降噪"
                        RoseBudsFeelMk2WireCodec.NoiseMode.EXTREME_ANC -> "极致降噪"
                        RoseBudsFeelMk2WireCodec.NoiseMode.OFF -> "关闭"
                        RoseBudsFeelMk2WireCodec.NoiseMode.TRANSPARENCY -> "通透"
                        RoseBudsFeelMk2WireCodec.NoiseMode.WIND -> "抗风噪"
                    }
                    mutableState.value = mutableState.value.copy(
                        noiseApiStatus = "可用 · $modeLabel",
                    )
                    addLog("ANC", "噪声模式=$modeLabel (value=${state.mode.value})")
                }
            }
        }
    }

    private fun handleTechnicsIncoming(bytes: ByteArray) {
        technicsDecoder.offer(bytes).forEach { frame ->
            addLog(
                "FRAME",
                "Technics RACE type=${frame.type.name} id=0x${frame.raceId.hex4()} " +
                    "payload=${frame.payload.size}",
                frame.bytes.hexBytes(),
            )
            var recognized = false
            TechnicsRaceWireCodec.parseBattery(frame)?.let { reading ->
                recognized = true
                val current = mutableState.value.battery
                    ?: BatteryObservation(null, null, null)
                val battery = when (reading.component) {
                    TechnicsRaceWireCodec.BatteryComponent.LEFT ->
                        current.copy(leftPercent = reading.percent)

                    TechnicsRaceWireCodec.BatteryComponent.RIGHT ->
                        current.copy(rightPercent = reading.percent)

                    TechnicsRaceWireCodec.BatteryComponent.CASE ->
                        current.copy(casePercent = reading.percent)
                }
                mutableState.value = mutableState.value.copy(
                    battery = battery,
                    batteryApiStatus = "可用 · Technics RACE 响应",
                )
                addLog(
                    "BAT",
                    "左=${battery.leftPercent ?: "—"}% 右=${battery.rightPercent ?: "—"}% " +
                        "盒=${battery.casePercent ?: "—"}%",
                )
            }
            TechnicsRaceWireCodec.parseOutsideControl(frame)?.let { outside ->
                recognized = true
                val mode = when (outside.mode) {
                    TechnicsRaceWireCodec.OutsideControlMode.OFF -> "关闭"
                    TechnicsRaceWireCodec.OutsideControlMode.ANC -> "降噪"
                    TechnicsRaceWireCodec.OutsideControlMode.TRANSPARENCY -> "通透"
                }
                mutableState.value = mutableState.value.copy(
                    noiseApiStatus = "可用 · $mode",
                )
                addLog(
                    "ANC",
                    "外部控制=$mode 降噪等级=${outside.noiseCancelLevel} " +
                        "通透等级=${outside.ambientLevel}",
                )
            }
            if (recognized) {
                mutableState.value = mutableState.value.copy(
                    handshakeStatus = "可用 · Technics RACE 响应",
                )
            }
        }
    }

    private fun handleIdentityScanEvent(event: IdentityScanEvent) {
        when (event) {
            IdentityScanEvent.Started -> {
                mutableState.value = mutableState.value.copy(
                    identityScanPhase = IdentityScanPhase.SCANNING,
                    identityScanMessage = "扫描中 · 请开合充电盒或让耳机进入可发现状态",
                )
                addLog("BLE", "开始 20 秒 vivo 广播判型扫描")
            }

            is IdentityScanEvent.Detection -> {
                val key = listOf(
                    event.address,
                    event.identity.uuid,
                    event.identity.modelId,
                    event.identity.layout,
                ).joinToString("|")
                val current = mutableState.value
                val previous = current.identityDetections.firstOrNull { it.id == key }
                val detection = VivoIdentityDetection(
                    id = key,
                    address = event.address,
                    name = event.name,
                    rssi = event.rssi,
                    identity = event.identity,
                    rawAdvertisement = event.rawAdvertisement.hexBytes(),
                    seenCount = (previous?.seenCount ?: 0) + 1,
                    lastSeen = TIME_FORMAT.format(Date()),
                )
                mutableState.value = current.copy(
                    identityScanMessage =
                        "已命中 vivo 官方广播结构 · model=${event.identity.modelId}",
                    observedAdvertisements = event.observedAdvertisements,
                    observedVivoAdvertisements = event.observedVivoAdvertisements,
                    identityDetections = (
                        listOf(detection) +
                            current.identityDetections.filterNot { it.id == key }
                        ).take(MAX_IDENTITY_DETECTIONS),
                )
                if (previous == null) {
                    addLog(
                        "BLE",
                        "命中 vivo 型号：${event.address} · ${event.identity.uuidLabel} · " +
                            "${event.identity.layout.label} · model=${event.identity.modelId}",
                        detection.rawAdvertisement,
                    )
                }
            }

            is IdentityScanEvent.Stopped -> {
                identityScanTimeoutJob?.cancel()
                identityScanTimeoutJob = null
                val current = mutableState.value
                mutableState.value = current.copy(
                    identityScanPhase = IdentityScanPhase.IDLE,
                    identityScanMessage = if (event.observedVivoAdvertisements > 0) {
                        "扫描完成 · 捕获 ${event.observedVivoAdvertisements} 条 vivo 广播"
                    } else {
                        "扫描完成 · 未捕获 vivo 官方广播结构"
                    },
                    observedAdvertisements = event.observedAdvertisements,
                    observedVivoAdvertisements = event.observedVivoAdvertisements,
                )
                addLog(
                    "BLE",
                    "判型扫描结束：共 ${event.observedAdvertisements} 条广播，" +
                        "vivo ${event.observedVivoAdvertisements} 条",
                )
            }

            is IdentityScanEvent.Failed -> {
                identityScanTimeoutJob?.cancel()
                identityScanTimeoutJob = null
                mutableState.value = mutableState.value.copy(
                    identityScanPhase = IdentityScanPhase.FAILED,
                    identityScanMessage = event.reason,
                    observedAdvertisements = event.observedAdvertisements,
                    observedVivoAdvertisements = event.observedVivoAdvertisements,
                )
                addLog("ERR", event.reason)
            }
        }
    }

    private fun inferWireConfig(noise: VivoTwsProtocol.NoiseState): WireConfig? = when {
        noise.noiseEffect == 4 && noise.transparencyEffect == 0 ->
            WireConfig.AIR3_PRO_CAPTURED
        noise.noiseEffect == 3 && noise.transparencyEffect == 1 ->
            WireConfig.FAMILY_DEFAULT_V4
        else -> null
    }

    private fun PairedDevice.suggestedVivoWireConfig(): WireConfig {
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return when (normalized) {
            "vivotws3e" -> WireConfig.TWS_3E_V3
            else -> WireConfig.AIR3_PRO_CAPTURED
        }
    }

    private fun markTimeoutsLater() {
        viewModelScope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            val current = mutableState.value
            mutableState.value = current.copy(
                handshakeStatus = current.handshakeStatus.timeoutIfWaiting(),
                noiseApiStatus = current.noiseApiStatus.timeoutIfWaiting(),
                batteryApiStatus = current.batteryApiStatus.timeoutIfWaiting(),
            )
        }
    }

    private fun String.timeoutIfWaiting(): String =
        if (startsWith("等待")) "超时/未观察到响应" else this

    private fun ensureConnected(): Boolean {
        if (mutableState.value.phase == ConnectionPhase.CONNECTED) return true
        addLog("ERR", "请先连接耳机协议通道")
        return false
    }

    private fun ensureVivoTarget(): Boolean {
        if (mutableState.value.selectedTarget == ProtocolTarget.VIVO_TWS) return true
        addLog("ERR", "当前连接不是 vivo TWS 协议")
        return false
    }

    private fun ensureStarRingTarget(): Boolean {
        if (mutableState.value.selectedTarget == ProtocolTarget.STARRING_ULTRA) return true
        addLog("ERR", "当前连接不是 StarRing Ultra 协议")
        return false
    }

    private fun addLog(direction: String, message: String, hex: String? = null) {
        val entry = ProtocolLog(
            id = logId.incrementAndGet(),
            time = TIME_FORMAT.format(Date()),
            direction = direction,
            message = message,
            hex = hex,
        )
        val line = "[$direction] $message${hex?.let { " | $it" }.orEmpty()}"
        Log.d(TAG, line)
        // Mirror to private file for external reading via run-as
        runCatching {
            fileLog?.appendLine("${entry.time} $line")
            fileLog?.flush()
        }
        mutableState.value = mutableState.value.copy(
            logs = (listOf(entry) + mutableState.value.logs).take(MAX_LOGS),
        )
    }

    private fun hasBluetoothPermission(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(getApplication(), it) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun parseHex(value: String): ByteArray? {
        val compact = value.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (compact.isEmpty() || compact.length % 2 != 0 || compact.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        return runCatching {
            ByteArray(compact.length / 2) { index ->
                compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private fun Throwable.conciseMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun Int.hex4(): String = toString(16).uppercase(Locale.US).padStart(4, '0')

    private fun Int.hex2(): String = toString(16).uppercase(Locale.US).padStart(2, '0')

    private fun ByteArray.hexBytes(): String =
        joinToString(" ") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }

    override fun onCleared() {
        identityScanTimeoutJob?.cancel()
        identityScanner.close()
        client.destroy()
        starRingGattClient.destroy()
        super.onCleared()
    }

    private companion object {
        const val TAG = "HyperEarsProtocol"
        const val PROBE_GAP_MS = 300L
        const val RESPONSE_TIMEOUT_MS = 2_800L
        const val IDENTITY_SCAN_DURATION_MS = 20_000L
        const val MAX_LOGS = 250
        const val MAX_IDENTITY_DETECTIONS = 20
        val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
