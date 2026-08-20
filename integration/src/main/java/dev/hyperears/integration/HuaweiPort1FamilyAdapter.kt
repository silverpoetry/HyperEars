package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/**
 * Conservative family fallback adapter for unknown Huawei devices using SPP port 1.
 *
 * This adapter catches Huawei devices that don't match any specific model adapter
 * (Pro 3, Pro 4, FreeClip 2, etc.) but use the Port 1 SPP channel. It starts with
 * battery-only support and probes for ANC capabilities during handshake.
 *
 * Port 1 devices may include:
 * - Pro 3/4/5 (full ANC + levels)
 * - SE 2/4, Studio, Lace Pro 2
 * - FreeClip (original, no ANC despite Pro 3 driver)
 *
 * The adapter dynamically enables features based on protocol evidence rather than
 * assuming capabilities upfront.
 */
class HuaweiPort1FamilyAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI SPP Port 1 protocol family"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 1,
            id = "huawei-port1-family-spp",
        ),
    )

    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is HuaweiAncLevelFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is HuaweiControlRequest.SetAncLevel &&
                adapter.effectiveCapabilities().noiseControl &&
                request.level in (
                    adapter.runtimeState().features
                        .get<HuaweiAncLevelFeatureState>()
                        ?.supported
                        .orEmpty()
                ) &&
                adapter.runtimeState().noiseMode == request.level.domainNoiseMode()
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).let { name ->
                name.startsWith("huawei") || name.startsWith("freebuds") ||
                    name.startsWith("freeclip") || name.startsWith("honor")
            }

    override fun createProtocolSession(): ProtocolSession = HuaweiPort1FamilyProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is HuaweiControlRequest.SetAncLevel ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    companion object {
        const val ID = "huawei-port1-family"
    }
}

/**
 * Conservative protocol session for Port 1 family devices.
 *
 * Starts with battery-only probing. If the device responds with noise state,
 * ANC capabilities are dynamically enabled. This avoids assuming features
 * that not all Port 1 devices support (e.g., FreeClip has no ANC).
 */
private class HuaweiPort1FamilyProtocolSession : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false
    private var pendingNoiseRefresh = false
    private var ancProbed = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        HuaweiFreebudsSppCodec.queryBattery,
        HuaweiFreebudsSppCodec.queryNoiseState,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(
            HuaweiFreebudsSppCodec.queryBattery,
            HuaweiFreebudsSppCodec.queryNoiseState,
        )
        is StandardControlRequest.SetNoiseMode -> listOf(
            HuaweiFreebudsSppCodec.noiseModeCommand(request.mode.toWireMode()),
        )
        is HuaweiControlRequest.SetAncLevel -> listOf(
            HuaweiFreebudsSppCodec.noiseLevelCommand(
                request.level.domainNoiseMode().toWireMode(),
                request.level.toWireLevel(),
            ),
        )
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode,
        is HuaweiControlRequest.SetAncLevel,
        -> listOf(HuaweiFreebudsSppCodec.queryNoiseState)
        else -> emptyList()
    }

    override fun drainImmediateCommands(): List<ByteArray> {
        if (!pendingNoiseRefresh) return emptyList()
        pendingNoiseRefresh = false
        return listOf(HuaweiFreebudsSppCodec.queryNoiseState)
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (
                HuaweiFreebudsSppCodec.parseFrame(frame)?.command ==
                HuaweiFreebudsSppCodec.CMD_NOISE_CHANGE_NOTIFY
            ) {
                pendingNoiseRefresh = true
                return@forEach
            }
            HuaweiFreebudsSppCodec.parseBatteryFrame(frame)?.let { battery ->
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(battery.leftPercent, charging = false),
                                right = BatteryReading(battery.rightPercent, charging = false),
                                case = BatteryReading(battery.casePercent, charging = false),
                                overall = BatteryReading(
                                    battery.globalPercent,
                                    charging = battery.isCharging,
                                ),
                            ),
                        ),
                    ),
                )
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                publishHandshakeIfNeeded()
                return@forEach
            }
            HuaweiFreebudsSppCodec.parseNoiseState(frame)?.let { state ->
                add(
                    ProtocolEvent.FeatureStateChanged(
                        NoiseModeFeatureState(state.mode.toDomainMode()),
                    ),
                )
                add(
                    ProtocolEvent.FeatureStateChanged(
                        HuaweiAncLevelFeatureState(
                            current = state.level?.toDomainLevel(state.mode),
                            supported = HuaweiAncLevel.entries.toSet(),
                        ),
                    ),
                )
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                    ),
                )
                ancProbed = true
                publishHandshakeIfNeeded()
                return@forEach
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        pendingNoiseRefresh = false
        ancProbed = false
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireMode(): HuaweiFreebudsSppCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> HuaweiFreebudsSppCodec.NoiseMode.ANC
        NoiseMode.OFF -> HuaweiFreebudsSppCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("Port 1 family protocol has no wind-noise mode")
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private fun Int.toDomainLevel(mode: HuaweiFreebudsSppCodec.NoiseMode): HuaweiAncLevel? =
        when (mode) {
            HuaweiFreebudsSppCodec.NoiseMode.ANC -> when (this) {
                0 -> HuaweiAncLevel.NORMAL
                1 -> HuaweiAncLevel.COMFORT
                2 -> HuaweiAncLevel.ULTRA
                3 -> HuaweiAncLevel.DYNAMIC
                else -> null
            }
            HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> when (this) {
                1 -> HuaweiAncLevel.VOICE_BOOST
                2 -> HuaweiAncLevel.TRANS_NORMAL
                else -> null
            }
            HuaweiFreebudsSppCodec.NoiseMode.OFF -> null
        }
}
