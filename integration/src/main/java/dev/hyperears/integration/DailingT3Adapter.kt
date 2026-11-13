package dev.hyperears.integration

/** Exact retail-name candidate; every private feature requires its own valid device report. */
class DailingT3Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "戴灵 T3"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities = EarbudCapabilities(battery = true, audioHandoff = true)
    override val controlApps = listOf(ControlAppCatalog.dailing)
    override val transports: List<EarbudTransportSpec> = listOf(
        // Captured vendor endpoint. Shared SPP never establishes a device's brand.
        RfcommEndpointSpec.Channel(number = 1, secure = false),
    )

    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state ->
        state is DailingGameModeFeatureState
    }
    override val controlRequestContract = StandardControlRequestContract.extending { adapter, request ->
        request is DailingControlRequest.SetGameMode &&
            adapter.runtimeState().features.get<DailingGameModeFeatureState>() != null
    }
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = DailingMiLinkPresentationIds.CARD.takeIf {
            effectiveCapabilities().noiseControl &&
                runtimeState().features.get<DailingGameModeFeatureState>() != null
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    override fun createProtocolSession(): ProtocolSession = DailingT3ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is DailingControlRequest.SetGameMode ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    override fun onProtocolReset() {
        super.onProtocolReset()
        removeFeatureState(DailingGameModeFeatureState.FEATURE_ID)
    }

    companion object {
        const val ID = "dailing-t3"
        private val MODEL_NAMES = setOf("dailingt3", "戴灵t3")
    }
}
