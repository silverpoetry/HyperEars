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

/**
 * How a model composes the noise mode out of GAIA's two master switches.
 *
 * Every model answers `1A04` (ANC) and `1804` (transparent hearing), but the MOMENTUM 4 keeps
 * transparent hearing formally on while ANC is off and only lets it take effect once ANC is on, so
 * its "off" is the ANC switch alone. The ACCENTUM generation has an independent transparency path
 * and needs both switches driven.
 */
internal enum class SennheiserNoiseDialect {
    /** MOMENTUM 4: transparency rides on the ANC master switch; off is `1A04 [00]` alone. */
    MOMENTUM_4,

    /** ACCENTUM: ANC and transparency are independent switches. */
    ACCENTUM,
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

    /**
     * The requested mode while the headset is still settling into it.
     *
     * A MOMENTUM 4 write is a sequence of independent frames, and the headset reports each one as it
     * lands: switching from off to ANC first reports "ANC on" while transparent hearing is still on
     * from before, which projects as transparency — a mode the user never asked for. Holding reports
     * that disagree with the request keeps those intermediate states out of the published snapshot
     * without discarding them, because the protocol session still records every observation.
     */
    private var expectedNoiseMode: NoiseMode? = null
    private var noiseSettleAttempt = 0

    override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
        val mode = (request as? StandardControlRequest.SetNoiseMode)?.mode ?: return
        expectedNoiseMode = mode
        noiseSettleAttempt = 0
        scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
    }

    override fun onFeatureReported(
        state: DeviceFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision {
        val noise = state as? NoiseModeFeatureState ?: return FeatureReportDecision.ACCEPT
        val expected = expectedNoiseMode ?: return FeatureReportDecision.ACCEPT
        if (noise.mode == expected || noiseSettleAttempt >= NOISE_SETTLE_DELAYS_MS.size) {
            expectedNoiseMode = null
            noiseSettleAttempt = 0
            scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }

        // Re-read state only; the delay ladder keeps the polling bounded so a headset that never
        // reports the requested mode still publishes a real observation within about a second.
        scope.requestState(NoiseModeFeatureState.FEATURE_ID, NOISE_SETTLE_DELAYS_MS[noiseSettleAttempt++])
        return FeatureReportDecision.HOLD
    }

    override fun onProtocolReset() {
        expectedNoiseMode = null
        noiseSettleAttempt = 0
    }

    /**
     * Publish the requested mode as soon as the write lands, then keep the authoritative readback.
     *
     * The headset has no combined noise command, so a single mode change is several frames; without
     * an optimistic publish the card would show whatever the first frame reports.
     */
    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            if (request is StandardControlRequest.SetNoiseMode) {
                policy.copy(confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE_THEN_REFRESH)
            } else {
                policy
            }
        }

    private companion object {
        val NOISE_SETTLE_DELAYS_MS = longArrayOf(150L, 300L, 500L)
    }
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
            noiseDialect = SennheiserNoiseDialect.MOMENTUM_4,
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
            noiseDialect = SennheiserNoiseDialect.ACCENTUM,
            supportsAntiWind = false,
        ),
        SennheiserAdapterConfig(
            id = "sennheiser-accentum",
            displayName = "Sennheiser ACCENTUM",
            nameMarkers = setOf("accentum"),
            formFactor = HeadsetFormFactor.HEADPHONES,
            noiseDialect = SennheiserNoiseDialect.ACCENTUM,
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

    /**
     * Latest independent noise-control observations.
     *
     * Every model projects from the same three facts: the ANC master switch (`1A05`), the
     * transparent-hearing switch (`1805`) and — where the model has it — the anti-wind selector from
     * the `1A01` mode table. A mode is only publishable once every fact it needs has been observed.
     */
    private var ancEnabled: Boolean? = null
    private var transparencyEnabled: Boolean? = null

    /** Anti-wind selector state from the `1A01` mode table: 0 off, 1 max, 2 automatic. */
    private var antiWindState: Int? = null

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
            // A state request re-reads state only: notification registration is one-time setup and
            // repeating it on every poll multiplies the traffic that already unsettles the mode.
            NoiseModeFeatureState.FEATURE_ID -> noiseReadback()
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
        antiWindState = null
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
        // Only the anti-wind selector is a fact here; the mode table says nothing authoritative
        // about the master switch or transparent hearing, which have their own commands.
        antiWindState = entries.firstOrNull { it.mode == ANTI_WIND }?.state
        return projectedNoiseEvents()
    }

    private fun ancToggleEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val state = frame.payload.singleOrNull()?.toInt()?.and(0xFF) ?: return emptyList()
        ancEnabled = state != SennheiserGaiaWireCodec.STATE_OFF
        return projectedNoiseEvents()
    }

    private fun transparencyToggleEvents(frame: SennheiserGaiaFrame): List<ProtocolEvent> {
        val state = frame.payload.singleOrNull()?.toInt()?.and(0xFF) ?: return emptyList()
        transparencyEnabled = state != SennheiserGaiaWireCodec.STATE_OFF
        return projectedNoiseEvents()
    }

    /**
     * A mode is only publishable once the facts it needs have been observed.
     *
     * Switching the ANC master switch off silences transparent hearing on this headset even though
     * the switch itself stays on, so the master switch decides between off and everything else. A
     * half observation cannot be projected onto a single [NoiseMode] without inventing one.
     */
    private fun projectedNoiseEvents(): List<ProtocolEvent> {
        val anc = ancEnabled ?: return emptyList()
        val transparency = transparencyEnabled ?: return emptyList()
        val mode = when {
            !anc -> NoiseMode.OFF
            transparency -> NoiseMode.TRANSPARENCY
            (antiWindState ?: SennheiserGaiaWireCodec.STATE_OFF) != SennheiserGaiaWireCodec.STATE_OFF ->
                NoiseMode.WIND

            else -> NoiseMode.ANC
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
        SennheiserNoiseDialect.MOMENTUM_4 -> momentum4ModeFrames(mode)
        SennheiserNoiseDialect.ACCENTUM -> accentumModeFrames(mode)
    }

    /**
     * MOMENTUM 4 drives the ANC master switch (`1A04`) and transparent hearing (`1804`) directly.
     *
     * The `1A00` table only tunes sub-behaviours that ride on top of the master switch, so writing it
     * alone can never leave the pass-through state: with the master switch on and no sub-selector
     * active the headset simply passes audio through, which is indistinguishable from transparency.
     * Transparency also needs the master switch on first, because switching ANC on ends transparent
     * hearing on this headset.
     *
     * Every frame here changes a real device state, because the headset plays its confirmation chime
     * per accepted write: a fixed sequence costs two chimes for a single mode change and leaves
     * sub-selectors of the previous mode behind. The frames are therefore derived from the last
     * observed state, and a mode that is already active still writes its primary frame — the runtime
     * rejects an empty command list as a failed control.
     */
    private fun momentum4ModeFrames(mode: NoiseMode): List<ByteArray> = when (mode) {
        NoiseMode.ANC -> buildList {
            if (ancEnabled != true) add(SennheiserGaiaWireCodec.setAncToggle(true))
            // Switching ANC on ends transparent hearing on its own, so the switch only needs an
            // explicit write when ANC was already on.
            if (ancEnabled == true && transparencyEnabled != false) {
                add(SennheiserGaiaWireCodec.setTransparencyToggle(false))
            }
            // The mode table is the only way to clear a stale anti-wind selector, and an unknown
            // cache counts as possibly set: the post-write readback would otherwise publish the
            // previous mode again.
            if (antiWindState != SennheiserGaiaWireCodec.STATE_OFF) {
                add(
                    SennheiserGaiaWireCodec.setCombinedNoiseMode(
                        ANTI_WIND,
                        SennheiserGaiaWireCodec.STATE_OFF,
                    ),
                )
            }
        }.ifEmpty { listOf(SennheiserGaiaWireCodec.setAncToggle(true)) }

        NoiseMode.TRANSPARENCY -> buildList {
            if (ancEnabled != true) add(SennheiserGaiaWireCodec.setAncToggle(true))
            if (transparencyEnabled != true) {
                add(SennheiserGaiaWireCodec.setTransparencyToggle(true))
            }
        }.ifEmpty { listOf(SennheiserGaiaWireCodec.setTransparencyToggle(true)) }

        NoiseMode.WIND -> buildList {
            if (ancEnabled != true) add(SennheiserGaiaWireCodec.setAncToggle(true))
            // The selector write ends transparent hearing by itself, so it is the whole transition
            // whenever ANC is already on.
            if (ancEnabled != true ||
                transparencyEnabled != false ||
                antiWindState != SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO
            ) {
                add(
                    SennheiserGaiaWireCodec.setCombinedNoiseMode(
                        ANTI_WIND,
                        SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO,
                    ),
                )
            }
        }.ifEmpty {
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(
                    ANTI_WIND,
                    SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO,
                ),
            )
        }

        // Switching the master switch off is the whole of "off": transparent hearing stays formally
        // on but has no effect without ANC, and writing it off would destroy the remembered level.
        // A stale anti-wind selector is masked by the switch itself, so it is left for the next ANC.
        NoiseMode.OFF -> listOf(SennheiserGaiaWireCodec.setAncToggle(false))
    }

    /** ACCENTUM exposes two independent switches; wind control is not part of this dialect. */
    private fun accentumModeFrames(mode: NoiseMode): List<ByteArray> = when (mode) {
        NoiseMode.ANC -> buildList {
            if (ancEnabled != true) add(SennheiserGaiaWireCodec.setAncToggle(true))
            if (transparencyEnabled != false) {
                add(SennheiserGaiaWireCodec.setTransparencyToggle(false))
            }
        }.ifEmpty { listOf(SennheiserGaiaWireCodec.setAncToggle(true)) }

        NoiseMode.TRANSPARENCY -> buildList {
            if (transparencyEnabled != true) {
                add(SennheiserGaiaWireCodec.setTransparencyToggle(true))
            }
            if (ancEnabled != false) add(SennheiserGaiaWireCodec.setAncToggle(false))
        }.ifEmpty { listOf(SennheiserGaiaWireCodec.setTransparencyToggle(true)) }

        NoiseMode.OFF -> buildList {
            if (ancEnabled != false) add(SennheiserGaiaWireCodec.setAncToggle(false))
            if (transparencyEnabled != false) {
                add(SennheiserGaiaWireCodec.setTransparencyToggle(false))
            }
        }.ifEmpty { listOf(SennheiserGaiaWireCodec.setAncToggle(false)) }

        NoiseMode.WIND -> emptyList()
    }

    private fun telemetryQueries(): List<ByteArray> = batteryQueries() + noiseQueries()

    private fun batteryQueries(): List<ByteArray> = listOf(
        SennheiserGaiaWireCodec.queryBattery,
        SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_BATTERY),
    )

    /**
     * Both dialects expose the ANC master switch and transparent hearing; only the mode table that
     * carries the anti-wind selector is MOMENTUM-4-specific.
     */
    private fun noiseQueries(): List<ByteArray> = noiseReadback() + listOf(
        SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_ANC),
        SennheiserGaiaWireCodec.registerNotification(
            SennheiserGaiaWireCodec.FEATURE_TRANSPARENT_HEARING,
        ),
    )

    /** Post-write verification reads state only; notification registration is a one-time setup. */
    private fun noiseReadback(): List<ByteArray> = buildList {
        add(SennheiserGaiaWireCodec.queryAncToggle)
        add(SennheiserGaiaWireCodec.queryTransparencyToggle)
        if (configuration.supportsAntiWind) {
            add(SennheiserGaiaWireCodec.queryCombinedNoiseMode)
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
        const val ANTI_WIND = SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND

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
