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

    @Serializable
    @SerialName("apple.set_microphone")
    data class SetMicrophone(val value: AppleMicrophone) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_one_bud_anc")
    data class SetOneBudAnc(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_conversation_awareness")
    data class SetConversationAwareness(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_personalized_volume")
    data class SetPersonalizedVolume(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_wearing_detection")
    data class SetWearingDetection(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_volume_swipe")
    data class SetVolumeSwipe(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_double_press_interval")
    data class SetDoublePressInterval(val value: AppleIntervalLevel) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_hold_interval")
    data class SetHoldInterval(val value: AppleIntervalLevel) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_swipe_interval")
    data class SetSwipeInterval(val value: AppleIntervalLevel) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_adaptive_strength")
    data class SetAdaptiveStrength(val value: Int) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_call_mapping")
    data class SetCallMapping(val value: AppleCallMapping) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_allow_off")
    data class SetAllowOff(val value: Boolean) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_chime_volume")
    data class SetChimeVolume(val value: Int) : AppleControlRequest

    @Serializable
    @SerialName("apple.set_sleep_pause")
    data class SetSleepPause(val value: Boolean) : AppleControlRequest
}
