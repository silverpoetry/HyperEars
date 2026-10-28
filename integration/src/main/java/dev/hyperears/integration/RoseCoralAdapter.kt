package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseCoralWireCodec

/** Exact name/config candidate. Source reconstruction does not replace device revalidation. */
class RoseCoralAdapter : RoseEarbudAdapter() {
    private val findCleanup = OwnedProtocolCleanup("rose.coral.find") {
        protocolSession.encode(RoseCoralControlRequest.SetFind(RoseCoralFindTarget.STOP))
    }
    override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
        if (request is RoseCoralControlRequest.SetFind) findCleanup.written(request.target != RoseCoralFindTarget.STOP, scope)
    }
    override fun onFeatureReported(state: DeviceFeatureState, scope: AdapterEventScope): FeatureReportDecision {
        if (state is RoseCoralFindFeatureState && state.left == false && state.right == false) findCleanup.reportedStopped(scope)
        return super.onFeatureReported(state, scope)
    }
    override fun prepareProtocolRelease() = findCleanup.release()
    override fun expireProtocolWorkflow(key: String, lease: Long) = findCleanup.expire(key, lease)
    override val id = ID
    override val displayName = "ROSE Coral"
    override val resolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired = true
    override val transportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = when {
            effectiveCapabilities().noiseControl -> PRESENTATION_ID
            runtimeState().features.values.any { state ->
                state is RoseCoralSettingsFeatureState || state is RoseCoralTouchFeatureState ||
                    state is RoseCoralFindFeatureState || state is RoseCoralInfoFeatureState ||
                    state is RoseCoralAudioFeatureState
            } -> SETTINGS_PRESENTATION_ID
            else -> null
        }
    override val transports = listOf(RfcommEndpointSpec.ServiceUuid(
        uuid = RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID, id = ID,
    ))
    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()) == "rosecoral"

    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state ->
        state is RoseCoralSettingsFeatureState || state is RoseCoralTouchFeatureState ||
            state is RoseCoralFindFeatureState || state is RoseCoralInfoFeatureState ||
            state is RoseCoralAudioFeatureState
    }
    override val controlRequestContract = StandardControlRequestContract.extending { adapter, request ->
        val features = adapter.runtimeState().features
        val settings = features.get<RoseCoralSettingsFeatureState>()
        when (request) {
            is RoseCoralControlRequest.SetAudioMode -> features.get<RoseCoralAudioFeatureState>() != null
            is RoseCoralControlRequest.SetGameMode -> settings?.gameMode != null
            is RoseCoralControlRequest.SetMultipoint -> settings?.multipoint != null
            is RoseCoralControlRequest.SetBassLevel -> settings?.bassLevel != null && request.level in 0..2
            is RoseCoralControlRequest.SetPromptVolume -> settings?.promptVolume != null && request.level in 1..5
            is RoseCoralControlRequest.SetTouchEnabled -> settings?.touchEnabled != null
            is RoseCoralControlRequest.SetTouch -> features.get<RoseCoralTouchFeatureState>() != null
            is RoseCoralControlRequest.SetFind -> features.get<RoseCoralFindFeatureState>() != null
            else -> false
        }
    }
    override fun createProtocolSession(): ProtocolSession = RoseCoralProtocolSession()
    override fun controlPolicy(request: ControlRequest) = ControlExecutionPolicy()
    override fun onProtocolReset() {
        findCleanup.reset()
        removeFeatureState(RoseCoralSettingsFeatureState.FEATURE_ID)
        removeFeatureState(RoseCoralTouchFeatureState.FEATURE_ID)
        removeFeatureState(RoseCoralFindFeatureState.FEATURE_ID)
        removeFeatureState(RoseCoralInfoFeatureState.FEATURE_ID)
        removeFeatureState(RoseCoralAudioFeatureState.FEATURE_ID)
    }
    companion object {
        const val ID = "rose-coral-rs-common-v2"
        val PRESENTATION_ID = MiLinkCardPresentationId(ID)
        val SETTINGS_PRESENTATION_ID = MiLinkCardPresentationId("$ID-settings")
    }
}

private class RoseCoralProtocolSession : ProtocolSession {
    private val decoder = RoseCoralWireCodec.Decoder()
    private var sequence = 0
    private var handshake = false
    private var settings = RoseCoralSettingsFeatureState()
    private var find = RoseCoralFindFeatureState()
    private fun nextSequence() = sequence.also { sequence = (sequence + 1) and 255 }
    private fun command(vararg payload: Int) = RoseCoralWireCodec.command(nextSequence(), payload.map(Int::toByte).toByteArray())
    override fun initialReadCommands() = listOf(RoseCoralWireCodec.query(nextSequence()))

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> initialReadCommands()
        is RoseCoralControlRequest.SetAudioMode -> listOf(command(0x2B, request.mode.wire))
        is StandardControlRequest.SetNoiseMode -> when (request.mode) {
            NoiseMode.ANC -> listOf(command(9, 1))
            NoiseMode.OFF -> listOf(command(9, 2))
            else -> emptyList()
        }
        is RoseCoralControlRequest.SetGameMode -> listOf(command(0x0E, if (request.enabled) 1 else 0))
        is RoseCoralControlRequest.SetMultipoint -> listOf(command(0x32, if (request.enabled) 1 else 0))
        is RoseCoralControlRequest.SetBassLevel -> if (request.level in 0..2) listOf(command(0x2A, 5, request.level)) else emptyList()
        is RoseCoralControlRequest.SetPromptVolume -> if (request.level in 1..5) listOf(command(0x2E, request.level)) else emptyList()
        is RoseCoralControlRequest.SetTouchEnabled -> listOf(command(7, if (request.enabled) 1 else 0))
        is RoseCoralControlRequest.SetTouch -> listOf(command(1, request.slot.wire, request.action.ordinal))
        is RoseCoralControlRequest.SetFind -> listOf(command(0x2F, request.target.wire))
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is RoseCoralControlRequest.SetAudioMode -> listOf(command(0xFA, 0x2B))
        is StandardControlRequest.SetNoiseMode -> listOf(command(0xFA, 9))
        is RoseCoralControlRequest.SetGameMode -> listOf(command(0xFA, 0x0E))
        is RoseCoralControlRequest.SetMultipoint -> listOf(command(0xFA, 0x32))
        is RoseCoralControlRequest.SetBassLevel -> listOf(command(0xFA, 0x2A))
        is RoseCoralControlRequest.SetPromptVolume -> listOf(command(0xFA, 0x2E))
        is RoseCoralControlRequest.SetTouch, is RoseCoralControlRequest.SetTouchEnabled -> listOf(command(0xFA, 1, 7))
        is RoseCoralControlRequest.SetFind -> listOf(command(0xFA, 0x2F))
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        val states = decoder.offer(bytes)
        if (states.isNotEmpty() && !handshake) {
            add(ProtocolEvent.HandshakeAccepted)
            handshake = true
        }
        states.forEach { state ->
            when (state) {
                is RoseCoralWireCodec.State.Firmware -> add(ProtocolEvent.FeatureStateChanged(
                    RoseCoralInfoFeatureState(state.major, state.minor, state.patch),
                ))
                is RoseCoralWireCodec.State.Battery -> {
                    fun reading(raw: Int): BatteryReading {
                        val percent = (raw and 127).takeIf { it <= 100 }
                        return BatteryReading(percent, percent != null && raw and 128 != 0)
                    }
                    val battery = EarbudBattery(
                        left = reading(state.left), right = reading(state.right), case = reading(state.case),
                    )
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                }
                is RoseCoralWireCodec.State.Touch -> {
                    val a = state.actions.map { RoseCoralTouchAction.entries[it] }
                    add(ProtocolEvent.FeatureStateChanged(RoseCoralTouchFeatureState(
                        a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                    )))
                }
                is RoseCoralWireCodec.State.Value -> when (state.type) {
                    0x2B -> add(ProtocolEvent.FeatureStateChanged(RoseCoralAudioFeatureState(
                        RoseCoralAudioMode.entries.single { it.wire == state.value },
                    )))
                    9 -> {
                        add(ProtocolEvent.CapabilitiesIdentified(battery = false, noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF)))
                        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(if (state.value == 1) NoiseMode.ANC else NoiseMode.OFF)))
                    }
                    0x2F -> {
                        find = when (state.value) {
                            1 -> find.copy(left = true)
                            2 -> find.copy(right = true)
                            3 -> RoseCoralFindFeatureState(true, true)
                            4 -> RoseCoralFindFeatureState(false, false)
                            5 -> find.copy(left = false)
                            else -> find.copy(right = false)
                        }
                        add(ProtocolEvent.FeatureStateChanged(find))
                    }
                    else -> {
                        settings = when (state.type) {
                            7 -> settings.copy(touchEnabled = state.value == 1)
                            0x0E -> settings.copy(gameMode = state.value == 1)
                            0x32 -> settings.copy(multipoint = state.value == 1)
                            0x2A -> settings.copy(bassLevel = state.value)
                            0x2E -> settings.copy(promptVolume = state.value)
                            else -> settings
                        }
                        add(ProtocolEvent.FeatureStateChanged(settings))
                    }
                }
            }
        }
    }

    override fun reset() {
        decoder.reset()
        sequence = 0
        handshake = false
        settings = RoseCoralSettingsFeatureState()
        find = RoseCoralFindFeatureState()
    }
}
