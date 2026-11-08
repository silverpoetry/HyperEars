package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** EQ presets exposed by the vendor app's sound-effect page, in wire-value order. */
@Serializable
enum class FloS2SoundEffect {
    @SerialName("standard")
    STANDARD,

    @SerialName("swimming")
    SWIMMING,

    @SerialName("vocals")
    VOCALS,

    @SerialName("enhanced")
    ENHANCED,
}

/** Protocol-confirmed sound-effect selection exposed only by FloS2 adapters. */
@Serializable
@SerialName("flos2.sound_effect")
data class FloS2SoundEffectFeatureState(
    val effect: FloS2SoundEffect,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "flos2.sound_effect"
    }
}

/** FloS2-owned requests remain outside the common refresh and three-state control family. */
@Serializable
sealed interface FloS2ControlRequest : ControlRequest {
    @Serializable
    @SerialName("flos2.set_sound_effect")
    data class SetSoundEffect(
        val effect: FloS2SoundEffect,
    ) : FloS2ControlRequest
}

object FloS2MiLinkPresentationIds {
    /** Native mode-slot carrier repurposed for the four-way sound-effect selector. */
    val SOUND_EFFECT = MiLinkCardPresentationId("flos2-sound-effect")
}
