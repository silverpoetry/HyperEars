package dev.hyperears.integration

import dev.hyperears.protocol.flos2.FloS2WireCodec

/** One stateful FloS2 conversation for one physical sport-earbud session. */
internal class FloS2ProtocolSession : ProtocolSession {
    private val decoder = FloS2WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(FloS2WireCodec.queryAllStatus)

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is FloS2ControlRequest.SetSoundEffect -> listOf(
            FloS2WireCodec.setSoundEffect(request.effect.toWireEffect()),
        )

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is FloS2ControlRequest.SetSoundEffect -> initialReadCommands()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            FloS2WireCodec.parseStatus(frame)?.let { status ->
                acceptHandshake()
                status.batteryPercent?.let { percent ->
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            BatteryFeatureState(EarbudBattery.fromAggregate(percent)),
                        ),
                    )
                }
                status.soundEffect?.let { effect ->
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            FloS2SoundEffectFeatureState(effect.toDomainEffect()),
                        ),
                    )
                }
            }
            FloS2WireCodec.parseBatteryReport(frame)?.let { percent ->
                acceptHandshake()
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(EarbudBattery.fromAggregate(percent)),
                    ),
                )
            }
            // The sound-effect ack carries no state; authoritative truth arrives through the
            // queryAllStatus readback response parsed above.
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun MutableList<ProtocolEvent>.acceptHandshake() {
        if (!handshakePublished) {
            handshakePublished = true
            add(ProtocolEvent.HandshakeAccepted)
        }
    }

    private fun FloS2SoundEffect.toWireEffect(): FloS2WireCodec.SoundEffect = when (this) {
        FloS2SoundEffect.STANDARD -> FloS2WireCodec.SoundEffect.STANDARD
        FloS2SoundEffect.SWIMMING -> FloS2WireCodec.SoundEffect.SWIMMING
        FloS2SoundEffect.VOCALS -> FloS2WireCodec.SoundEffect.VOCALS
        FloS2SoundEffect.ENHANCED -> FloS2WireCodec.SoundEffect.ENHANCED
    }

    private fun FloS2WireCodec.SoundEffect.toDomainEffect(): FloS2SoundEffect = when (this) {
        FloS2WireCodec.SoundEffect.STANDARD -> FloS2SoundEffect.STANDARD
        FloS2WireCodec.SoundEffect.SWIMMING -> FloS2SoundEffect.SWIMMING
        FloS2WireCodec.SoundEffect.VOCALS -> FloS2SoundEffect.VOCALS
        FloS2WireCodec.SoundEffect.ENHANCED -> FloS2SoundEffect.ENHANCED
    }
}
