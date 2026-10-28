package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SonyAudioDefinition(val version: Int, val inquiredType: Int)

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
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings get() = dsee != null || quality != null || speak != null
    val dseeWritable get() = dsee != null && dseeDefinition != null &&
        (dseeDefinition.inquiredType == 1 || dseeAvailable == true)
    fun supports(request: SonyControlRequest): Boolean = when (request) {
        is SonyControlRequest.SetDsee -> dseeWritable && request.expected == dseeDefinition
        is SonyControlRequest.SetConnectionQuality -> quality != null && qualityAvailable == true &&
            request.expected == qualityDefinition && request.mode in 0..1
        is SonyControlRequest.SetSpeakToChat -> speak != null && speakAvailable == true && request.expected == speakDefinition
        else -> false
    }
    companion object { const val FEATURE_ID = "sony.audio_settings" }
}
