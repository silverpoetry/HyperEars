package dev.hyperears.integration

import dev.hyperears.protocol.technics.TechnicsRaceWireCodec

/** Protocol candidate for the Technics EAH-AZ true-wireless family. */
class TechnicsEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Technics EAH-AZ series"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> =
        listOf(ControlAppCatalog.technicsAudioConnect)
    private var noiseStateConfirmedForSession = false
    override val controlRequestContract: ControlRequestContract =
        ControlRequestContract { adapter, request ->
            StandardControlRequestContract.supports(adapter, request) &&
                (request !is StandardControlRequest.SetNoiseMode ||
                    noiseStateConfirmedForSession)
        }
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = TECHNICS_SPP_UUID,
            id = "technics-airoha-spp",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = STANDARD_SPP_UUID,
            id = "technics-standard-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        return AZ_TWS_NAME.matches(normalizeDeviceName(identity.deviceName.orEmpty()))
    }

    override fun createProtocolSession(): ProtocolSession = TechnicsRaceProtocolSession()

    override fun onCapabilitiesIdentified(
        battery: Boolean,
        noiseModes: Set<NoiseMode>,
    ): HandshakeResult? {
        if (noiseModes.isNotEmpty()) noiseStateConfirmedForSession = true
        return super.onCapabilitiesIdentified(battery, noiseModes)
    }

    override fun onProtocolReset() {
        noiseStateConfirmedForSession = false
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "technics-eah-az"
        const val TECHNICS_SPP_UUID = "00000000-0000-0000-0099-AABBCCDDEEFF"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        internal val SUPPORTED_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )

        private val AZ_TWS_NAME = Regex("^(?:technics)?(?:eah)?az\\d{2,3}[a-z0-9]*$")
    }
}

private class TechnicsRaceProtocolSession : ProtocolSession {
    private val decoder = TechnicsRaceWireCodec.Decoder()
    private var battery = EarbudBattery()
    private var noiseCancelLevel = DEFAULT_NOISE_CANCEL_LEVEL
    private var transparencyLevel = DEFAULT_TRANSPARENCY_LEVEL
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = telemetryQueries()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> telemetryQueries()
        is StandardControlRequest.SetNoiseMode -> request.mode
            .toWireMode()
            ?.let { mode ->
                TechnicsRaceWireCodec.setNoiseMode(
                    mode = mode,
                    noiseCancelLevel = noiseCancelLevel,
                    ambientLevel = transparencyLevel,
                )
            }
            .orEmpty()
        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> batteryQueries()
            NoiseModeFeatureState.FEATURE_ID -> noiseQueries()
            else -> emptyList()
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode -> noiseQueries()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap { frame ->
            TechnicsRaceWireCodec.parseBattery(frame)?.let { reading ->
                battery = battery.withReading(reading)
                return@flatMap buildList<ProtocolEvent> {
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                    publishHandshakeIfNeeded()
                }
            }
            TechnicsRaceWireCodec.parseOutsideControl(frame)?.let { outsideControl ->
                noiseCancelLevel = outsideControl.noiseCancelLevel
                transparencyLevel = outsideControl.ambientLevel
                val mode = outsideControl.mode.toDomainMode()
                return@flatMap buildList<ProtocolEvent> {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = TechnicsEarbudAdapter.SUPPORTED_NOISE_MODES,
                        ),
                    )
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            NoiseModeFeatureState(mode),
                        ),
                    )
                    publishHandshakeIfNeeded()
                }
            }
            listOf<ProtocolEvent>(
                ProtocolEvent.UnknownFrame(
                    version = 0,
                    vendor = 0,
                    command = frame.raceId,
                    payloadSize = frame.payload.size,
                ),
            )
        }

    override fun reset() {
        decoder.reset()
        battery = EarbudBattery()
        noiseCancelLevel = DEFAULT_NOISE_CANCEL_LEVEL
        transparencyLevel = DEFAULT_TRANSPARENCY_LEVEL
        handshakePublished = false
    }

    private fun telemetryQueries(): List<ByteArray> = batteryQueries() + noiseQueries()

    private fun batteryQueries(): List<ByteArray> = listOf(
        TechnicsRaceWireCodec.queryAgentBattery,
        TechnicsRaceWireCodec.queryClientBattery,
        TechnicsRaceWireCodec.queryCaseBattery,
    )

    private fun noiseQueries(): List<ByteArray> = listOf(
        TechnicsRaceWireCodec.queryOutsideControl,
    )

    private fun EarbudBattery.withReading(
        reading: TechnicsRaceWireCodec.BatteryReading,
    ): EarbudBattery {
        val value = BatteryReading(reading.percent, charging = false)
        return when (reading.component) {
            TechnicsRaceWireCodec.BatteryComponent.LEFT -> copy(left = value)
            TechnicsRaceWireCodec.BatteryComponent.RIGHT -> copy(right = value)
            TechnicsRaceWireCodec.BatteryComponent.CASE -> copy(case = value)
        }
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireMode(): TechnicsRaceWireCodec.NoiseMode? = when (this) {
        NoiseMode.ANC -> TechnicsRaceWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> TechnicsRaceWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> TechnicsRaceWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> null
    }

    private fun TechnicsRaceWireCodec.OutsideControlMode.toDomainMode(): NoiseMode = when (this) {
        TechnicsRaceWireCodec.OutsideControlMode.ANC -> NoiseMode.ANC
        TechnicsRaceWireCodec.OutsideControlMode.OFF -> NoiseMode.OFF
        TechnicsRaceWireCodec.OutsideControlMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private companion object {
        const val DEFAULT_NOISE_CANCEL_LEVEL = 100
        const val DEFAULT_TRANSPARENCY_LEVEL = 50
    }
}
