package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SonyAudioDefinition(val version: Int, val inquiredType: Int)

@Serializable
data class SonySpeakParameters(val sensitivity: Int, val modeOutTime: Int, val voiceFocus: Boolean? = null)

@Serializable @SerialName("sony.audio_settings")
data class SonyAudioSettingsFeatureState(
    val dseeDefinition: SonyAudioDefinition? = null,
    val dsee: Boolean? = null,
    val dseeGeneration: Int? = null,
    val dseeAvailable: Boolean? = null,
    val qualityDefinition: SonyAudioDefinition? = null,
    val quality: Int? = null,
    val qualityAvailable: Boolean? = null,
    val switchingStream: Int? = null,
    val speakDefinition: SonyAudioDefinition? = null,
    val speak: Boolean? = null,
    val speakAvailable: Boolean? = null,
    val speakActive: Boolean? = null,
    val speakParameters: SonySpeakParameters? = null,
    val wearingDefinition: SonyAudioDefinition? = null,
    val wearingPlayback: Boolean? = null,
    val wearingAvailable: Boolean? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings get() = dsee != null || quality != null || speak != null || wearingPlayback != null
    val dseeWritable get() = dsee != null && dseeDefinition != null &&
        (dseeDefinition.inquiredType == 1 || dseeAvailable == true)
    fun supports(request: SonyControlRequest): Boolean = when (request) {
        is SonyControlRequest.SetWearingPlayback -> wearingDefinition == SonyAudioDefinition(1,3) && wearingPlayback != null && wearingAvailable == true &&
            request.expected == wearingDefinition && request.expectedCurrent == wearingPlayback && request.enabled != wearingPlayback
        is SonyControlRequest.SetDsee -> dseeWritable && request.expected == dseeDefinition
        is SonyControlRequest.SetConnectionQuality -> quality != null && qualityAvailable == true &&
            request.expected == qualityDefinition && request.mode in 0..1
        is SonyControlRequest.SetSpeakParameters -> speak != null && speakAvailable == true &&
            speakDefinition?.version == 2 && request.expected == speakDefinition &&
            speakParameters != null && request.baseline == speakParameters &&
            request.target.sensitivity in 0..2 && request.target.modeOutTime in 0..3 &&
            request.target.voiceFocus == speakParameters.voiceFocus && request.target != speakParameters
        is SonyControlRequest.SetSpeakToChat -> speak != null && speakAvailable == true && request.expected == speakDefinition
        else -> false
    }
    companion object { const val FEATURE_ID = "sony.audio_settings" }
}
