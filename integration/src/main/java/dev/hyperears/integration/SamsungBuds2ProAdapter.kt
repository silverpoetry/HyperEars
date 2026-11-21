package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
/** Samsung ANC-capable Galaxy Buds family; model selection never proves a capability. */
class SamsungBuds2ProAdapter(
    private val model: SamsungBudsCodec.Model = SamsungBudsCodec.Model.BUDS2_PRO,
) : StandardEarbudAdapter() {
    override val id: String = when (model) {
        SamsungBudsCodec.Model.BUDS2_PRO -> ID
        SamsungBudsCodec.Model.BUDS2 -> "samsung-galaxy-buds2"
        SamsungBudsCodec.Model.BUDS_FE -> "samsung-galaxy-buds-fe"
        SamsungBudsCodec.Model.BUDS_LIVE -> "samsung-galaxy-buds-live"
        SamsungBudsCodec.Model.BUDS_PRO -> "samsung-galaxy-buds-pro"
        SamsungBudsCodec.Model.BUDS3 -> "samsung-galaxy-buds3"
        SamsungBudsCodec.Model.BUDS3_PRO -> "samsung-galaxy-buds3-pro"
        SamsungBudsCodec.Model.BUDS_CORE -> "samsung-galaxy-buds-core"
        SamsungBudsCodec.Model.BUDS3_FE -> "samsung-galaxy-buds3-fe"
        SamsungBudsCodec.Model.BUDS4 -> "samsung-galaxy-buds4"
        SamsungBudsCodec.Model.BUDS4_PRO -> "samsung-galaxy-buds4-pro"
        SamsungBudsCodec.Model.UNKNOWN -> "samsung-galaxy-buds-family"
    }
    override val displayName: String = when (model) {
        SamsungBudsCodec.Model.BUDS2_PRO -> "Samsung Galaxy Buds2 Pro"
        SamsungBudsCodec.Model.BUDS2 -> "Samsung Galaxy Buds2"
        SamsungBudsCodec.Model.BUDS_FE -> "Samsung Galaxy Buds FE"
        SamsungBudsCodec.Model.BUDS_LIVE -> "Samsung Galaxy Buds Live"
        SamsungBudsCodec.Model.BUDS_PRO -> "Samsung Galaxy Buds Pro"
        SamsungBudsCodec.Model.BUDS3 -> "Samsung Galaxy Buds3"
        SamsungBudsCodec.Model.BUDS3_PRO -> "Samsung Galaxy Buds3 Pro"
        SamsungBudsCodec.Model.BUDS_CORE -> "Samsung Galaxy Buds Core"
        SamsungBudsCodec.Model.BUDS3_FE -> "Samsung Galaxy Buds3 FE"
        SamsungBudsCodec.Model.BUDS4 -> "Samsung Galaxy Buds4"
        SamsungBudsCodec.Model.BUDS4_PRO -> "Samsung Galaxy Buds4 Pro"
        SamsungBudsCodec.Model.UNKNOWN -> "Samsung Galaxy Buds (shared service)"
    }
    override val resolution: AdapterResolution = if (model == SamsungBudsCodec.Model.UNKNOWN)
        AdapterResolution.FAMILY_MATCH else AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(if (model.usesStandardSpp) STANDARD_SPP_UUID else BUDS2_PRO_UUID,
            "samsung-buds-rfcomm"),
    )
    override val controlApps: List<ControlAppSpec> = buildList {
        add(ControlAppCatalog.galaxyWearable)
        when (model) {
            SamsungBudsCodec.Model.BUDS2_PRO -> add(ControlAppCatalog.galaxyBuds2ProManager)
            SamsungBudsCodec.Model.BUDS2 -> add(ControlAppCatalog.galaxyBuds2Manager)
            SamsungBudsCodec.Model.BUDS_FE -> add(ControlAppCatalog.galaxyBudsFeManager)
            SamsungBudsCodec.Model.BUDS_PRO -> add(ControlAppCatalog.galaxyBudsProManager)
            SamsungBudsCodec.Model.BUDS_LIVE -> add(ControlAppCatalog.galaxyBudsLiveManager)
            SamsungBudsCodec.Model.BUDS3 -> add(ControlAppCatalog.galaxyBuds3Manager)
            SamsungBudsCodec.Model.BUDS3_PRO -> add(ControlAppCatalog.galaxyBuds3ProManager)
            SamsungBudsCodec.Model.BUDS_CORE -> add(ControlAppCatalog.galaxyBudsCoreManager)
            SamsungBudsCodec.Model.BUDS3_FE -> add(ControlAppCatalog.galaxyBuds3FeManager)
            SamsungBudsCodec.Model.BUDS4, SamsungBudsCodec.Model.BUDS4_PRO -> Unit
            SamsungBudsCodec.Model.UNKNOWN -> Unit
        }
        // Samsung's newer unified controller is a control-ownership boundary,
        // never a model-identification hint.
        if (model != SamsungBudsCodec.Model.UNKNOWN) add(ControlAppCatalog.galaxyBudsUnified)
    }
    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val privateService = identity.serviceUuids.any { it.equals(BUDS2_PRO_UUID, ignoreCase = true) }
        if (model == SamsungBudsCodec.Model.UNKNOWN) return privateService
        val aliases = MODEL_ALIASES.getValue(model)
        val officialName = aliases.any { alias ->
            name == alias || (name.startsWith(alias) &&
                name.removePrefix(alias).matches(Regex("[a-f0-9]{4}")))
        }
        return officialName || (privateService && MODEL_SUFFIXES.getValue(model).any(name::endsWith))
    }

    override fun createProtocolSession(): ProtocolSession = SamsungBuds2ProProtocolSession(model = model)

    companion object {
        const val ID = "samsung-galaxy-buds2-pro"
        const val BUDS2_PRO_UUID = "2e73a4ad-332d-41fc-90e2-16bef06523f2"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
        private val MODEL_SUFFIXES = mapOf(
            SamsungBudsCodec.Model.BUDS2_PRO to setOf("buds2pro", "smr510"),
            SamsungBudsCodec.Model.BUDS2 to setOf("buds2", "smr177"),
            SamsungBudsCodec.Model.BUDS_FE to setOf("budsfe", "smr400"),
            SamsungBudsCodec.Model.BUDS_LIVE to setOf("budslive", "smr180"),
            SamsungBudsCodec.Model.BUDS_PRO to setOf("budspro", "smr190"),
            SamsungBudsCodec.Model.BUDS3 to setOf("buds3", "smr530"),
            SamsungBudsCodec.Model.BUDS3_PRO to setOf("buds3pro", "smr630"),
            SamsungBudsCodec.Model.BUDS_CORE to setOf("budscore", "smr410"),
            SamsungBudsCodec.Model.BUDS3_FE to setOf("buds3fe", "smr420"),
            SamsungBudsCodec.Model.BUDS4 to setOf("buds4", "smr540"),
            SamsungBudsCodec.Model.BUDS4_PRO to setOf("buds4pro", "smr640"),
        )
        private val MODEL_ALIASES = MODEL_SUFFIXES.mapValues { (_, suffixes) ->
            suffixes.flatMap { suffix ->
                if (suffix.startsWith("smr")) listOf(suffix)
                else listOf("galaxy$suffix", "samsunggalaxy$suffix")
            }.toSet()
        }
    }
}

internal class SamsungBuds2ProProtocolSession(
    private val model: SamsungBudsCodec.Model = SamsungBudsCodec.Model.BUDS2_PRO,
) : ProtocolSession {
    private val decoder = SamsungBudsCodec.Decoder()
    private var handshakePublished = false
    private var managerInfoSent = false

    override fun initialReadCommands(): List<ByteArray> = emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(SamsungBudsCodec.managerInfoCommand())
        is StandardControlRequest.SetNoiseMode -> request.mode.takeIf { it in noiseModes }?.toSamsungMode()
            ?.let { SamsungBudsCodec.noiseModeCommand(it, model) }?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { raw ->
            val frame = SamsungBudsCodec.parseFrame(raw) ?: return@forEach
            var accepted = false
            SamsungBudsCodec.parseBattery(frame, model)?.let { battery ->
                accepted = true
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery.toDomain())))
            }
            SamsungBudsCodec.parseNoiseMode(frame, model)?.let { mode ->
                accepted = true
                add(ProtocolEvent.CapabilitiesIdentified(false, noiseModes))
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode.toDomain())))
            }
            if (accepted && !handshakePublished) {
                handshakePublished = true
                add(ProtocolEvent.HandshakeAccepted)
            }
        }
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> {
        if (event !is ProtocolEvent.HandshakeAccepted || managerInfoSent) return emptyList()
        managerInfoSent = true
        return listOf(SamsungBudsCodec.managerInfoCommand())
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        managerInfoSent = false
    }

    private fun SamsungBudsCodec.BatteryState.toDomain() = EarbudBattery(
        left = BatteryReading(left, leftCharging), right = BatteryReading(right, rightCharging),
        case = BatteryReading(case, caseCharging),
    )
    private fun SamsungBudsCodec.NoiseMode.toDomain() = when (this) {
        SamsungBudsCodec.NoiseMode.OFF -> NoiseMode.OFF
        SamsungBudsCodec.NoiseMode.ANC -> NoiseMode.ANC
        SamsungBudsCodec.NoiseMode.AMBIENT -> NoiseMode.TRANSPARENCY
    }
    private fun NoiseMode.toSamsungMode() = when (this) {
        NoiseMode.OFF -> SamsungBudsCodec.NoiseMode.OFF
        NoiseMode.ANC -> SamsungBudsCodec.NoiseMode.ANC
        NoiseMode.TRANSPARENCY -> SamsungBudsCodec.NoiseMode.AMBIENT
        NoiseMode.WIND -> null
    }

    private val noiseModes: Set<NoiseMode> get() = if (model.ambient) THREE_STATE_NOISE_MODES
        else setOf(NoiseMode.ANC, NoiseMode.OFF)

    private companion object {
        val THREE_STATE_NOISE_MODES = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
    }
}
