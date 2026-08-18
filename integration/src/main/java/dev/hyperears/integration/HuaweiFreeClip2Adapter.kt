package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/**
 * Concrete adapter for the Huawei FreeClip 2.
 *
 * The FreeClip 2 is an open-ear clip-on design with no active noise cancellation.
 * It shares the same SPP channel 1 protocol as the FreeBuds Pro 3 for battery telemetry,
 * but does not support noise-mode control or ANC-level switching.
 *
 * Evidence: OpenFreebuds OfbDriverHuaweiFreeClip2 (port 1, no ANC handler).
 */
class HuaweiFreeClip2Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeClip 2"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 1,
            id = "huawei-freeclip2-spp",
        ),
    )

    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract  // No ANC level feature

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract      // No SetAncLevel support

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf(
                "huaweifreeclip2",
                "freeclip2",
                "btft0027",
            )

    override fun createProtocolSession(): ProtocolSession = HuaweiFreeClip2ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request)

    companion object {
        const val ID = "huawei-freeclip2"
    }
}

/**
 * Session over the `5A 00` private RFCOMM SPP channel 1 for FreeClip 2.
 *
 * The FreeClip 2 does not support noise-mode control. The session only handles battery
 * telemetry; all noise-related commands are omitted.
 */
private class HuaweiFreeClip2ProtocolSession : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        HuaweiFreebudsSppCodec.queryBattery,
        // No noise-state query — FreeClip 2 has no ANC
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(
            HuaweiFreebudsSppCodec.queryBattery,
        )
        // SetNoiseMode / SetAncLevel not supported on FreeClip 2
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun drainImmediateCommands(): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
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
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }
}
