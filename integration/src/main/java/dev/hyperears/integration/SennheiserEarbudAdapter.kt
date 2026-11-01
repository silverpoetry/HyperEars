package dev.hyperears.integration

import dev.hyperears.protocol.sennheiser.SennheiserGaiaFrame
import dev.hyperears.protocol.sennheiser.SennheiserGaiaFrameReader
import dev.hyperears.protocol.sennheiser.SennheiserGaiaWireCodec

/**
 * Standard Bluetooth fallback for Sennheiser headsets outside a confirmed private-protocol family.
 *
 * Name matching alone never unlocks battery or noise control; the GAIA session must return a valid
 * response before any private capability becomes visible.
 */
open class SennheiserEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Sennheiser headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> =
        listOf(ControlAppCatalog.sennheiserSmartControl)

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.isEligibleSennheiserHeadset() && identity.hasSennheiserName()

    companion object {
        const val ID = "sennheiser-family"
        const val MOMENTUM_4_PRESENTATION_ID = "sennheiser-momentum-4"
    }
}

/** GAIA noise-control dialects differ between the MOMENTUM 4 and the ACCENTUM generation. */
internal enum class SennheiserNoiseDialect {
    /** One command selects a mode and its state: `1A00 [mode, state]`. */
    COMBINED_MODES,

    /** ANC and transparency are independent switches: `1A04` and `1804`. */
    TOGGLE_PAIR,
}

internal data class SennheiserAdapterConfig(
    val id: String,
    val displayName: String,
    val nameMarkers: Set<String>,
    val formFactor: HeadsetFormFactor,
    val noiseDialect: SennheiserNoiseDialect,
    val supportsAntiWind: Boolean,
    val exactName: Boolean = false,
    val miLinkPresentationId: MiLinkCardPresentationId? = null,
    val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH,
)

/**
 * Sennheiser's shared GAIA protocol layer.
 *
 * The initial projection deliberately looks like a standard headset; every private capability is
 * unlocked one at a time by the corresponding valid GAIA response.
 */
open class SennheiserProtocolFamilyAdapter internal constructor(
    private val configuration: SennheiserAdapterConfig,
    private val matcher: (EarbudIdentity) -> Boolean,
) : SennheiserEarbudAdapter() {
    final override val id: String = configuration.id
    final override val displayName: String = configuration.displayName
    final override val formFactor: HeadsetFormFactor = configuration.formFactor
    final override val resolution: AdapterResolution = configuration.resolution
    final override val privateProtocolRequired: Boolean = true
    final override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    final override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    final override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    final override val supportedNoiseModes: Set<NoiseMode> = emptySet()
    final override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SennheiserGaiaWireCodec.GAIA_SERVICE_UUID,
            id = "sennheiser-gaia",
        ),
    )
    final override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = configuration.miLinkPresentationId
            ?.takeIf { effectiveCapabilities().noiseControl }

    final override fun matches(identity: EarbudIdentity): Boolean =
        identity.isEligibleSennheiserHeadset() && matcher(identity)

    final override fun createProtocolSession(): ProtocolSession =
        SennheiserProtocolSession(configuration)
}

private class SennheiserModelAdapter(
    configuration: SennheiserAdapterConfig,
) : SennheiserProtocolFamilyAdapter(
    configuration = configuration,
    matcher = { identity ->
        val name = identity.normalizedSennheiserName()
        if (configuration.exactName) {
            name in configuration.nameMarkers
        } else {
            configuration.nameMarkers.any(name::contains)
        }
    },
)

/** Sennheiser model and family composition root, ordered from exact models to safe fallbacks. */
object SennheiserAdapterRegistry {
    private val modelConfigs = listOf(
        SennheiserAdapterConfig(
            id = "sennheiser-momentum-4",
            displayName = "Sennheiser MOMENTUM 4",
            nameMarkers = setOf("momentum4"),
            formFactor = HeadsetFormFactor.HEADPHONES,
            noiseDialect = SennheiserNoiseDialect.COMBINED_MODES,
            supportsAntiWind = true,
            miLinkPresentationId = MiLinkCardPresentationId(
                SennheiserEarbudAdapter.MOMENTUM_4_PRESENTATION_ID,
            ),
        ),
        SennheiserAdapterConfig(
            id = "sennheiser-accentum-tws",
            displayName = "Sennheiser ACCENTUM True Wireless",
            nameMarkers = setOf("accentumtws", "accentumtruewireless"),
            formFactor = HeadsetFormFactor.TWS,
            noiseDialect = SennheiserNoiseDialect.TOGGLE_PAIR,
            supportsAntiWind = false,
        ),
        SennheiserAdapterConfig(
            id = "sennheiser-accentum",
            displayName = "Sennheiser ACCENTUM",
            nameMarkers = setOf("accentum"),
            formFactor = HeadsetFormFactor.HEADPHONES,
            noiseDialect = SennheiserNoiseDialect.TOGGLE_PAIR,
            supportsAntiWind = false,
        ),
    )

    private val modelFactories: List<() -> EarbudAdapter> =
        modelConfigs.map { config -> { SennheiserModelAdapter(config) } }

    /** Exact models first, then the name-agnostic family fallback. */
    val factories: List<() -> EarbudAdapter> = buildList {
        addAll(modelFactories)
        add(::SennheiserEarbudAdapter)
    }

    init {
        val adapters = factories.map { it() }
        require(adapters.map(EarbudAdapter::id).distinct().size == adapters.size)
    }
}

private class SennheiserProtocolSession(
    private val configuration: SennheiserAdapterConfig,
) : ProtocolSession {
    private val reader = SennheiserGaiaFrameReader()
    private var battery = EarbudBattery()
    private var handshakePublished = false
    private var batteryPublished = false
    private var noisePublished = false

    /** Latest independent toggle states, used to synthesise a mode for the TOGGLE_PAIR dialect. */
    private var ancEnabled: Boolean? = null
    private var transparencyEnabled: Boolean? = null

    override fun initialReadCommands(): List<ByteArray> = listOf(
        SennheiserGaiaWireCodec.queryQualcommApiVersion,
    )

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> = when (event) {
        ProtocolEvent.HandshakeAccepted -> telemetryQueries()
        else -> emptyList()
    }

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> telemetryQueries()
        is StandardControlRequest.SetNoiseMode -> setFrames(request.mode)
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
        is StandardControlRequest.SetNoiseMode -> noiseReadback()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in reader.offer(bytes)) {
            // Command frames are our own writes echoed back; they carry no device state.
            if (frame.type == SennheiserGaiaWireCodec.FrameType.COMMAND) continue
            if (frame.type == SennheiserGaiaWireCodec.FrameType.ERROR) {
                add(unknownFrame(frame))
                continue
            }

            // A null result means the command is outside this dialect and proves nothing.
            val events = when (frame.baseCommandId) {
                COMMAND_BATTERY -> batteryEvents(frame)
                COMMAND_COMBINED_NOISE -> combinedNoiseEvents(frame)
                COMMAND_ANC_TOGGLE -> ancToggleEvents(frame)
                COMMAND_TRANSPARENCY_TOGGLE -> transparencyToggleEvents(frame)
                // Core API version and model identity prove only that GAIA answers at all.
                COMMAND_CORE_VERSION, COMMAND_MODEL_ID -> emptyList()
                else -> null
            }
            if (events == null) {
                add(unknownFrame(frame))
                continue
            }

            addAll(events)
            publishHandshakeIfNeeded()
        }
    }

    override fun reset() {
        reader.reset()
        battery = EarbudBattery()
        handshakePublished = false
        batteryPublished = false
        noisePublished = false
        ancEnabled = null
        transparencyEnabled = null
    }

    private fun batteryEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val percent = SennheiserGaiaWireCodec.parseBatteryPercent(frame.payload) ?: return emptyList()
        battery = battery.copy(overall = BatteryReading(percent, charging = false))
        return buildList {
            if (!batteryPublished) {
                batteryPublished = true
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
            }
            add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
        }
    }

    private fun combinedNoiseEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val entries = SennheiserGaiaWireCodec.parseCombinedNoiseMode(frame.payload)
            ?: return emptyList()
        return noiseEvents(entries.toNoiseMode())
    }

    private fun ancToggleEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val state = frame.payload.singleOrNull()?.toInt()?.and(0xFF) ?: return emptyList()
        if (configuration.noiseDialect != SennheiserNoiseDialect.TOGGLE_PAIR) return emptyList()
        ancEnabled = state != SennheiserGaiaWireCodec.STATE_OFF
        return toggleNoiseEvents()
    }

    private fun transparencyToggleEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val state = frame.payload.singleOrNull()?.toInt()?.and(0xFF) ?: return emptyList()
        if (configuration.noiseDialect != SennheiserNoiseDialect.TOGGLE_PAIR) return emptyList()
        transparencyEnabled = state != SennheiserGaiaWireCodec.STATE_OFF
        return toggleNoiseEvents()
    }

    /**
     * The toggle dialect only becomes publishable once both switches reported a value; a half
     * observation cannot be projected onto a single [NoiseMode] without inventing one.
     */
    private fun toggleNoiseEvents(): List<ProtocolEvent> {
        val anc = ancEnabled ?: return emptyList()
        val transparency = transparencyEnabled ?: return emptyList()
        val mode = when {
            transparency -> NoiseMode.TRANSPARENCY
            anc -> NoiseMode.ANC
            else -> NoiseMode.OFF
        }
        return noiseEvents(mode)
    }

    private fun noiseEvents(mode: NoiseMode): List<ProtocolEvent> = buildList {
        if (!noisePublished) {
            noisePublished = true
            add(ProtocolEvent.CapabilitiesIdentified(battery = false, noiseModes = declaredNoiseModes()))
        }
        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
    }

    private fun declaredNoiseModes(): Set<NoiseMode> = if (configuration.supportsAntiWind) {
        setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND)
    } else {
        setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
    }

    private fun setFrames(mode: NoiseMode): List<ByteArray> = when (configuration.noiseDialect) {
        SennheiserNoiseDialect.COMBINED_MODES -> combinedModeFrames(mode)
        SennheiserNoiseDialect.TOGGLE_PAIR -> toggleModeFrames(mode)
    }

    /**
     * MOMENTUM 4 writes each selector separately. Anti-wind is engaged only together with adaptive
     * ANC; the shared disable pass runs last so a stale selector can never win.
     */
    private fun combinedModeFrames(mode: NoiseMode): List<ByteArray> = when (mode) {
        NoiseMode.ANC -> listOf(
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ADAPTIVE, SennheiserGaiaWireCodec.STATE_ON),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(TRANSPARENCY, SennheiserGaiaWireCodec.STATE_OFF),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ANTI_WIND, SennheiserGaiaWireCodec.STATE_OFF),
        )

        NoiseMode.TRANSPARENCY -> listOf(
            SennheiserGaiaWireCodec.setCombinedNoiseMode(TRANSPARENCY, SennheiserGaiaWireCodec.STATE_ON),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ADAPTIVE, SennheiserGaiaWireCodec.STATE_OFF),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ANTI_WIND, SennheiserGaiaWireCodec.STATE_OFF),
        )

        NoiseMode.WIND -> listOf(
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ADAPTIVE, SennheiserGaiaWireCodec.STATE_ON),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ANTI_WIND, SennheiserGaiaWireCodec.STATE_ON),
        )

        NoiseMode.OFF -> listOf(
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ADAPTIVE, SennheiserGaiaWireCodec.STATE_OFF),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(TRANSPARENCY, SennheiserGaiaWireCodec.STATE_OFF),
            SennheiserGaiaWireCodec.setCombinedNoiseMode(ANTI_WIND, SennheiserGaiaWireCodec.STATE_OFF),
        )
    }

    /** ACCENTUM exposes two independent switches; wind control is not part of this dialect. */
    private fun toggleModeFrames(mode: NoiseMode): List<ByteArray> = when (mode) {
        NoiseMode.ANC -> listOf(
            SennheiserGaiaWireCodec.setAncToggle(true),
            SennheiserGaiaWireCodec.setTransparencyToggle(false),
        )

        NoiseMode.TRANSPARENCY -> listOf(
            SennheiserGaiaWireCodec.setTransparencyToggle(true),
            SennheiserGaiaWireCodec.setAncToggle(false),
        )

        NoiseMode.OFF -> listOf(
            SennheiserGaiaWireCodec.setAncToggle(false),
            SennheiserGaiaWireCodec.setTransparencyToggle(false),
        )

        NoiseMode.WIND -> emptyList()
    }

    private fun telemetryQueries(): List<ByteArray> = batteryQueries() + noiseQueries()

    private fun batteryQueries(): List<ByteArray> = listOf(
        SennheiserGaiaWireCodec.queryBattery,
        SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_BATTERY),
    )

    private fun noiseQueries(): List<ByteArray> = when (configuration.noiseDialect) {
        SennheiserNoiseDialect.COMBINED_MODES -> noiseReadback() + listOf(
            SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_ANC),
        )

        SennheiserNoiseDialect.TOGGLE_PAIR -> noiseReadback() + listOf(
            SennheiserGaiaWireCodec.registerNotification(
                SennheiserGaiaWireCodec.FEATURE_TRANSPARENT_HEARING,
            ),
        )
    }

    /** Post-write verification reads state only; notification registration is a one-time setup. */
    private fun noiseReadback(): List<ByteArray> = when (configuration.noiseDialect) {
        SennheiserNoiseDialect.COMBINED_MODES -> listOf(
            SennheiserGaiaWireCodec.queryCombinedNoiseMode,
        )

        SennheiserNoiseDialect.TOGGLE_PAIR -> listOf(
            SennheiserGaiaWireCodec.queryAncToggle,
            SennheiserGaiaWireCodec.queryTransparencyToggle,
        )
    }

    private fun List<SennheiserGaiaWireCodec.NoiseModeEntry>.toNoiseMode(): NoiseMode {
        val transparency = firstOrNull { it.mode == TRANSPARENCY }
        val antiWind = firstOrNull { it.mode == ANTI_WIND }
        val adaptive = firstOrNull { it.mode == ADAPTIVE }
        return when {
            transparency != null && transparency.state != SennheiserGaiaWireCodec.STATE_OFF ->
                NoiseMode.TRANSPARENCY

            antiWind != null && antiWind.state != SennheiserGaiaWireCodec.STATE_OFF ->
                NoiseMode.WIND

            adaptive != null && adaptive.state != SennheiserGaiaWireCodec.STATE_OFF ->
                NoiseMode.ANC

            else -> NoiseMode.OFF
        }
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun unknownFrame(frame: SennheiserGaiaFrame): ProtocolEvent.UnknownFrame =
        ProtocolEvent.UnknownFrame(
            version = 3,
            vendor = frame.vendorId,
            command = frame.commandId,
            payloadSize = frame.payload.size,
        )

    private companion object {
        const val ADAPTIVE = SennheiserGaiaWireCodec.NOISE_MODE_ADAPTIVE
        const val ANTI_WIND = SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND
        const val TRANSPARENCY = SennheiserGaiaWireCodec.NOISE_MODE_TRANSPARENCY

        // Frames are dispatched on baseCommandId, which has the type bits already stripped.
        const val COMMAND_CORE_VERSION = 0x0000
        const val COMMAND_BATTERY = 0x0603
        const val COMMAND_MODEL_ID = 0x1206
        const val COMMAND_TRANSPARENCY_TOGGLE = 0x1805
        const val COMMAND_COMBINED_NOISE = 0x1A01
        const val COMMAND_ANC_TOGGLE = 0x1A05
    }
}

private fun EarbudIdentity.isEligibleSennheiserHeadset(): Boolean =
    standardHeadset && !nativeSystemEarbud && !deviceName.orEmpty().startsWith("LE_", ignoreCase = true)

private fun EarbudIdentity.normalizedSennheiserName(): String =
    deviceName.orEmpty().lowercase().filter(Char::isLetterOrDigit)

private fun EarbudIdentity.hasSennheiserName(): Boolean =
    normalizedSennheiserName().let { name ->
        name.startsWith("sennheiser") || SENNHEISER_NAME_MARKERS.any(name::contains)
    }

/**
 * SDP caches that advertise the GAIA endpoint are strong positive evidence, but no ROM is required
 * to publish it, so it may only ever widen an already eligible headset.
 */
private fun EarbudIdentity.hasSennheiserPrivateService(): Boolean = serviceUuids.any { uuid ->
    uuid.equals(SennheiserGaiaWireCodec.GAIA_SERVICE_UUID, ignoreCase = true)
}

private val SENNHEISER_NAME_MARKERS = setOf("momentum", "accentum", "sennheiser")
