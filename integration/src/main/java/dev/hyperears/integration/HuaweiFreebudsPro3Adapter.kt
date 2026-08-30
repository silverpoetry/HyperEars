package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

internal data class HuaweiProtocolProfile(
    val noiseModes: Set<NoiseMode>,
    val ancLevels: Boolean,
)

/** Shared identity-independent behavior for Huawei's `5A 00` RFCOMM protocol. */
internal abstract class HuaweiFreebudsProtocolAdapter(
    endpointPrefix: String,
    channelNumbers: List<Int>,
    private val profile: HuaweiProtocolProfile,
) : StandardEarbudAdapter() {
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = channelNumbers.map { channel ->
        RfcommEndpointSpec.Channel(
            number = channel,
            id = "$endpointPrefix-channel-$channel",
        )
    }
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.huaweiSmartAudio)

    override val featureStateContract: DeviceFeatureStateContract =
        if (profile.ancLevels) {
            StandardDeviceFeatureStateContract.extending { _, state ->
                state is HuaweiAncLevelFeatureState
            }
        } else {
            StandardDeviceFeatureStateContract
        }

    override val controlRequestContract: ControlRequestContract =
        if (profile.ancLevels) {
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
        } else {
            StandardControlRequestContract
        }

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsProtocolSession(profile)

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is HuaweiControlRequest.SetAncLevel ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant
}

/** Hardware-verified HUAWEI FreeBuds Pro 3 adapter (T0018/T0018C). */
internal class HuaweiFreebudsPro3Adapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-pro3",
    channelNumbers = listOf(1),
    profile = PRO3_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds Pro 3"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freebuds-pro3"
        private val MODEL_NAMES = setOf("huaweifreebudspro3", "freebudspro3")
    }
}

/** HUAWEI FreeBuds 4 profile: channel 1 with ANC/off and no transparency or depth controls. */
internal class HuaweiFreeBuds4Adapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-4",
    channelNumbers = listOf(1),
    profile = FREEBUDS_4_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freebuds-4"
        private val MODEL_NAMES = setOf("huaweifreebuds4", "freebuds4")
    }
}

/**
 * Conservative Huawei family candidate. One Adapter owns the ordered endpoint fallback, so a
 * failed channel-1 probe can continue to channel 16 without a second unreachable family match.
 */
internal class HuaweiFreebudsFamilyAdapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-family",
    channelNumbers = listOf(1, 16),
    profile = HUAWEI_FAMILY_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds protocol family"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return HUAWEI_AUDIO_PREFIXES.any(name::startsWith)
    }

    companion object {
        const val ID = "huawei-freebuds-family"
        private val HUAWEI_AUDIO_PREFIXES = setOf(
            "huaweifreebuds",
            "freebuds",
            "huaweifreeclip",
            "freeclip",
            "huaweifreelace",
            "freelace",
        )
    }
}

/** One device-session state machine shared by the Huawei profiles above. */
private class HuaweiFreebudsProtocolSession(
    private val profile: HuaweiProtocolProfile,
) : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false
    private var pendingNoiseRefresh = false

    override fun initialReadCommands(): List<ByteArray> = telemetryQueries()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> telemetryQueries()
        is StandardControlRequest.SetNoiseMode ->
            request.mode
                .takeIf(profile.noiseModes::contains)
                ?.let { listOf(HuaweiFreebudsSppCodec.noiseModeCommand(it.toWireMode())) }
                .orEmpty()
        is HuaweiControlRequest.SetAncLevel ->
            if (profile.ancLevels) {
                listOf(
                    HuaweiFreebudsSppCodec.noiseLevelCommand(
                        request.level.domainNoiseMode().toWireMode(),
                        request.level.toWireLevel(),
                    ),
                )
            } else {
                emptyList()
            }
        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID -> listOf(HuaweiFreebudsSppCodec.queryBattery)
            NoiseModeFeatureState.FEATURE_ID,
            HuaweiAncLevelFeatureState.FEATURE_ID,
            -> listOf(HuaweiFreebudsSppCodec.queryNoiseState)
            else -> emptyList()
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode,
        is HuaweiControlRequest.SetAncLevel,
        -> listOf(HuaweiFreebudsSppCodec.queryNoiseState)
        else -> emptyList()
    }

    override fun drainImmediateCommands(): List<ByteArray> {
        if (!pendingNoiseRefresh) return emptyList()
        pendingNoiseRefresh = false
        return listOf(HuaweiFreebudsSppCodec.queryNoiseState)
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frameBytes ->
            val frame = HuaweiFreebudsSppCodec.parseFrame(frameBytes) ?: return@forEach
            if (frame.command == HuaweiFreebudsSppCodec.CMD_NOISE_CHANGE_NOTIFY) {
                pendingNoiseRefresh = true
                return@forEach
            }

            HuaweiFreebudsSppCodec.parseBatteryFrame(frameBytes)?.let { battery ->
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(battery.leftPercent, charging = false),
                                right = BatteryReading(battery.rightPercent, charging = false),
                                case = BatteryReading(battery.casePercent, charging = false),
                                overall = BatteryReading(
                                    battery.globalPercent,
                                    charging = battery.isCharging,
                                ),
                            ),
                        ),
                    ),
                )
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                publishHandshakeIfNeeded()
                return@forEach
            }

            HuaweiFreebudsSppCodec.parseNoiseState(frameBytes)?.let { state ->
                val mode = state.mode.toDomainMode()
                if (mode in profile.noiseModes) {
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                    if (profile.ancLevels) {
                        add(
                            ProtocolEvent.FeatureStateChanged(
                                HuaweiAncLevelFeatureState(
                                    current = state.level?.toDomainLevel(state.mode),
                                    supported = HuaweiAncLevel.entries.toSet(),
                                ),
                            ),
                        )
                    }
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = profile.noiseModes,
                        ),
                    )
                }
                publishHandshakeIfNeeded()
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        pendingNoiseRefresh = false
    }

    private fun telemetryQueries(): List<ByteArray> = listOf(
        HuaweiFreebudsSppCodec.queryBattery,
        HuaweiFreebudsSppCodec.queryNoiseState,
    )

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireMode(): HuaweiFreebudsSppCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> HuaweiFreebudsSppCodec.NoiseMode.ANC
        NoiseMode.OFF -> HuaweiFreebudsSppCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> error("Huawei FreeBuds SPP does not define a wind-noise mode")
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private fun Int.toDomainLevel(mode: HuaweiFreebudsSppCodec.NoiseMode): HuaweiAncLevel? =
        when (mode) {
            HuaweiFreebudsSppCodec.NoiseMode.ANC -> when (this) {
                0 -> HuaweiAncLevel.NORMAL
                1 -> HuaweiAncLevel.COMFORT
                2 -> HuaweiAncLevel.ULTRA
                3 -> HuaweiAncLevel.DYNAMIC
                else -> null
            }
            HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> when (this) {
                1 -> HuaweiAncLevel.VOICE_BOOST
                2 -> HuaweiAncLevel.TRANS_NORMAL
                else -> null
            }
            HuaweiFreebudsSppCodec.NoiseMode.OFF -> null
        }
}

private val PRO3_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
    ancLevels = true,
)

private val FREEBUDS_4_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF),
    ancLevels = false,
)

private val HUAWEI_FAMILY_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
    ancLevels = false,
)
