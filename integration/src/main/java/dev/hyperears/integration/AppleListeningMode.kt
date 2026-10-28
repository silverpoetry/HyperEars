package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** AACP listening selection; adaptive remains distinct from conventional ANC. */
@Serializable
enum class AppleListeningMode {
    @SerialName("off") OFF,
    @SerialName("anc") ANC,
    @SerialName("transparency") TRANSPARENCY,
    @SerialName("adaptive") ADAPTIVE,
}

/** Last authoritative listening report, not a requested or optimistically published selection. */
@Serializable
@SerialName("apple.listening_mode")
data class AppleListeningModeFeatureState(
    val mode: AppleListeningMode,
    /** Session-admitted choices for presentation and request validation; reset discards them. */
    val supportedModes: Set<AppleListeningMode>,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "apple.listening_mode"
    }
}

object AppleMiLinkPresentationIds {
    val LISTENING = MiLinkCardPresentationId("apple-listening")
}

@Serializable
sealed interface AppleControlRequest : ControlRequest {
    @Serializable
    @SerialName("apple.set_listening_mode")
    data class SetListeningMode(val mode: AppleListeningMode) : AppleControlRequest
}
