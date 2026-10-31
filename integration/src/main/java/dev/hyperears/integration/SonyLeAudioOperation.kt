package dev.hyperears.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable
enum class SonyLeAudioPhase { WAITING_PROFILE, WAITING_HISTORY, WAITING_CONFIRMATION, SETTING_CONFIRMED, FAILED }

/** Host transaction progress, distinct from the headphone's reported LE mode and link state. */
@Serializable
@SerialName("sony.le_audio_operation")
data class SonyLeAudioOperation(
    val phase:SonyLeAudioPhase,
    val targetEnabled:Boolean,
    val pairingRequired:Boolean=false,
    val message:String="",
):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val pending:Boolean get()=phase in setOf(SonyLeAudioPhase.WAITING_PROFILE,SonyLeAudioPhase.WAITING_HISTORY,SonyLeAudioPhase.WAITING_CONFIRMATION)
    companion object {const val FEATURE_ID="sony.le_audio_operation"}
}
