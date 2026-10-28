package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseBudsFeelMk2WireCodec

/** HyperRose's explicit i7 profile uses RFCOMM, unlike the i5 GATT product-line probe. */
class RoseEarfeelI7Adapter : RoseBudsFeelProtocolFamilyAdapter() {
    override val settingsProfile = RoseRfcommSettingsProfile.GAME_ONLY
    override val id = ID
    override val displayName = "ROSE EarFeel i7"
    override val resolution = AdapterResolution.EXACT_MATCH
    override val wireNoiseModes = RoseBudsFeelMk2WireCodec.NoiseMode.entries.filter { it.value in 1..4 }.toSet()
    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in setOf("earfeeli7", "roseearfeeli7")
    override fun onInitialProtocolUnavailable() = InitialProtocolFailureResolution.KeepDormant
    companion object { const val ID = "rose-earfeel-i7" }
}

/** U has its own public profile; it must not inherit Ultra's identity or model-specific policy. */
class RoseCeramicsUAdapter : RoseBudsFeelProtocolFamilyAdapter() {
    override val settingsProfile = RoseRfcommSettingsProfile.CERAMICS_U
    override val id = ID
    override val displayName = "ROSE Ceramics U"
    override val resolution = AdapterResolution.EXACT_MATCH
    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud &&
            normalizeDeviceName(identity.deviceName.orEmpty()) == "roseceramicsu"
    override fun onInitialProtocolUnavailable() = InitialProtocolFailureResolution.KeepDormant
    companion object { const val ID = "rose-ceramics-u" }
}
