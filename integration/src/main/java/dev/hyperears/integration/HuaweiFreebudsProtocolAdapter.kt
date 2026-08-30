package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

internal data class HuaweiProtocolProfile(
    val noiseModes: Set<NoiseMode>,
    val extension: HuaweiProtocolExtension? = null,
) {
    val noiseTelemetryEnabled: Boolean get() = noiseModes.isNotEmpty()

    init {
        require(NoiseMode.WIND !in noiseModes) {
            "Huawei FreeBuds SPP does not define a wind-noise mode"
        }
        require(extension == null || noiseTelemetryEnabled) {
            "Huawei protocol extensions require a noise-capable profile"
        }
    }
}

/** Optional model-specific state and control behavior layered over the shared Huawei session. */
internal interface HuaweiProtocolExtension {
    val featureIds: Set<String>

    fun acceptsState(state: DeviceFeatureState): Boolean

    fun supportsRequest(adapter: EarbudAdapter, request: ControlRequest): Boolean

    fun encode(request: ControlRequest): ByteArray?

    fun state(mode: HuaweiFreebudsSppCodec.NoiseMode, level: Int?): DeviceFeatureState?

    fun controlPolicy(request: ControlRequest): ControlExecutionPolicy? = null

    fun readback(request: ControlRequest): List<ByteArray> = emptyList()
}

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
        if (profile.extension != null) {
            StandardDeviceFeatureStateContract.extending { _, state ->
                profile.extension.acceptsState(state)
            }
        } else {
            StandardDeviceFeatureStateContract
        }

    override val controlRequestContract: ControlRequestContract =
        if (profile.extension != null) {
            StandardControlRequestContract.extending { adapter, request ->
                profile.extension.supportsRequest(adapter, request)
            }
        } else {
            StandardControlRequestContract
        }

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsProtocolSession(profile)

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        profile.extension?.controlPolicy(request) ?: super.controlPolicy(request)

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant
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

/** HUAWEI FreeClip 2: hardware-verified component battery without noise control. */
internal class HuaweiFreeClip2Adapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freeclip-2",
    channelNumbers = listOf(1),
    profile = FREECLIP_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeClip 2"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freeclip-2"
        private val MODEL_NAMES = setOf("huaweifreeclip2", "freeclip2")
    }
}

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
                ?.let { listOf(HuaweiFreebudsSppCodec.noiseModeCommand(it.toHuaweiWireMode())) }
                .orEmpty()

        else -> profile.extension?.encode(request)?.let(::listOf).orEmpty()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature -> when {
            request.featureId == BatteryFeatureState.FEATURE_ID ->
                listOf(HuaweiFreebudsSppCodec.queryBattery)

            request.featureId == NoiseModeFeatureState.FEATURE_ID ||
                request.featureId in profile.extension?.featureIds.orEmpty() ->
                listOf(HuaweiFreebudsSppCodec.queryNoiseState)
                    .takeIf { profile.noiseTelemetryEnabled }
                    .orEmpty()

            else -> emptyList()
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode ->
            listOf(HuaweiFreebudsSppCodec.queryNoiseState)
                .takeIf { profile.noiseTelemetryEnabled }
                .orEmpty()

        else -> profile.extension?.readback(request).orEmpty()
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
                if (profile.noiseTelemetryEnabled) pendingNoiseRefresh = true
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

            if (profile.noiseTelemetryEnabled) {
                HuaweiFreebudsSppCodec.parseNoiseState(frameBytes)?.let { state ->
                    val mode = state.mode.toDomainMode()
                    if (mode in profile.noiseModes) {
                        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                        profile.extension?.state(state.mode, state.level)?.let { extensionState ->
                            add(ProtocolEvent.FeatureStateChanged(extensionState))
                        }
                        add(
                            ProtocolEvent.CapabilitiesIdentified(
                                battery = false,
                                noiseModes = profile.noiseModes,
                            ),
                        )
                        publishHandshakeIfNeeded()
                    }
                }
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        pendingNoiseRefresh = false
    }

    private fun telemetryQueries(): List<ByteArray> = buildList {
        add(HuaweiFreebudsSppCodec.queryBattery)
        if (profile.noiseTelemetryEnabled) add(HuaweiFreebudsSppCodec.queryNoiseState)
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }
}

internal fun NoiseMode.toHuaweiWireMode(): HuaweiFreebudsSppCodec.NoiseMode = when (this) {
    NoiseMode.ANC -> HuaweiFreebudsSppCodec.NoiseMode.ANC
    NoiseMode.OFF -> HuaweiFreebudsSppCodec.NoiseMode.OFF
    NoiseMode.TRANSPARENCY -> HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY
    NoiseMode.WIND -> error("Huawei FreeBuds SPP does not define a wind-noise mode")
}

private val FREEBUDS_4_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF),
)

private val HUAWEI_FAMILY_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
)

private val FREECLIP_PROFILE = HuaweiProtocolProfile(
    noiseModes = emptySet(),
)
