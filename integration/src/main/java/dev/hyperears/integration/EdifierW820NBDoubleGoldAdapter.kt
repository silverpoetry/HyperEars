package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierV1WireCodec

/** Protocol from QNquenan PR #50; this maintenance version awaits new device validation. */
class EdifierW820NBDoubleGoldAdapter : EdifierGameModeAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier W820NB 双金标版"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val formFactor = HeadsetFormFactor.HEADPHONES
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = EdifierMiLinkPresentationIds.W820NB.takeIf {
            effectiveCapabilities().noiseControl
        } ?: EdifierMiLinkPresentationIds.GAME_MODE.takeIf {
            runtimeState().features.get<EdifierGameModeFeatureState>() != null
        }

    override fun createProtocolSession(): ProtocolSession = EdifierV1ProtocolSession()

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return "w820nb" in name && "双金标" in name
    }

    companion object {
        const val ID = "edifier-w820nb-double-gold"
    }
}


private class EdifierV1ProtocolSession : ProtocolSession {
    private val decoder = EdifierV1WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        EdifierV1WireCodec.queryBattery,
        EdifierV1WireCodec.queryAnc,
        EdifierV1WireCodec.queryGameState,
        EdifierV1WireCodec.queryFunction,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(
            EdifierV1WireCodec.queryBattery,
            EdifierV1WireCodec.queryAnc,
            EdifierV1WireCodec.queryGameState,
        )

        is StandardControlRequest.SetNoiseMode -> {
            val value = when (request.mode) {
                NoiseMode.OFF -> EdifierV1WireCodec.ANC_MODE_STANDARD
                NoiseMode.ANC -> EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION
                NoiseMode.TRANSPARENCY -> EdifierV1WireCodec.ANC_MODE_TRANSPARENCY
                else -> null
            }
            if (value != null) {
                listOf(EdifierV1WireCodec.setAnc(value))
            } else {
                emptyList()
            }
        }

        is EdifierControlRequest.SetGameMode ->
            listOf(EdifierV1WireCodec.setGameState(request.enabled))

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode -> listOf(EdifierV1WireCodec.queryAnc)
        is EdifierControlRequest.SetGameMode -> listOf(EdifierV1WireCodec.queryGameState)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            EdifierV1WireCodec.parseBatteryState(frame)?.let { percent ->
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

            EdifierV1WireCodec.parseAncState(frame)?.let { anc ->
                val mode = when (anc.mode) {
                    EdifierV1WireCodec.ANC_MODE_STANDARD -> NoiseMode.OFF
                    EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION -> NoiseMode.ANC
                    EdifierV1WireCodec.ANC_MODE_TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    else -> null
                }
                if (mode != null) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                }
                publishHandshakeIfNeeded()
                return@forEach
            }

            EdifierV1WireCodec.parseGameState(frame)?.let { enabled ->
                add(ProtocolEvent.FeatureStateChanged(EdifierGameModeFeatureState(enabled)))
                publishHandshakeIfNeeded()
                return@forEach
            }

            // Only the captured 21-byte function reply proves transport; opaque variants do not.
            if (
                frame.commandIndex == EdifierV1WireCodec.CMD_FUNCTION_QUERY &&
                frame.payload.size == 21 &&
                EdifierV1WireCodec.isProtocolResponse(frame)
            ) {
                publishHandshakeIfNeeded()
                return@forEach
            }

            add(
                ProtocolEvent.UnknownFrame(
                    version = 0,
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
}
