package dev.hyperears.integration

import dev.hyperears.protocol.swarmoo.SwarmooMo3WireCodec

/** One connected MO3 PRO conversation. Transport routes carry unmodified notification datagrams. */
internal class SwarmooMo3ProtocolSession : ProtocolSession {
    private var handshakePublished = false
    private var batteryConfirmed = false

    override fun initialReadCommands(): List<ByteArray> = listOf(SwarmooMo3WireCodec.query)

    override fun commandRoutes(command: ByteArray): List<String?> =
        if (command.contentEquals(SwarmooMo3WireCodec.query)) listOf(null, BATTERY_ROUTE) else listOf(null)

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> initialReadCommands()
        is StandardControlRequest.SetNoiseMode -> when (request.mode) {
            NoiseMode.OFF -> listOf(SwarmooMo3WireCodec.setMain(0))
            NoiseMode.TRANSPARENCY -> listOf(SwarmooMo3WireCodec.setMain(2))
            NoiseMode.ANC -> listOf(SwarmooMo3WireCodec.setMain(1), SwarmooMo3WireCodec.setSub(2))
            NoiseMode.WIND -> listOf(SwarmooMo3WireCodec.setMain(1), SwarmooMo3WireCodec.setSub(1))
        }
        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> initialReadCommands()
        is TelemetryQuery.RefreshFeature -> if (
            request.featureId == NoiseModeFeatureState.FEATURE_ID ||
            request.featureId == BatteryFeatureState.FEATURE_ID
        ) initialReadCommands() else emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> =
        if (request is StandardControlRequest.SetNoiseMode) initialReadCommands() else emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = offer(bytes, null)

    override fun offer(bytes: ByteArray, route: String?): List<ProtocolEvent> = buildList {
        when (route) {
            null -> {
                val state = SwarmooMo3WireCodec.parseMode(bytes) ?: return@buildList
                acceptHandshake()
                val mode = when (state.main) {
                    0 -> NoiseMode.OFF
                    2 -> NoiseMode.TRANSPARENCY
                    else -> if (state.sub == 1) NoiseMode.WIND else NoiseMode.ANC
                }
                add(ProtocolEvent.CapabilitiesIdentified(
                    battery = false,
                    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
                ))
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
            }
            BATTERY_ROUTE -> {
                val state = SwarmooMo3WireCodec.parseBattery(bytes) ?: return@buildList
                acceptHandshake()
                val battery = EarbudBattery(
                    left = BatteryReading(state.left, false),
                    right = BatteryReading(state.right, false),
                    case = BatteryReading(state.case, false),
                )
                if (state.left != null || state.right != null || state.case != null) {
                    batteryConfirmed = true
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                }
                // An unknown complete report clears old component values after confirmation.
                if (batteryConfirmed) add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
            }
        }
    }

    override fun reset() {
        handshakePublished = false
        batteryConfirmed = false
    }

    private fun MutableList<ProtocolEvent>.acceptHandshake() {
        if (!handshakePublished) {
            handshakePublished = true
            add(ProtocolEvent.HandshakeAccepted)
        }
    }

    companion object {
        const val BATTERY_ROUTE = "battery"
    }
}
