package dev.hyperears.integration

import dev.hyperears.protocol.nicehck.NiceHckWireCodec
import dev.hyperears.protocol.nicehck.NiceHckSettingsCodec

/** Standard-behavior fallback for recognizable NiceHCK/YuanDao headsets. */
open class NiceHckEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "NiceHCK headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.yuanDao)

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("nicehck") || name.contains("yuandao")
    }

    companion object {
        const val ID = "nicehck-family"
    }
}

class NiceHckYuanDaoOrigAdapter : NiceHckEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "NiceHCK YuanDao OriG in"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = when {
            runtimeState().features.get<NiceHckSettingsFeatureState>()?.values?.isNotEmpty() == true ->
                if (effectiveCapabilities().noiseControl) ADVANCED_PRESENTATION_ID else SETTINGS_PRESENTATION_ID
            effectiveCapabilities().noiseControl -> PRESENTATION_ID
            else -> null
        }
    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state -> state is NiceHckSettingsFeatureState }
    override val controlRequestContract = StandardControlRequestContract.extending { adapter, request ->
        request is NiceHckControlRequest.SetValue &&
            adapter.runtimeState().features.get<NiceHckSettingsFeatureState>()?.supports(request) == true
    }
    override fun onProtocolReset() { removeFeatureState(NiceHckSettingsFeatureState.FEATURE_ID) }
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "nicehck-orig-rfcomm",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name in ORIG_MODEL_NAMES
    }

    override fun createProtocolSession(): ProtocolSession = NiceHckOrigProtocolSession()

    companion object {
        const val ID = "nicehck-yuandao-orig-in"
        val PRESENTATION_ID = MiLinkCardPresentationId(ID)
        val ADVANCED_PRESENTATION_ID = MiLinkCardPresentationId("nicehck-orig-advanced")
        val SETTINGS_PRESENTATION_ID = MiLinkCardPresentationId("nicehck-orig-settings")
        internal val SUPPORTED_NOISE_MODES = NoiseMode.entries.toSet()
        private const val SPP_UUID = "0000a100-1000-8000-4e48-434b4354524c"
        private val ORIG_MODEL_NAMES = setOf(
            "origin",
            "yuandaoorigin",
            "nicehckorigin",
            "nicehckyuandaoorigin",
        )
    }
}

private class NiceHckOrigProtocolSession : ProtocolSession {
    private val decoder = NiceHckWireCodec.Decoder()
    private var handshakeAccepted = false
    private var settings = NiceHckSettingsFeatureState()

    override fun initialReadCommands(): List<ByteArray> = listOf(
        NiceHckWireCodec.queryBattery,
        NiceHckWireCodec.queryNoiseMode,
    ) + listOf(NiceHckSettingsCodec.queryFirmware, NiceHckSettingsCodec.queryEqualizer) +
        NiceHckSettingsCodec.Toggle.entries.map(NiceHckSettingsCodec::query)

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request is NiceHckControlRequest.SetValue && settings.supports(request) -> listOf(
            if (request.setting == NiceHckSetting.NOISE) NiceHckWireCodec.setNoiseMode(
                NiceHckWireCodec.NoiseMode.entries.single { it.value == request.value })
            else NiceHckWireCodec.command(request.setting.write, byteArrayOf(request.value.toByte())),
        )
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> listOf(
            NiceHckWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
        request is NiceHckControlRequest.SetValue -> listOf(NiceHckWireCodec.command(request.setting.query))
        request === StandardControlRequest.Refresh -> emptyList()
        request is StandardControlRequest.SetNoiseMode -> listOf(NiceHckWireCodec.queryNoiseMode)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            val battery = NiceHckWireCodec.parseBattery(frame)
            val noiseMode = NiceHckWireCodec.parseNoiseMode(frame)
            var settingsChanged = false
            NiceHckSettingsCodec.parseFirmware(frame)?.let {
                settings = settings.copy(firmwareMajor = it.major, firmwareMinor = it.minor)
                settingsChanged = true
            }
            NiceHckSetting.entries.firstOrNull { it.query == frame.opcode }?.let { setting ->
                frame.parameters.singleOrNull()?.toInt()?.and(255)?.takeIf { it in setting.values }?.let { value ->
                    settings = settings.copy(values = settings.values + (setting to value))
                    settingsChanged = true
                }
            }
            if (!handshakeAccepted && (battery != null || noiseMode != null || settingsChanged)) {
                handshakeAccepted = true
                add(ProtocolEvent.HandshakeAccepted)
            }
            if (settingsChanged) add(ProtocolEvent.FeatureStateChanged(settings))
            battery?.let {
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(EarbudBattery(
                            left = BatteryReading(it.leftPercent, false),
                            right = BatteryReading(it.rightPercent, false),
                            case = BatteryReading(it.casePercent, false),
                        )),
                    ),
                )
            }
            noiseMode?.let { mode ->
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = NiceHckYuanDaoOrigAdapter.SUPPORTED_NOISE_MODES,
                    ),
                )
                add(
                    ProtocolEvent.FeatureStateChanged(
                        NoiseModeFeatureState(mode.toDomainMode()),
                    ),
                )
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakeAccepted = false
        settings = NiceHckSettingsFeatureState()
    }

    private fun NoiseMode.toWireMode(): NiceHckWireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> NiceHckWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> NiceHckWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> NiceHckWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> NiceHckWireCodec.NoiseMode.WIND
    }

    private fun NiceHckWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        NiceHckWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        NiceHckWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
        NiceHckWireCodec.NoiseMode.ANC,
        NiceHckWireCodec.NoiseMode.DEEP_ANC,
        NiceHckWireCodec.NoiseMode.EXPERIMENTAL_ANC,
        -> NoiseMode.ANC

        NiceHckWireCodec.NoiseMode.WIND -> NoiseMode.WIND
    }
}
