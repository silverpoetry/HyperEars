package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierV1WireCodec

/**
 * Edifier V1 (`bleVersion = 1`) session, used by W820NB headphones.
 *
 * The private channel opens from a valid `0xD0` battery report; noise modes open from a valid
 * `0xCC` report whose dialect is verified (`1 = 标准`, `2 = 降噪`, `3 = 通透`). A `0xD8`
 * capability reply only proves that the V1 transport is alive and lands the handshake without
 * claiming a capability the headset has not demonstrated.
 */
internal class EdifierV1ProtocolSession : ProtocolSession {
    private val decoder = EdifierV1WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        EdifierV1WireCodec.queryBattery,
        EdifierV1WireCodec.queryAnc,
        EdifierV1WireCodec.queryFunction,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(
            EdifierV1WireCodec.queryBattery,
            EdifierV1WireCodec.queryAnc,
        )

        is StandardControlRequest.SetNoiseMode -> writeValue(request.mode)
            ?.let { mode -> listOf(EdifierV1WireCodec.setAnc(mode)) }
            .orEmpty()

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> =
        if (request is StandardControlRequest.SetNoiseMode) {
            listOf(EdifierV1WireCodec.queryAnc)
        } else {
            emptyList()
        }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            EdifierV1WireCodec.parseBattery(frame)?.let { percent ->
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                overall = BatteryReading(percent, charging = false),
                            ),
                        ),
                    ),
                )
                publishHandshakeIfNeeded()
                return@forEach
            }

            EdifierV1WireCodec.parseAnc(frame)?.let { anc ->
                val mode = anc.mode.toNoiseMode()
                if (mode != null) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = SUPPORTED_NOISE_MODES,
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                }
                publishHandshakeIfNeeded()
                return@forEach
            }

            // A2DP state pushes are emitted by the headset on its own schedule; they carry no
            // telemetry this adapter projects and must not count as unknown traffic.
            if (frame.commandIndex == EdifierV1WireCodec.CMD_A2DP_STATE) return@forEach

            // Capability reply: proves the V1 transport only.
            if (frame.commandIndex == EdifierV1WireCodec.CMD_FUNCTION_QUERY) {
                publishHandshakeIfNeeded()
                return@forEach
            }

            add(
                ProtocolEvent.UnknownFrame(
                    version = 1,
                    vendor = 0,
                    command = frame.commandIndex,
                    payloadSize = frame.payload.size,
                ),
            )
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

    private fun Int.toNoiseMode(): NoiseMode? = when (this) {
        EdifierV1WireCodec.ANC_MODE_STANDARD -> NoiseMode.OFF
        EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION -> NoiseMode.ANC
        EdifierV1WireCodec.ANC_MODE_TRANSPARENCY -> NoiseMode.TRANSPARENCY
        else -> null
    }

    private fun writeValue(mode: NoiseMode): Int? = when (mode) {
        NoiseMode.OFF -> EdifierV1WireCodec.ANC_MODE_STANDARD
        NoiseMode.ANC -> EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION
        NoiseMode.TRANSPARENCY -> EdifierV1WireCodec.ANC_MODE_TRANSPARENCY
        // The W820NB V1 dialect has no 抗风噪 slot.
        NoiseMode.WIND -> null
    }

    private companion object {
        val SUPPORTED_NOISE_MODES: Set<NoiseMode> = setOf(
            NoiseMode.ANC,
            NoiseMode.TRANSPARENCY,
            NoiseMode.OFF,
        )
    }
}
