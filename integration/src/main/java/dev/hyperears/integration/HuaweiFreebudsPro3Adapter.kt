package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

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

private val PRO3_PROTOCOL_EXTENSION = object : HuaweiProtocolExtension {
    override val featureIds: Set<String> = setOf(HuaweiAncLevelFeatureState.FEATURE_ID)

    override fun acceptsState(state: DeviceFeatureState): Boolean =
        state is HuaweiAncLevelFeatureState

    override fun supportsRequest(adapter: EarbudAdapter, request: ControlRequest): Boolean =
        request is HuaweiControlRequest.SetAncLevel &&
            adapter.effectiveCapabilities().noiseControl &&
            request.level in (
                adapter.runtimeState().features
                    .get<HuaweiAncLevelFeatureState>()
                    ?.supported
                    .orEmpty()
            ) &&
            adapter.runtimeState().noiseMode == request.level.domainNoiseMode()

    override fun encode(request: ControlRequest): ByteArray? =
        (request as? HuaweiControlRequest.SetAncLevel)?.let {
            HuaweiFreebudsSppCodec.noiseLevelCommand(
                it.level.domainNoiseMode().toHuaweiWireMode(),
                it.level.toWireLevel(),
            )
        }

    override fun state(
        mode: HuaweiFreebudsSppCodec.NoiseMode,
        level: Int?,
    ): DeviceFeatureState = HuaweiAncLevelFeatureState(
        current = level?.toPro3Level(mode),
        supported = HuaweiAncLevel.entries.toSet(),
    )

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy? =
        if (request is HuaweiControlRequest.SetAncLevel) {
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        } else {
            null
        }

    override fun readback(request: ControlRequest): List<ByteArray> =
        if (request is HuaweiControlRequest.SetAncLevel) {
            listOf(HuaweiFreebudsSppCodec.queryNoiseState)
        } else {
            emptyList()
        }

    private fun Int.toPro3Level(mode: HuaweiFreebudsSppCodec.NoiseMode): HuaweiAncLevel? =
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
    extension = PRO3_PROTOCOL_EXTENSION,
)
