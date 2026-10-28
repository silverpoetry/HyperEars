package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleAapWireCodec

/** Apple AAP family selected by its published SDP service UUID, never by the display name alone. */
open class AppleAirPodsAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Apple AirPods"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        L2capEndpointSpec(
            psm = AAP_PSM,
            serviceUuid = AAP_SERVICE_UUID,
            id = "apple-aap-l2cap",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud && identity.serviceUuids.any { uuid ->
            AAP_SERVICE_UUIDS.any { it.equals(uuid, ignoreCase = true) }
        }

    override fun createProtocolSession(): ProtocolSession = AppleAapProtocolSession()

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is AppleListeningModeFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is AppleControlRequest.SetListeningMode &&
                adapter.runtimeState().features.get<AppleListeningModeFeatureState>()
                    ?.supportedModes?.contains(request.mode) == true
        }

    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = AppleMiLinkPresentationIds.LISTENING.takeIf {
            runtimeState().features.get<AppleListeningModeFeatureState>() != null
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is AppleControlRequest.SetListeningMode,
        is StandardControlRequest.SetNoiseMode,
        -> ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    override fun onProtocolReset() {
        super.onProtocolReset()
        removeFeatureState(AppleListeningModeFeatureState.FEATURE_ID)
    }

    companion object {
        const val ID = "apple-airpods-family"
        const val AAP_SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
        const val AAP_PSM = 0x1001
        val AAP_SERVICE_UUIDS = setOf(
            AAP_SERVICE_UUID,
            "2a72e02b-7b99-778f-014d-ad0b7221ec74",
        )
    }
}

class AppleAirPodsProAdapter : AppleAirPodsAdapter() {

    override val id: String = ID
    override val displayName: String = "Apple AirPods Pro"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("airpodspro")

    companion object { const val ID = "apple-airpods-pro" }
}

class AppleAirPodsMaxAdapter : AppleAirPodsAdapter() {

    override val id: String = ID
    override val displayName: String = "Apple AirPods Max"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val formFactor: HeadsetFormFactor = HeadsetFormFactor.HEADPHONES

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("airpodsmax")

    companion object { const val ID = "apple-airpods-max" }
}

private class AppleAapProtocolSession : ProtocolSession {
    private val decoder = AppleAapWireCodec.Decoder()
    private var handshakePublished = false
    private var adaptiveReported = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        AppleAapWireCodec.handshake,
        AppleAapWireCodec.enableSpecificFeatures,
        AppleAapWireCodec.requestNotifications,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> listOf(AppleAapWireCodec.requestNotifications)
        request is StandardControlRequest.SetNoiseMode -> listOf(
            AppleAapWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        request is AppleControlRequest.SetListeningMode -> listOf(
            AppleAapWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is AppleControlRequest.SetListeningMode,
        is StandardControlRequest.SetNoiseMode,
        -> listOf(AppleAapWireCodec.requestNotifications)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap { state ->
            when (state) {
                is AppleAapWireCodec.State.Battery -> buildList<ProtocolEvent> {
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            BatteryFeatureState(EarbudBattery(
                                left = state.left.toDomainReading(),
                                right = state.right.toDomainReading(),
                                case = state.case.toDomainReading(),
                            )),
                        ),
                    )
                    publishHandshakeIfNeeded()
                }

                is AppleAapWireCodec.State.Noise -> buildList<ProtocolEvent> {
                    // A three-state report does not prove adaptive support. Once actually reported,
                    // retain that evidence only for the lifetime of this protocol session.
                    if (state.mode == AppleAapWireCodec.NoiseMode.ADAPTIVE) adaptiveReported = true
                    val supportedModes = BASELINE_LISTENING_MODES +
                        if (adaptiveReported) setOf(AppleListeningMode.ADAPTIVE) else emptySet()
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            AppleListeningModeFeatureState(state.mode.toListeningMode(), supportedModes),
                        ),
                    )
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = THREE_STATE_NOISE_MODES,
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(state.mode.toDomainMode())))
                    publishHandshakeIfNeeded()
                }
            }
        }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        adaptiveReported = false
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireMode(): AppleAapWireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> AppleAapWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> AppleAapWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> AppleAapWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("Apple AAP does not expose a wind-noise mode")
    }

    private fun AppleListeningMode.toWireMode(): AppleAapWireCodec.NoiseMode = when (this) {
        AppleListeningMode.OFF -> AppleAapWireCodec.NoiseMode.OFF
        AppleListeningMode.ANC -> AppleAapWireCodec.NoiseMode.ANC
        AppleListeningMode.TRANSPARENCY -> AppleAapWireCodec.NoiseMode.TRANSPARENCY
        AppleListeningMode.ADAPTIVE -> AppleAapWireCodec.NoiseMode.ADAPTIVE
    }

    private fun AppleAapWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        AppleAapWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        AppleAapWireCodec.NoiseMode.ANC,
        AppleAapWireCodec.NoiseMode.ADAPTIVE,
        -> NoiseMode.ANC

        AppleAapWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private fun AppleAapWireCodec.NoiseMode.toListeningMode(): AppleListeningMode = when (this) {
        AppleAapWireCodec.NoiseMode.OFF -> AppleListeningMode.OFF
        AppleAapWireCodec.NoiseMode.ANC -> AppleListeningMode.ANC
        AppleAapWireCodec.NoiseMode.TRANSPARENCY -> AppleListeningMode.TRANSPARENCY
        AppleAapWireCodec.NoiseMode.ADAPTIVE -> AppleListeningMode.ADAPTIVE
    }

    private fun AppleAapWireCodec.Component.toDomainReading(): BatteryReading =
        BatteryReading(percent, charging)

    private companion object {
        val BASELINE_LISTENING_MODES = setOf(
            AppleListeningMode.OFF,
            AppleListeningMode.ANC,
            AppleListeningMode.TRANSPARENCY,
        )
        val THREE_STATE_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
