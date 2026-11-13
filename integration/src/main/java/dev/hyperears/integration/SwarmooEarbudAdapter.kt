package dev.hyperears.integration

import dev.hyperears.protocol.swarmoo.SwarmooMo3WireCodec

/** Unknown SWARMOO models retain standard integration and the vendor-app navigation target. */
open class SwarmooEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = "swarmoo-family"
    override val displayName: String = "SWARMOO headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.tigerListening)

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()).startsWith("swarmoo")
}

/** Exact MO3/MO3 PRO candidate; shared FFF0/2A19 UUIDs never identify a brand. */
class SwarmooMo3ProAdapter : SwarmooEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "SWARMOO MO3 PRO"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = PRESENTATION_ID.takeIf { effectiveCapabilities().windNoiseControl }
    override val transports: List<EarbudTransportSpec> = listOf(
        GattTransportSpec(
            serviceUuid = SwarmooMo3WireCodec.SERVICE_UUID,
            writeCharacteristicUuid = SwarmooMo3WireCodec.MODE_UUID,
            notifyCharacteristicUuid = SwarmooMo3WireCodec.MODE_UUID,
            notificationsRequired = true,
            minimumMtu = 26, // Complete 23-byte mode notification plus the ATT header.
            minimumWriteIntervalMs = WRITE_INTERVAL_MS,
            additionalRoutes = listOf(
                GattRouteSpec(
                    id = SwarmooMo3ProtocolSession.BATTERY_ROUTE,
                    writeCharacteristicUuid = SwarmooMo3WireCodec.BATTERY_UUID,
                    notifyCharacteristicUuid = SwarmooMo3WireCodec.BATTERY_UUID,
                ),
            ),
            id = "swarmoo-mo3-gatt",
        ),
    )

    private var expectedMode: NoiseMode? = null
    private var settleAttempt = 0

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf("swarmoomo3", "swarmoomo3pro")

    override fun createProtocolSession(): ProtocolSession = SwarmooMo3ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        ControlExecutionPolicy(
            confirmation = ControlConfirmationPolicy.DEVICE_REPORT,
            commandGapMs = WRITE_INTERVAL_MS,
            readbackDelayMs = 300L,
        )

    override fun permitsUnconfirmedGattWrite(command: ByteArray, route: String?, status: Int): Boolean =
        status == 1 && when (route) {
            null -> SwarmooMo3WireCodec.isKnownCommand(command)
            SwarmooMo3ProtocolSession.BATTERY_ROUTE -> command.contentEquals(SwarmooMo3WireCodec.query)
            else -> false
        }

    override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
        val mode = (request as? StandardControlRequest.SetNoiseMode)?.mode ?: return
        expectedMode = mode
        settleAttempt = 0
        scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
    }

    override fun onFeatureReported(state: DeviceFeatureState, scope: AdapterEventScope): FeatureReportDecision {
        val mode = (state as? NoiseModeFeatureState)?.mode ?: return FeatureReportDecision.ACCEPT
        val expected = expectedMode ?: return FeatureReportDecision.ACCEPT
        if (mode == expected || settleAttempt >= SETTLE_DELAYS.size) {
            expectedMode = null
            settleAttempt = 0
            scope.cancelStateRequest(NoiseModeFeatureState.FEATURE_ID)
            return FeatureReportDecision.ACCEPT
        }
        scope.requestState(NoiseModeFeatureState.FEATURE_ID, SETTLE_DELAYS[settleAttempt++])
        return FeatureReportDecision.HOLD
    }

    override fun onProtocolReset() {
        expectedMode = null
        settleAttempt = 0
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.FallbackTo(SwarmooEarbudAdapter())

    companion object {
        const val ID = "swarmoo-mo3-pro"
        val PRESENTATION_ID = MiLinkCardPresentationId(ID)
        private const val WRITE_INTERVAL_MS = 300L
        private val SETTLE_DELAYS = longArrayOf(250L, 500L, 700L)
    }
}
