package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropPuddingWireCodec

/**
 * Protocol adapter for the MOONDROP Pudding TWS headset (Bluetooth name "MOONDROP Pudding").
 *
 * Handshake, frame layout and noise-mode encoding match MOONDROP Robin: the same
 * `0A 03 00` handshake command and `0A 83 00 00 04 03 01` confirmation, and a
 * `1D 40/41 03/04` noise-mode query/write pair. Private component battery and
 * noise control are exposed only after their corresponding valid responses.
 *
 * Pudding's noise surface covers the full five-tier byte range (each tier device-confirmed
 * 2026-10-26): the standard ANC switch maps to the adaptive tier (`04`), wind (`03`) is
 * exposed through the wind-noise card toggle, and basic-tier (`01`) reports project back onto
 * the standard ANC state.
 */
class MoondropPuddingAdapter : MoondropJieliAdapter("moondrop-pudding-spp") {
    override val id: String = ID
    override val displayName: String = "MOONDROP Pudding"
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = PRESENTATION_ID.takeIf { effectiveCapabilities().noiseControl }

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return ("moondrop" in name || "水月雨" in name) && "pudding" in name
    }

    override fun createProtocolSession(): ProtocolSession =
        MoondropPuddingProtocolSession(
            supportedTiers = PUDDING_NOISE_TIERS,
            ancWriteTier = MoondropPuddingWireCodec.NoiseMode.ADAPTIVE,
        )

    companion object {
        const val ID = "moondrop-pudding"
        val PRESENTATION_ID = MiLinkCardPresentationId(ID)
        const val STANDARD_SPP_UUID = MoondropJieliAdapter.STANDARD_SPP_UUID
        internal const val INITIAL_MODE_QUERY_DELAY_MS = MoondropJieliAdapter.INITIAL_MODE_QUERY_DELAY_MS
        internal val BATTERY_BOOTSTRAP_DELAYS_MS = MoondropJieliAdapter.BATTERY_BOOTSTRAP_DELAYS_MS
        internal val MODE_CONFIRMATION_DELAYS_MS = MoondropJieliAdapter.MODE_CONFIRMATION_DELAYS_MS

        /** Recognize all captured tiers; ANC writes adaptive (`04`), while basic (`01`) is read-only. */
        internal val PUDDING_NOISE_TIERS: Set<MoondropPuddingWireCodec.NoiseMode> =
            MoondropPuddingWireCodec.NoiseMode.entries.toSet()
    }
}

/** Shared FF/Jieli dialogue and bounded report policy, with state owned by each adapter instance. */
abstract class MoondropJieliAdapter(
    endpointId: String,
) : MoondropEarbudAdapter() {
    private var batteryBootstrapAttempt = 0
    private var privateBatteryCommitted = false
    private var expectedNoiseMode: NoiseMode? = null
    private var noiseModeAttempt = 0

    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.moondrop)
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = STANDARD_SPP_UUID,
            id = endpointId,
        ),
    )

    override fun createProtocolSession(): ProtocolSession = MoondropPuddingProtocolSession()

    override fun onFeatureReported(
        state: DeviceFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision = when (state) {
        is BatteryFeatureState -> handleBatteryReport(state, scope)
        is NoiseModeFeatureState -> handleNoiseModeReport(state, scope)
        else -> FeatureReportDecision.ACCEPT
    }

    private fun handleBatteryReport(
        state: BatteryFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision {
        if (privateBatteryCommitted) return FeatureReportDecision.ACCEPT

        val battery = state.battery
        val provisional = battery.left.percent == null || battery.right.percent == null
        if (!provisional || batteryBootstrapAttempt >= BATTERY_BOOTSTRAP_DELAYS_MS.size) {
            privateBatteryCommitted = true
            scope.cancelStateRequest(BatteryFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }

        scope.requestState(
            BatteryFeatureState.FEATURE_ID,
            BATTERY_BOOTSTRAP_DELAYS_MS[batteryBootstrapAttempt++],
        )
        return FeatureReportDecision.HOLD
    }

    private fun handleNoiseModeReport(
        state: NoiseModeFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision {
        val expected = expectedNoiseMode ?: return FeatureReportDecision.ACCEPT
        if (
            state.mode == expected ||
            noiseModeAttempt >= MODE_CONFIRMATION_DELAYS_MS.size
        ) {
            expectedNoiseMode = null
            noiseModeAttempt = 0
            scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }

        val delayMs = MODE_CONFIRMATION_DELAYS_MS[noiseModeAttempt++]
        scope.requestState(NoiseModeFeatureState.FEATURE_ID, delayMs)
        return FeatureReportDecision.HOLD
    }

    override fun onControlWritten(
        request: ControlRequest,
        scope: AdapterEventScope,
    ) {
        if (request !is StandardControlRequest.SetNoiseMode) return
        expectedNoiseMode = request.mode
        noiseModeAttempt = 0
        scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
        scope.requestState(NoiseModeFeatureState.FEATURE_ID, INITIAL_MODE_QUERY_DELAY_MS)
    }

    override fun onProtocolReset() {
        batteryBootstrapAttempt = 0
        privateBatteryCommitted = false
        expectedNoiseMode = null
        noiseModeAttempt = 0
    }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            if (request is StandardControlRequest.SetNoiseMode) {
                policy.copy(
                    confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                )
            } else {
                policy
            }
        }

    /** A known exact model remains eligible for the normal bounded retry and dormant wake path. */
    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
        internal const val INITIAL_MODE_QUERY_DELAY_MS = 600L
        internal val BATTERY_BOOTSTRAP_DELAYS_MS = longArrayOf(500L, 800L, 1_200L, 1_600L)
        internal val MODE_CONFIRMATION_DELAYS_MS = longArrayOf(500L, 700L, 900L, 1_200L)

    }
}

internal class MoondropPuddingProtocolSession(
    /**
     * Wire tiers this model may control and recognize. Pudding passes the full five-tier set;
     * the default keeps MIRAGE's verified three-state surface (basic `01`, off, transparency),
     * where `03`/`04` stay unrecognized and wind writes are rejected.
     */
    private val supportedTiers: Set<MoondropPuddingWireCodec.NoiseMode> = THREE_STATE_TIERS,
    /**
     * The wire tier the standard ANC switch writes: adaptive (`04`) on Pudding, basic (`01`)
     * on MIRAGE whose verified surface never adopted the adaptive tier.
     */
    private val ancWriteTier: MoondropPuddingWireCodec.NoiseMode =
        MoondropPuddingWireCodec.NoiseMode.BASIC,
) : ProtocolSession {
    init {
        check(ancWriteTier in supportedTiers) { "ANC write tier must be a supported tier" }
    }

    private val decoder = MoondropPuddingWireCodec.Decoder()
    private var handshakeAccepted = false
    private var battery = EarbudBattery()

    override fun initialReadCommands(): List<ByteArray> =
        listOf(MoondropPuddingWireCodec.handshake)

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> =
        if (event === ProtocolEvent.HandshakeAccepted) telemetryQueries()
        else emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> telemetryQueries()
        request is StandardControlRequest.SetNoiseMode -> listOf(
            MoondropPuddingWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> listOf(MoondropPuddingWireCodec.queryBattery)
            NoiseModeFeatureState.FEATURE_ID -> listOf(MoondropPuddingWireCodec.queryNoiseMode)
            else -> emptyList()
        }
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (!handshakeAccepted) {
                if (MoondropPuddingWireCodec.parseHandshake(frame)) {
                    handshakeAccepted = true
                    add(ProtocolEvent.HandshakeAccepted)
                } else if (frame.command == 0x0A && frame.subcommand == 0x83) {
                    add(ProtocolEvent.HandshakeRejected)
                }
                return@forEach
            }

            MoondropPuddingWireCodec.parseBattery(frame)?.let { battery ->
                this@MoondropPuddingProtocolSession.battery =
                    this@MoondropPuddingProtocolSession.battery.merge(battery)
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(this@MoondropPuddingProtocolSession.battery),
                    ),
                )
            }
            MoondropPuddingWireCodec.parseNoiseModeQuery(frame)?.let { wireMode ->
                if (wireMode in supportedTiers) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = supportedNoiseModes,
                        ),
                    )
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            NoiseModeFeatureState(wireMode.toDomainMode()),
                        ),
                    )
                }
            }
            MoondropPuddingWireCodec.parseNoiseModeConfirm(frame)?.let { wireMode ->
                if (wireMode in supportedTiers) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = supportedNoiseModes,
                        ),
                    )
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            NoiseModeFeatureState(wireMode.toDomainMode()),
                        ),
                    )
                }
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakeAccepted = false
        battery = EarbudBattery()
    }

    private fun EarbudBattery.merge(
        report: MoondropPuddingWireCodec.BatteryState,
    ): EarbudBattery = copy(
        left = report.leftPercent?.let { BatteryReading(it, charging = false) } ?: left,
        right = report.rightPercent?.let { BatteryReading(it, charging = false) } ?: right,
        case = report.casePercent?.let { BatteryReading(it, charging = false) } ?: case,
    )

    private fun telemetryQueries(): List<ByteArray> = listOf(
        MoondropPuddingWireCodec.queryBattery,
        MoondropPuddingWireCodec.queryNoiseMode,
    )

    /**
     * The standard ANC switch writes the model's [MoondropPuddingProtocolSession] ANC tier
     * (adaptive `04` on Pudding, basic `01` on MIRAGE); the other ANC-family tier exists only
     * as a report value and projects back onto standard ANC in [toDomainMode].
     */
    private fun NoiseMode.toWireMode(): MoondropPuddingWireCodec.NoiseMode {
        val tier = when (this) {
            NoiseMode.ANC -> ancWriteTier
            NoiseMode.OFF -> MoondropPuddingWireCodec.NoiseMode.OFF
            NoiseMode.TRANSPARENCY -> MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY
            NoiseMode.WIND -> MoondropPuddingWireCodec.NoiseMode.WIND
        }
        check(tier in supportedTiers) { "Noise tier $tier is outside this model's confirmed set" }
        return tier
    }

    private fun MoondropPuddingWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        MoondropPuddingWireCodec.NoiseMode.ADAPTIVE -> NoiseMode.ANC
        MoondropPuddingWireCodec.NoiseMode.BASIC -> NoiseMode.ANC
        MoondropPuddingWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
        MoondropPuddingWireCodec.NoiseMode.WIND -> NoiseMode.WIND
    }

    private val supportedNoiseModes: Set<NoiseMode> =
        supportedTiers.mapTo(mutableSetOf()) { it.toDomainMode() }

    private companion object {
        /**
         * The three-state surface MIRAGE verified on-device: `01` lands on basic ANC there.
         * Wind and the unverified `04` tier stay unrecognized.
         */
        val THREE_STATE_TIERS = setOf(
            MoondropPuddingWireCodec.NoiseMode.OFF,
            MoondropPuddingWireCodec.NoiseMode.BASIC,
            MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY,
        )
    }
}
