package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropRobinWireCodec

/** Standard Bluetooth fallback for recognizable MOONDROP headsets without a verified codec. */
open class MoondropEarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "MOONDROP headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return "moondrop" in name || "水月雨" in name || name in UNBRANDED_MODEL_NAMES
    }

    companion object {
        const val ID = "moondrop-family"
        private val UNBRANDED_MODEL_NAMES = setOf("spacetravel2ultra")
    }
}

/**
 * Protocol adapter for the verified Bluetooth name "Robin's Earphones".
 *
 * Name matching selects this single model. The standard SPP UUID is only an RFCOMM endpoint and
 * never participates in device identity. Private battery and ANC capabilities stay unavailable
 * until the strict protocol handshake and corresponding read responses are observed.
 */
class MoondropRobinAdapter : MoondropEarbudAdapter() {
    private var batteryBootstrapAttempt = 0
    private var privateBatteryCommitted = false
    private var expectedNoiseMode: NoiseMode? = null
    private var noiseModeAttempt = 0

    override val id: String = ID
    override val displayName: String = "MOONDROP Robin"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = STANDARD_SPP_UUID,
            id = "moondrop-robin-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name in EXACT_NAMES ||
            ("moondrop" in name && "robin" in name) ||
            ("水月雨" in name && "知更鸟" in name)
    }

    override fun createProtocolSession(): ProtocolSession = MoondropRobinProtocolSession()

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
        val left = battery.left.percent
        val right = battery.right.percent
        val provisional = left == null || right == null || left == 0 || right == 0
        if (!provisional || batteryBootstrapAttempt >= BATTERY_BOOTSTRAP_DELAYS_MS.size) {
            privateBatteryCommitted = true
            scope.cancelStateRequest(BatteryFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }

        val delayMs = BATTERY_BOOTSTRAP_DELAYS_MS[batteryBootstrapAttempt++]
        scope.requestState(BatteryFeatureState.FEATURE_ID, delayMs)
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
        const val ID = "moondrop-robin"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
        internal const val INITIAL_MODE_QUERY_DELAY_MS = 600L
        internal val BATTERY_BOOTSTRAP_DELAYS_MS = longArrayOf(500L, 800L, 1_200L, 1_600L)
        internal val MODE_CONFIRMATION_DELAYS_MS = longArrayOf(500L, 700L, 900L, 1_200L)

        private val EXACT_NAMES = setOf(
            "robinsearphones",
            "moondroprobin",
            "moondroprobinsearphones",
            "水月雨知更鸟",
        )
    }
}

internal class MoondropRobinProtocolSession : ProtocolSession {
    private val decoder = MoondropRobinWireCodec.Decoder()
    private var handshakeAccepted = false

    override fun initialReadCommands(): List<ByteArray> =
        listOf(MoondropRobinWireCodec.handshake)

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> =
        if (event === ProtocolEvent.HandshakeAccepted) telemetryQueries() else emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> telemetryQueries()
        request is StandardControlRequest.SetNoiseMode -> listOf(
            MoondropRobinWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> listOf(MoondropRobinWireCodec.queryBattery)
            NoiseModeFeatureState.FEATURE_ID -> listOf(MoondropRobinWireCodec.queryNoiseMode)
            else -> emptyList()
        }
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (!handshakeAccepted) {
                if (MoondropRobinWireCodec.parseHandshake(frame)) {
                    handshakeAccepted = true
                    add(ProtocolEvent.HandshakeAccepted)
                } else if (frame.command == 0x0A && frame.subcommand == 0x83) {
                    add(ProtocolEvent.HandshakeRejected)
                }
                return@forEach
            }

            MoondropRobinWireCodec.parseBattery(frame)?.let { battery ->
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(battery.leftPercent, charging = false),
                                right = BatteryReading(battery.rightPercent, charging = false),
                            ),
                        ),
                    ),
                )
            }
            MoondropRobinWireCodec.parseNoiseMode(frame)?.let { mode ->
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = SUPPORTED_NOISE_MODES,
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
    }

    private fun telemetryQueries(): List<ByteArray> = listOf(
        MoondropRobinWireCodec.queryBattery,
        MoondropRobinWireCodec.queryNoiseMode,
    )

    private fun NoiseMode.toWireMode(): MoondropRobinWireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> MoondropRobinWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> MoondropRobinWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> MoondropRobinWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("Robin does not support wind-noise control")
    }

    private fun MoondropRobinWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        MoondropRobinWireCodec.NoiseMode.ANC -> NoiseMode.ANC
        MoondropRobinWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        MoondropRobinWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private companion object {
        val SUPPORTED_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
