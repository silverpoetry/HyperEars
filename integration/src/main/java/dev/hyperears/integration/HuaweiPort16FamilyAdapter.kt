package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/**
 * Conservative family fallback adapter for unknown Huawei devices using SPP port 16.
 *
 * This adapter catches Huawei devices that don't match any specific model adapter
 * (4i, 5i, etc.) but use the Port 16 SPP channel. It starts with battery-only
 * support and probes for basic ANC capabilities during handshake.
 *
 * Port 16 devices typically include:
 * - FreeBuds 4i (basic 3-mode ANC, no levels)
 * - FreeBuds 5i
 * - FreeLace Pro
 * - FreeBuds SE (original)
 *
 * Port 16 devices generally have more limited capabilities than Port 1 devices.
 * ANC levels are NOT assumed even if ANC mode is detected.
 */
class HuaweiPort16FamilyAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI Port 16 (Family)"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 16,
            id = "huawei-port16-family-spp",
        ),
    )

    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract  // ANC basic (three-state, no levels)

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract      // No SetAncLevel support

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).let { name ->
                name.startsWith("huawei") || name.startsWith("freebuds") ||
                    name.startsWith("honor")
            }

    override fun createProtocolSession(): ProtocolSession = HuaweiPort16FamilyProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request)

    companion object {
        const val ID = "huawei-port16-family"
    }
}

/**
 * Conservative protocol session for Port 16 family devices.
 *
 * Starts with battery-only probing. If the device responds with noise state,
 * basic 3-mode ANC is enabled (no levels). This matches the typical Port 16
 * device capabilities (4i, 5i, etc.).
 */
private class HuaweiPort16FamilyProtocolSession : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false
    private var pendingNoiseRefresh = false

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
        // SetAncLevel not supported on Port 16 family (no levels)
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode -> listOf(HuaweiFreebudsSppCodec.queryNoiseState)
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
        NoiseMode.WIND -> error("Port 16 family protocol has no wind-noise mode")
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }
}
