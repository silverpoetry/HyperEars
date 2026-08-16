package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sSppCodec

/**
 * Concrete adapter for the Honor X5s Pro (BTV-ME10).
 *
 * The adapter starts from standard capabilities (system aggregate battery, media handoff, native
 * MiLink three-state card) and opens private capabilities only on valid protocol evidence:
 * component battery after a well-formed battery report, noise modes after a well-formed mode
 * report. ANC depth is a model-specific feature exposed through [HonorAncDepthFeatureState] and
 * [HonorControlRequest.SetAncDepth] on the v2.0.0 typed request/state transport; mode and depth
 * are independent features, both confirmed by the earphone's state report (`DEVICE_REPORT`).
 */
class HonorX5sProAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "荣耀亲选耳机 X5s Pro"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "honor-x5spro-spp",
        ),
    )

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is HonorAncDepthFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is HonorControlRequest.SetAncDepth &&
                adapter.effectiveCapabilities().noiseControl &&
                request.depth in (
                    adapter.runtimeState().features
                        .get<HonorAncDepthFeatureState>()
                        ?.supported
                        .orEmpty()
                )
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) == "荣耀亲选耳机x5spro"

    override fun createProtocolSession(): ProtocolSession = HonorX5sProProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is HonorControlRequest.SetAncDepth ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    companion object {
        const val ID = "honor-x5spro"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

/**
 * Session over the `5A 00` private RFCOMM SPP channel.
 *
 * The session owns the streaming decoder buffer, the single-worn-eardrum flag, the case-level
 * cache, the handshake confirmation and every protocol exchange; the adapter only supplies
 * identity, transport, contracts and confirmed capabilities.
 */
private class HonorX5sProProtocolSession : ProtocolSession {
    private val decoder = HonorX5sSppCodec.Decoder()
    private var singleEarbud = false
    private var handshakePublished = false

    private val supportedDepths = HonorAncDepth.entries.toSet()

    override fun initialReadCommands(): List<ByteArray> =
        HonorX5sSppCodec.initSequence + HonorX5sSppCodec.queryBattery

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(HonorX5sSppCodec.queryBattery)
        is StandardControlRequest.SetNoiseMode -> listOf(
            HonorX5sSppCodec.modeCommand(request.mode.toWireMode(), ANC_DEPTH),
        )
        is HonorControlRequest.SetAncDepth -> listOf(
            HonorX5sSppCodec.modeCommand(
                HonorX5sSppCodec.NoiseMode.ANC,
                request.depth.toWireDepth(),
            ),
        )
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (HonorX5sSppCodec.isHeartbeat(frame)) return@forEach
            HonorX5sSppCodec.parseBatteryFrame(frame)?.let { battery ->
                singleEarbud = battery.leftPercent == null || battery.rightPercent == null
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(battery.leftPercent, charging = false),
                                right = BatteryReading(battery.rightPercent, charging = false),
                                case = BatteryReading(battery.casePercent, charging = battery.caseCharging),
                            ),
                        ),
                    ),
                )
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                publishHandshakeIfNeeded()
                return@forEach
            }
            HonorX5sSppCodec.stateFromFrame(frame, singleEarbud)?.let { state ->
                add(
                    ProtocolEvent.FeatureStateChanged(
                        NoiseModeFeatureState(state.mode.toDomainMode()),
                    ),
                )
                add(
                    ProtocolEvent.FeatureStateChanged(
                        HonorAncDepthFeatureState(
                            current = state.depth?.toDomainDepth(),
                            supported = supportedDepths,
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
        singleEarbud = false
        handshakePublished = false
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireMode(): HonorX5sSppCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> HonorX5sSppCodec.NoiseMode.ANC
        NoiseMode.OFF -> HonorX5sSppCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> HonorX5sSppCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("The Honor X5s Pro protocol has no wind-noise mode")
    }

    private fun HonorX5sSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HonorX5sSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HonorX5sSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HonorX5sSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private fun HonorX5sSppCodec.AncDepth.toDomainDepth(): HonorAncDepth = when (this) {
        HonorX5sSppCodec.AncDepth.SMART -> HonorAncDepth.SMART
        HonorX5sSppCodec.AncDepth.LIGHT -> HonorAncDepth.LIGHT
        HonorX5sSppCodec.AncDepth.MEDIUM -> HonorAncDepth.MEDIUM
        HonorX5sSppCodec.AncDepth.DEEP -> HonorAncDepth.DEEP
    }

    private fun HonorAncDepth.toWireDepth(): HonorX5sSppCodec.AncDepth = when (this) {
        HonorAncDepth.SMART -> HonorX5sSppCodec.AncDepth.SMART
        HonorAncDepth.LIGHT -> HonorX5sSppCodec.AncDepth.LIGHT
        HonorAncDepth.MEDIUM -> HonorX5sSppCodec.AncDepth.MEDIUM
        HonorAncDepth.DEEP -> HonorX5sSppCodec.AncDepth.DEEP
    }

    private companion object {
        // ANC commands always request the vendor app's smart level.
        val ANC_DEPTH = HonorX5sSppCodec.AncDepth.SMART
    }
}
