package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropZzzAngelsWireCodec

/**
 * Concrete adapter for the MOONDROP ZZZ-ANGELS-OWS (水月雨 × 绝区零「妄想天使·闪亮登场」耳夹款).
 *
 * Verified device facts (2026-10-20, firmware 1.2.0):
 *
 * - Bluetooth name `ZZZ-ANGELS-OWS`; the SDP record advertises the standard SPP service,
 *   which is also the channel the official MOONDROP Link application's captured session
 *   uses for the shared MOONDROP GAIA wire family;
 * - the earbuds additionally broadcast an unusable `ZZZ-ANGELS-OWS-LEA` LE Audio identity
 *   that this adapter must never claim;
 * - the model is an open-ear (OWS) clip headset without noise cancellation: neither the
 *   official application nor the capture contains any noise command;
 * - battery telemetry covers the left and right buds only (the device reports the supported
 *   component set `01 02` and answers battery queries with two pairs).
 *
 * Like Robin and Pudding, the adapter starts from Android's standard projection and opens
 * private battery only after a legal battery response. See
 * `docs/moondrop-zzz-angels-protocol.md` for the evidence trail.
 */
class MoondropZzzAngelsAdapter : MoondropEarbudAdapter() {
    private val batteryPolicy = MoondropZzzAngelsBatteryPolicy()

    override val id: String = ID
    override val displayName: String = "MOONDROP ZZZ-ANGELS-OWS"
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
            id = "moondrop-zzz-angels-ows-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        if (name.endsWith(LEA_SUFFIX)) return false
        return name in EXACT_NAMES
    }

    override fun createProtocolSession(): ProtocolSession = MoondropZzzAngelsProtocolSession()

    /**
     * Holds provisional battery reports until both earbuds are known or the bounded bootstrap
     * sequence is exhausted; standard Android battery remains available while held.
     */
    override fun onFeatureReported(
        state: DeviceFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision = when (state) {
        is BatteryFeatureState -> batteryPolicy.decide(state.battery, scope)
        else -> FeatureReportDecision.ACCEPT
    }

    override fun onProtocolReset() {
        batteryPolicy.reset()
    }

    /** A known exact model keeps the normal bounded retry and dormant wake path. */
    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "moondrop-zzz-angels-ows"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

        private const val LEA_SUFFIX = "lea"

        private val EXACT_NAMES = setOf(
            "zzzangelsows",
            "moondropzzzangelsows",
        )
    }
}

/**
 * One ZZZ-ANGELS-OWS conversation: family handshake, battery queries and per-component
 * merging. The model has no noise commands, so no noise query or write is ever produced.
 */
internal class MoondropZzzAngelsProtocolSession : ProtocolSession {
    private val decoder = MoondropZzzAngelsWireCodec.Decoder()
    private var handshakeAccepted = false
    private var battery = EarbudBattery()

    override fun initialReadCommands(): List<ByteArray> =
        listOf(MoondropZzzAngelsWireCodec.handshake)

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> =
        if (event === ProtocolEvent.HandshakeAccepted) telemetryQueries()
        else emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> telemetryQueries()
        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> listOf(MoondropZzzAngelsWireCodec.queryBattery)
            else -> emptyList()
        }
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (!handshakeAccepted) {
                if (MoondropZzzAngelsWireCodec.parseHandshake(frame)) {
                    handshakeAccepted = true
                    add(ProtocolEvent.HandshakeAccepted)
                } else if (MoondropZzzAngelsWireCodec.isHandshakeFailure(frame)) {
                    add(ProtocolEvent.HandshakeRejected)
                }
                return@forEach
            }

            MoondropZzzAngelsWireCodec.parseBattery(frame)?.let { report ->
                battery = battery.merge(report)
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(battery),
                    ),
                )
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakeAccepted = false
        battery = EarbudBattery()
    }

    /** Absent components keep their value; an explicitly unknown reading clears stale state. */
    private fun EarbudBattery.merge(
        report: MoondropZzzAngelsWireCodec.BatteryState,
    ): EarbudBattery = copy(
        left = report.left?.let { BatteryReading(it.percent, charging = false) } ?: left,
        right = report.right?.let { BatteryReading(it.percent, charging = false) } ?: right,
        case = report.chargingCase?.let { BatteryReading(it.percent, charging = false) } ?: case,
    )

    private fun telemetryQueries(): List<ByteArray> =
        listOf(MoondropZzzAngelsWireCodec.queryBattery)
}
