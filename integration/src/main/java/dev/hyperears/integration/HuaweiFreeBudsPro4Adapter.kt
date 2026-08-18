package dev.hyperears.integration

/**
 * Concrete adapter for the Huawei FreeBuds Pro 4.
 *
 * The FreeBuds Pro 4 uses the same SPP protocol as the FreeBuds Pro 3 (shared codec,
 * same RFCOMM channel 1, identical noise-mode and ANC-level semantics). The adapter
 * delegates all protocol behaviour to [HuaweiFreebudsPro3ProtocolSession] and exists
 * as a distinct UI entry so users can identify and manage it independently in the
 * adapter settings list.
 *
 * Evidence: OpenFreebuds OfbDriverHuaweiPro3 (shared with Pro 3/4).
 */
class HuaweiFreeBudsPro4Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds Pro 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = 1,
            id = "huawei-freebuds-pro4-spp",
        ),
    )

    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is HuaweiAncLevelFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is HuaweiControlRequest.SetAncLevel &&
                adapter.effectiveCapabilities().noiseControl &&
                request.level in (
                    adapter.runtimeState().features
                        .get<HuaweiAncLevelFeatureState>()
                        ?.supported
                        .orEmpty()
                ) &&
                adapter.runtimeState().noiseMode == request.level.domainNoiseMode()
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf(
                "huaweifreebudspro4",
                "freebudspro4",
            )

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsPro3ProtocolSession()

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is HuaweiControlRequest.SetAncLevel ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    companion object {
        const val ID = "huawei-freebuds-pro4"
    }
}
