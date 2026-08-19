package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/**
 * Concrete adapter for the Huawei FreeBuds 4.
 *
 * The FreeBuds 4 is an open-fit (non in-ear) design released in 2021. Unlike the in-ear FreeBuds 4i,
 * it has ANC but no transparency mode. It uses SPP port 1 (same as Pro 3/4).
 *
 * Evidence: User confirmed FreeBuds 4 uses SPP port 1 with ANC support (no transparency).
 */
class HuaweiFreeBuds4Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 1,
            id = "huawei-freebuds4-spp",
        ),
    )

    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract  // ANC basic (OFF/ANC only, no transparency)

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract      // No SetAncLevel support

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf(
                "huaweifreebuds4",
                "freebuds4",
            )

    override fun createProtocolSession(): ProtocolSession = HuaweiFreeBuds4ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request)

    companion object {
        const val ID = "huawei-freebuds4"
    }
}

/**
 * Session over the `5A 00` private RFCOMM SPP channel 1 for FreeBuds 4.
 *
 * The FreeBuds 4 supports two noise modes (OFF / ANC) but no transparency.
 * Device-side `2B 03` change notifications trigger a noise-state re-query.
 */
private class HuaweiFreeBuds4ProtocolSession : ProtocolSession {
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
        // SetAncLevel not supported on FreeBuds 4 (no levels)
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
                        noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF),
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
        NoiseMode.TRANSPARENCY -> error("The Huawei FreeBuds 4 has no transparency mode")
        NoiseMode.WIND -> error("The Huawei FreeBuds 4 protocol has no wind-noise mode")
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> error("The Huawei FreeBuds 4 has no transparency mode")
    }
}
