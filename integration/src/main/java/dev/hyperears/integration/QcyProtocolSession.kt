package dev.hyperears.integration

import dev.hyperears.protocol.qcy.QcyWireCodec

/** One stateful QCY command conversation for one physical headset session. */
internal class QcyProtocolSession : ProtocolSession {
    private val decoder = QcyWireCodec.Decoder()
    private var handshakePublished = false
    private var settings = QcySettingsFeatureState()

    override fun initialReadCommands(): List<ByteArray> = listOf(
        QcyWireCodec.queryBattery,
        QcyWireCodec.queryNoiseMode,
    ) + QcyWireCodec.advancedQueries

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> request.mode
            .toQcyMode()
            ?.let(QcyWireCodec::setNoiseMode)
            ?.let(::listOf)
            .orEmpty()

        request is QcyControlRequest && settings.supports(request) -> listOf(when (request) {
            is QcyControlRequest.SetToggle -> QcyWireCodec.setToggle(request.setting.wire(), request.enabled)
            is QcyControlRequest.SetSpatialMode -> QcyWireCodec.setSpatialMode(request.mode)
            is QcyControlRequest.SetEqPreset -> QcyWireCodec.setEqPreset(request.preset)
            is QcyControlRequest.SetCustomEq -> QcyWireCodec.setCustomEq(request.gains)
        })
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode -> listOf(QcyWireCodec.queryNoiseMode)
        is QcyControlRequest.SetToggle -> listOf(QcyWireCodec.query(request.setting.command))
        is QcyControlRequest.SetSpatialMode -> listOf(QcyWireCodec.query(0x2D))
        is QcyControlRequest.SetEqPreset -> listOf(QcyWireCodec.query(0x20), QcyWireCodec.query(0x22))
        is QcyControlRequest.SetCustomEq -> listOf(QcyWireCodec.query(0x22))
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        var accepted = false
        decoder.offer(bytes).forEach { frame ->
            var changed = false
            QcyWireCodec.parseToggle(frame)?.let { (toggle, enabled) ->
                val setting = QcyToggle.entries.single { it.command == toggle.command }
                settings = settings.copy(toggles = settings.toggles + (setting to enabled))
                changed = true
            }
            QcyWireCodec.parseSpatialMode(frame)?.let {
                settings = settings.copy(spatialMode = it); changed = true
            }
            QcyWireCodec.parseEqPreset(frame)?.let { preset ->
                settings = if (frame.command == 0x20) settings.copy(eqPreset = preset, eqV1Preset = preset)
                    else settings.copy(eqPreset = preset, eqV2Preset = preset, equalizer = QcyWireCodec.parseEqualizer(frame)?.let { eq ->
                        QcyEqualizer(eq.preset, eq.masterGainHundredths, eq.bands.map {
                            QcyEqBand(it.frequency, it.gainHundredths, it.qHundredths, it.type)
                        })
                    })
                changed = true
            }
            if (changed) {
                accepted = true
                add(ProtocolEvent.FeatureStateChanged(settings))
            }
            QcyWireCodec.parseBattery(frame)?.let { battery ->
                accepted = true
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = battery.left.toBatteryReading(),
                                right = battery.right.toBatteryReading(),
                                case = battery.case.toBatteryReading(),
                            ),
                        ),
                    ),
                )
            }
            QcyWireCodec.parseNoiseMode(frame)?.let { mode ->
                accepted = true
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = THREE_STATE_NOISE_MODES,
                    ),
                )
                add(
                    ProtocolEvent.FeatureStateChanged(
                        NoiseModeFeatureState(mode.toDomainMode()),
                    ),
                )
            }
        }
        if (accepted && !handshakePublished) {
            handshakePublished = true
            add(ProtocolEvent.HandshakeAccepted)
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        settings = QcySettingsFeatureState()
    }

    private fun QcyWireCodec.BatteryCell.toBatteryReading(): BatteryReading =
        BatteryReading(percent = percent, charging = charging)

    private fun NoiseMode.toQcyMode(): QcyWireCodec.NoiseMode? = when (this) {
        NoiseMode.ANC -> QcyWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> QcyWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> QcyWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> null
    }

    private fun QcyWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        QcyWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        QcyWireCodec.NoiseMode.ANC,
        QcyWireCodec.NoiseMode.OUTDOOR,
        -> NoiseMode.ANC

        QcyWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private companion object {
        val THREE_STATE_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
