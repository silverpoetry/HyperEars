package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/**
 * Concrete adapter for the Huawei FreeBuds Pro 3.
 *
 * The adapter starts from standard capabilities (system aggregate battery, media handoff) and opens
 * private capabilities only on valid protocol evidence: component battery after a well-formed
 * battery report, noise modes after a well-formed mode report. ANC level is a model-specific
 * feature exposed through [HuaweiAncLevelFeatureState] and [HuaweiControlRequest.SetAncLevel] on
 * the v2.0.0 typed request/state transport; mode and level are independent features, both
 * confirmed by the earphone's state report (`DEVICE_REPORT`).
 */
class HuaweiFreebudsPro3Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds Pro 3"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 1,
            id = "huawei-freebuds-pro3-spp",
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
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf(
                "huaweifreebudspro3",
                "freebudspro3",
            )

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsPro3ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is HuaweiControlRequest.SetAncLevel ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    companion object {
        const val ID = "huawei-freebuds-pro3"
    }
}

/**
 * Session over the `5A 00` private RFCOMM SPP channel 1.
 *
 * The session owns the streaming decoder buffer, the on-device mode-change refresh flag, the
 * handshake confirmation and every protocol exchange; the adapter only supplies identity,
 * transport, contracts and confirmed capabilities. Writes are confirmed by re-reading `2B 2A`
 * (`DEVICE_REPORT`); the device pushes no state after a write.
 */
private class HuaweiFreebudsPro3ProtocolSession : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false
    private var pendingNoiseRefresh = false

    private val supportedLevels = HuaweiAncLevel.entries.toSet()

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
                            supported = supportedLevels,
                        ),
                    ),
                )
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                    ),
                )
                publishHandshakeIfNeeded()
                return@forEach
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        pendingNoiseRefresh = false
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
        NoiseMode.WIND -> error("The Huawei FreeBuds Pro 3 protocol has no wind-noise mode")
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
