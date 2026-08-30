package dev.hyperears.integration

/** Hardware-verified HUAWEI FreeBuds 5i adapter (RFCOMM Channel 16). */
internal class HuaweiFreebuds5iAdapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-5i",
    channelNumbers = listOf(16),
    profile = HuaweiProtocolProfile(
        noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
    ),
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds 5i"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freebuds-5i"
        private val MODEL_NAMES = setOf("huaweifreebuds5i", "freebuds5i")
    }
}
