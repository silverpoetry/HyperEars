package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropZzzAngelsWireCodec

/**
 * Concrete adapter for the MOONDROP PILL (水月雨「药丸」耳夹款).
 *
 * Verified device facts (2026-11-12, firmware 1.6.0, Xiaomi 13 / Android 17 / HyperOS
 * OS4.0.6.0.XPBCNXM):
 *
 * - Bluetooth name `MOONDROP PILL`, normalised to `moondroppill`; the SDP record advertises
 *   the standard SPP service alongside A2DP `110b`, AVRCP `110e` and HFP `111e`, and the
 *   official MOONDROP application's captured session runs on that RFCOMM channel (DLCI 2);
 * - the device class is `0x240404` (Audio/Video, Wearable Headset) and the identity is DUAL;
 *   unlike ZZZ-ANGELS-OWS no separate `-LEA` broadcast name was observed, so no LEA exclusion
 *   is claimed here;
 * - the model is an open-ear (OWS) clip headset without noise cancellation: the official
 *   application's device page exposes only gain presets, scene presets and interactive DSP,
 *   and a 151-frame capture across three sessions contains zero noise commands;
 * - battery telemetry covers the left and right buds only: the supported-component response
 *   is `01 02` and battery queries answer with two pairs.
 *
 * PILL is the base model of the ZZZ-ANGELS-OWS collaboration edition and speaks the same
 * MOONDROP GAIA subset: the API-version handshake and the battery query/response are
 * byte-identical to the ZZZ-ANGELS-OWS capture. This adapter therefore reuses
 * [MoondropZzzAngelsWireCodec] and [MoondropZzzAngelsBatteryPolicy] unchanged, and leaves the
 * already revalidated ZZZ-ANGELS-OWS adapter and session untouched.
 *
 * Like the rest of the family, the adapter starts from Android's standard projection and
 * opens private battery only after a legal battery response. See
 * `docs/moondrop-pill-protocol.md` for the evidence trail.
 */
class MoondropPillAdapter : MoondropEarbudAdapter() {
    private val batteryPolicy = MoondropZzzAngelsBatteryPolicy()

    override val id: String = ID
    override val displayName: String = "MOONDROP PILL"
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
            id = "moondrop-pill-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name in EXACT_NAMES
    }

    override fun createProtocolSession(): ProtocolSession = MoondropPillProtocolSession()

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
        const val ID = "moondrop-pill"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

        /** Only the captured retail name is claimed; wider aliases await device evidence. */
        private val EXACT_NAMES = setOf(
            "moondroppill",
        )
    }
}

/**
 * One PILL conversation: family handshake, battery queries and per-component merging.
 * The model has no noise commands, so no noise query or write is ever produced.
 */
internal class MoondropPillProtocolSession : ProtocolSession {
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