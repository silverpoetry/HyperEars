package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleAapWireCodec
import dev.hyperears.protocol.apple.AppleProximityKeys
import dev.hyperears.protocol.apple.AppleProximityReport

/** Apple AAP family selected by its published SDP service UUID, never by the display name alone. */
open class AppleAirPodsAdapter : StandardEarbudAdapter() {
    private var proximityBatteryConfirmed = false
    private var aacpBattery: EarbudBattery? = null
    override val batterySource: BatterySource
        get() = if (proximityBatteryConfirmed) BatterySource.PRIVATE_PROTOCOL else BatterySource.SYSTEM_AGGREGATE

    override val id: String = ID
    override val displayName: String = "Apple AirPods"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.andPods, ControlAppCatalog.librePods)
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

    /** Host-local callback only; install after paired/native ownership admission and raw-log redaction. */
    var privateKeyReceiver: ((AppleProximityKeys) -> Unit)? = null

    var privateMotionReceiver: ((ByteArray) -> Unit)? = null

    override fun createProtocolSession(): ProtocolSession = AppleAapProtocolSession(
        onPrivateKeys = { keys -> privateKeyReceiver?.invoke(keys) ?: keys.close() },
        onPrivateMotion = { frame -> privateMotionReceiver?.invoke(frame) },
    )

    /** Only the current admitted host session publishes renderer readback here. */
    fun publishSpatialHostState(state: AppleSpatialFeatureState): Boolean {
        if (privateMotionReceiver == null) return false
        val current = runtimeState()
        return adoptRuntimeState(current.copy(features = current.features.update(state)))
    }

    /** Host calls only while this admitted session owns the private transport. */
    fun proximityBootstrapCommands(): List<ByteArray> = if (privateKeyReceiver == null) emptyList()
        else listOf(AppleProximityKeys.request(1), AppleProximityKeys.request(4))

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is AppleDeviceInformationFeatureState || state is AppleListeningModeFeatureState || state is AppleSettingsFeatureState ||
                state is AppleWearFeatureState || state is AppleConversationFeatureState ||
                state is AppleStemFeatureState || state is AppleGestureSettingsFeatureState ||
                state is AppleEqualizerFeatureState || state is AppleCaseFeatureState || state is AppleSpatialFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            val features = adapter.runtimeState().features
            when (request) {
                is AppleHostLongPressLeaseRequest -> features.get<AppleGestureSettingsFeatureState>()?.interceptionMask != null
                is AppleControlRequest.SetListeningMode ->
                    features.get<AppleListeningModeFeatureState>()?.supportedModes?.contains(request.mode) == true
                is AppleControlRequest.SetLongPress, is AppleControlRequest.SetListeningCycle ->
                    features.get<AppleGestureSettingsFeatureState>()?.supports(
                        request,
                        features.get<AppleListeningModeFeatureState>()?.supportedModes.orEmpty(),
                    ) == true
                is AppleSpatialControlRequest -> features.get<AppleSpatialFeatureState>()?.let { spatial ->
                    when (request) {
                        is AppleSpatialControlRequest.SetMode -> request.mode == AppleSpatialMode.OFF ||
                            spatial.rendererAvailable && (request.mode == AppleSpatialMode.FIXED || spatial.trackerAvailable && spatial.calibrated)
                        AppleSpatialControlRequest.BeginCalibration -> spatial.trackerAvailable
                        AppleSpatialControlRequest.CalibrationStep -> spatial.calibrationStep in 0..2
                        AppleSpatialControlRequest.Recenter -> spatial.mode == AppleSpatialMode.TRACKING
                    }
                } == true
                is AppleControlRequest.SetEqualizer -> features.get<AppleEqualizerFeatureState>() != null && request.valid()
                is AppleControlRequest -> features.get<AppleSettingsFeatureState>()?.supports(
                    request, features.get<AppleListeningModeFeatureState>()?.mode,
                ) == true
                else -> false
            }
        }

    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = when {
            runtimeState().features.get<AppleListeningModeFeatureState>() != null -> AppleMiLinkPresentationIds.LISTENING
            runtimeState().features.values.any { state ->
                state is AppleSettingsFeatureState || state is AppleWearFeatureState ||
                    state is AppleConversationFeatureState || (state is AppleGestureSettingsFeatureState &&
                    (state.leftLongPress != null || state.rightLongPress != null || state.listeningCycleMask in 1..15)) ||
                    state is AppleEqualizerFeatureState || (state is AppleSpatialFeatureState && state.rendererAvailable)
            } -> AppleMiLinkPresentationIds.SETTINGS
            else -> null
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is AppleControlRequest,
        is StandardControlRequest.SetNoiseMode,
        -> ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    override fun onFeatureReported(state: DeviceFeatureState, scope: AdapterEventScope): FeatureReportDecision {
        if (state is BatteryFeatureState) aacpBattery = state.battery
        return super.onFeatureReported(state, scope)
    }

    /** Called only after host identity/ownership authentication. Approximate nibbles never masquerade as precise values. */
    fun acceptProximityReport(report: AppleProximityReport): Boolean {
        val current = runtimeState()
        val sequence = current.features.get<AppleCaseFeatureState>()?.sequence ?: 0
        var features = current.features.update(AppleCaseFeatureState(report.lidOpen, sequence))
        if (report.precise) {
            fun merge(component: AppleAapWireCodec.Component, authoritative: BatteryReading?): BatteryReading =
                authoritative?.takeIf { it.available } ?: BatteryReading(component.percent, component.charging)
            val battery = EarbudBattery(
                left = merge(report.left, aacpBattery?.left),
                right = merge(report.right, aacpBattery?.right),
                case = merge(report.case, aacpBattery?.case),
            )
            features = features.update(BatteryFeatureState(battery))
            proximityBatteryConfirmed = true
        }
        return adoptRuntimeState(current.copy(features = features))
    }

    /** Confirmation happens after the complete BLE batch and settle interval, never from the raw lid bit. */
    fun acceptProximityCaseOpen(): Boolean {
        val current = runtimeState()
        val lid = current.features.get<AppleCaseFeatureState>() ?: return false
        if (!lid.isOpen) return false
        return adoptRuntimeState(current.copy(features = current.features.update(lid.copy(sequence = lid.sequence + 1))))
    }

    override fun onProtocolReset() {
        super.onProtocolReset()
        proximityBatteryConfirmed = false
        aacpBattery = null
        removeFeatureState(AppleDeviceInformationFeatureState.FEATURE_ID)
        removeFeatureState(AppleCaseFeatureState.FEATURE_ID)
        removeFeatureState(AppleSpatialFeatureState.FEATURE_ID)
        removeFeatureState(AppleListeningModeFeatureState.FEATURE_ID)
        removeFeatureState(AppleSettingsFeatureState.FEATURE_ID)
        removeFeatureState(AppleWearFeatureState.FEATURE_ID)
        removeFeatureState(AppleConversationFeatureState.FEATURE_ID)
        removeFeatureState(AppleStemFeatureState.FEATURE_ID)
        removeFeatureState(AppleGestureSettingsFeatureState.FEATURE_ID)
        removeFeatureState(AppleEqualizerFeatureState.FEATURE_ID)
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

private class AppleAapProtocolSession(
    onPrivateKeys: (AppleProximityKeys) -> Unit,
    onPrivateMotion: (ByteArray) -> Unit,
) : ProtocolSession {
    private val decoder = AppleAapWireCodec.Decoder(onPrivateKeys, onPrivateMotion)
    private var handshakePublished = false
    private var adaptiveReported = false
    private var adaptiveModel = false
    private var lastNoiseReport: AppleAapWireCodec.NoiseMode? = null
    private var settings = AppleSettingsFeatureState()
    private var gestures = AppleGestureSettingsFeatureState()
    private var equalizer: AppleEqualizerFeatureState? = null
    private var stemSequence = 0L
    private val settingPayloads = mutableMapOf<Int, List<Int>>()
    private val hostLongPressLease = AppleHostLongPressWireLease()

    override fun initialReadCommands(): List<ByteArray> = listOf(
        AppleAapWireCodec.handshake,
        AppleAapWireCodec.enableSpecificFeatures,
        AppleAapWireCodec.requestNotifications,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request is AppleHostLongPressLeaseRequest -> hostLongPressLease.change(request, settingPayloads[0x39])
            ?.let { listOf(AppleAapWireCodec.setControl(0x39, it)) }.orEmpty()
        request === StandardControlRequest.Refresh -> listOf(AppleAapWireCodec.requestNotifications)
        request is StandardControlRequest.SetNoiseMode -> listOf(
            AppleAapWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        request is AppleControlRequest.SetListeningMode -> listOf(
            AppleAapWireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        request is AppleControlRequest.SetEqualizer -> if (equalizer != null && request.valid())
            listOf(AppleAapWireCodec.setEqualizer(request.state, request.low, request.mid, request.high)) else emptyList()
        request is AppleControlRequest.SetLongPress || request is AppleControlRequest.SetListeningCycle ->
            encodeGesture(request)
        request is AppleControlRequest -> encodeSetting(request)
        else -> emptyList()
    }

    private fun encodeGesture(request: AppleControlRequest): List<ByteArray> {
        val modes = if (lastNoiseReport == null) emptySet() else supportedListeningModes()
        if (!gestures.supports(request, modes)) return emptyList()
        val (identifier, index, value) = when (request) {
            is AppleControlRequest.SetLongPress -> Triple(0x16, if (request.bud == AppleBud.RIGHT) 0 else 1, request.action.wireValue)
            is AppleControlRequest.SetListeningCycle -> Triple(0x1a, 0, request.mask)
            else -> return emptyList()
        }
        val payload = settingPayloads[identifier]?.toMutableList() ?: return emptyList()
        payload[index] = value
        return listOf(AppleAapWireCodec.setControl(identifier, payload))
    }

    private fun encodeSetting(request: AppleControlRequest): List<ByteArray> {
        if (!settings.supports(request, lastNoiseReport?.toListeningMode())) return emptyList()
        val write = request.settingWrite() ?: return emptyList()
        val previous = settingPayloads[write.identifier] ?: return emptyList()
        // Adaptive strength is deliberately zero padded in the corroborating active source.
        val payload = if (write.identifier == 0x2e) listOf(write.value, 0, 0, 0)
            else previous.toMutableList().also { it[write.index] = write.value }
        return listOf(AppleAapWireCodec.setControl(write.identifier, payload))
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is AppleHostLongPressLeaseRequest,
        is AppleControlRequest,
        is StandardControlRequest.SetNoiseMode,
        -> listOf(AppleAapWireCodec.requestNotifications)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap { state ->
            when (state) {
                is AppleAapWireCodec.State.Control -> buildList<ProtocolEvent> {
                    gestures.acceptReport(state)?.let { accepted ->
                        gestures = accepted
                        settingPayloads[state.identifier] = state.values.toList()
                        add(ProtocolEvent.FeatureStateChanged(accepted))
                        publishHandshakeIfNeeded()
                    }
                    settings.acceptReport(state)?.let { accepted ->
                        settings = accepted
                        settingPayloads[state.identifier] = state.values.toList()
                        add(ProtocolEvent.FeatureStateChanged(accepted))
                        publishHandshakeIfNeeded()
                    }
                }

                is AppleAapWireCodec.State.Wear -> telemetry(AppleWearFeatureState(
                    AppleWearPosition.entries[state.primary], AppleWearPosition.entries[state.secondary],
                ))
                is AppleAapWireCodec.State.Conversation -> telemetry(AppleConversationFeatureState(state.subtype, state.status))
                is AppleAapWireCodec.State.Stem -> telemetry(AppleStemFeatureState(
                    AppleStemPress.entries[state.press - 5], AppleBud.entries[state.bud - 1], ++stemSequence,
                ))
                is AppleAapWireCodec.State.Equalizer -> {
                    val accepted = AppleEqualizerFeatureState(state.state, state.low, state.mid, state.high)
                    equalizer = accepted
                    telemetry(accepted)
                }
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

                is AppleAapWireCodec.State.DeviceInformation -> buildList<ProtocolEvent> {
                    add(ProtocolEvent.FeatureStateChanged(AppleDeviceInformationFeatureState(state.reportedName, state.modelIdentifier, state.firmwareActive)))
                    adaptiveModel = state.modelIdentifier in SOURCE_ADAPTIVE_MODELS
                    // Identity alone never establishes live listening controls or native admission.
                    lastNoiseReport?.let { add(listeningEvent(it)) }
                }

                is AppleAapWireCodec.State.Noise -> buildList<ProtocolEvent> {
                    lastNoiseReport = state.mode
                    if (state.mode == AppleAapWireCodec.NoiseMode.ADAPTIVE) adaptiveReported = true
                    add(listeningEvent(state.mode))
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

    private fun telemetry(state: DeviceFeatureState): List<ProtocolEvent> = buildList {
        add(ProtocolEvent.FeatureStateChanged(state))
        publishHandshakeIfNeeded()
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        adaptiveReported = false
        adaptiveModel = false
        lastNoiseReport = null
        settings = AppleSettingsFeatureState()
        gestures = AppleGestureSettingsFeatureState()
        equalizer = null
        stemSequence = 0L
        settingPayloads.clear()
        hostLongPressLease.clear()
    }

    private fun listeningEvent(mode: AppleAapWireCodec.NoiseMode): ProtocolEvent =
        ProtocolEvent.FeatureStateChanged(
            AppleListeningModeFeatureState(
                mode.toListeningMode(),
                supportedListeningModes(),
            ),
        )

    private fun supportedListeningModes(): Set<AppleListeningMode> = BASELINE_LISTENING_MODES +
        if (adaptiveReported || adaptiveModel) setOf(AppleListeningMode.ADAPTIVE) else emptySet()

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
        // Exact modelIdentifier declarations from LibrePods' model registry, corroborating
        // the AndPods identity schema. Source support is conditional on a same-session live report.
        // See docs/research/source-audits/apple-device-identity-followup.md; not hardware validation.
        val SOURCE_ADAPTIVE_MODELS = setOf(
            "A3056", "A3055", "A3057", // AirPods 4 ANC
            "A3531", "A3532", "A3533", // AirPods 5
            "A3439", "A3440", "A3441", // AirPods 5 wireless case
            "A2931", "A2699", "A2698", // Pro 2 Lightning
            "A3047", "A3048", "A3049", // Pro 2 USB-C
            "A3063", "A3064", "A3065", // Pro 3
        )
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
