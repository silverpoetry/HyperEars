package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class VivoDoubleTap(val wire: Int, val report: Int) {
    ASSISTANT(0, 3), PLAY_PAUSE(1, 4), PREVIOUS(2, 6), NEXT(3, 7), NONE(6, 255),
}
@Serializable
enum class VivoHoldCycle(val wire: Int) {
    ANC_OFF(8), TRANSPARENCY_OFF(9), ANC_TRANSPARENCY(10), ALL(11), NONE(255),
}
@Serializable
enum class VivoDeepX(val wire: Int) { DEFAULT(0), VOICE(1), BASS(2), TREBLE(3), AUDIOBOOK(5) }

/** A status document replaces the snapshot; missing fields never acquire invented defaults. */
@Serializable
@SerialName("vivo.tws3e.settings")
data class VivoTws3eFeatureState(
    val leftTap: VivoDoubleTap? = null,
    val rightTap: VivoDoubleTap? = null,
    val leftHold: VivoHoldCycle? = null,
    val rightHold: VivoHoldCycle? = null,
    val deepX: VivoDeepX? = null,
    val wearDetection: Boolean? = null,
) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    fun supports(request: VivoTws3eControlRequest): Boolean = when (request) {
        is VivoTws3eControlRequest.SetDoubleTap -> (if (request.right) rightTap else leftTap) != null
        is VivoTws3eControlRequest.SetHold -> leftHold != null && rightHold != null
        is VivoTws3eControlRequest.SetDeepX -> deepX != null
        is VivoTws3eControlRequest.SetWearDetection -> wearDetection != null
    }
    val hasSettings: Boolean get() = leftTap != null || rightTap != null ||
        (leftHold != null && rightHold != null) || deepX != null || wearDetection != null
    companion object { const val FEATURE_ID = "vivo.tws3e.settings" }
}

@Serializable
sealed interface VivoTws3eControlRequest : ControlRequest {
    @Serializable @SerialName("vivo.tws3e.set_double_tap")
    data class SetDoubleTap(val right: Boolean, val action: VivoDoubleTap) : VivoTws3eControlRequest
    @Serializable @SerialName("vivo.tws3e.set_hold")
    data class SetHold(val left: VivoHoldCycle, val right: VivoHoldCycle) : VivoTws3eControlRequest
    @Serializable @SerialName("vivo.tws3e.set_deepx")
    data class SetDeepX(val preset: VivoDeepX) : VivoTws3eControlRequest
    @Serializable @SerialName("vivo.tws3e.set_wear_detection")
    data class SetWearDetection(val enabled: Boolean) : VivoTws3eControlRequest
}

object VivoTws3ePresentationIds {
    val SETTINGS = MiLinkCardPresentationId("vivo-tws3e-settings")
}
