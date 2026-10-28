package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyDailyControlsCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SonyEqBand(val kind: Int, val value: Int)

/** Exact semantic identity of an EQ editor; transported with writes to reject stale IPC drafts. */
@Serializable
data class SonyEqDefinition(
    val version: Int,
    val parameterType: Int,
    val bands: List<SonyEqBand>,
    val center: Int,
    val ultMode: Int?,
)

@Serializable @SerialName("sony.eq")
data class SonyEqFeatureState(
    val version: Int,
    val parameterType: Int? = null,
    val enabled: Boolean? = null,
    val preset: Int? = null,
    val ultMode: Int? = null,
    val rawSteps: List<Int> = emptyList(),
    val bands: List<SonyEqBand> = emptyList(),
    val standaloneBass: Int? = null,
    val bassEnabled: Boolean? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val tenBand: Boolean get() = bands.size == 10 && bands.none { it.kind == 0x10 }
    val center: Int get() = if (tenBand) 6 else 10
    val definition: SonyEqDefinition? get() = parameterType?.let { SonyEqDefinition(version, it, bands, center, ultMode) }
    val clearBassSlot: Boolean get() = bands.firstOrNull()?.kind == 0x10
    val validBands: Boolean get() = bands.isNotEmpty() && bands.size == rawSteps.size && rawSteps.all { it in 0..center * 2 }
    val writableBands: Boolean get() = enabled == true && validBands &&
        parameterType in (if (version == 1) setOf(1) else setOf(0, 3, 4, 0x31))
    val clearBass: Int? get() = if (clearBassSlot && validBands) rawSteps[0] - center else standaloneBass
    val writableBass: Boolean get() = if (clearBassSlot) writableBands else !tenBand && standaloneBass != null && bassEnabled == true
    val writablePreset: Boolean get() = enabled == true && preset != null && parameterType != null && parameterType != 0x31
    fun supports(request: SonyControlRequest): Boolean = when (request) {
        is SonyControlRequest.SetEqPreset -> writablePreset && request.preset in SonyDailyControlsCodec.presets
        is SonyControlRequest.SetEqBands -> writableBands && request.expectedDefinition == definition && request.steps.size == rawSteps.size && request.steps.all { it in -center..center }
        is SonyControlRequest.SetClearBass -> writableBass && request.level in -10..10
        else -> false
    }
    companion object { const val FEATURE_ID = "sony.eq" }
}

@Serializable @SerialName("sony.ambient")
data class SonyAmbientFeatureState(val level: Int, val voice: Boolean, val voiceWritable: Boolean, val adaptive: Boolean) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun supports(request: SonyControlRequest.SetAmbient) = level in 1..20 && !adaptive && request.level in 1..20 &&
        (voiceWritable || request.voice == voice)
    companion object { const val FEATURE_ID = "sony.ambient" }
}

@Serializable
sealed interface SonyControlRequest : ControlRequest {
    @Serializable @SerialName("sony.set_wearing_playback") data class SetWearingPlayback(val expected:SonyAudioDefinition,val expectedCurrent:Boolean,val enabled:Boolean):SonyControlRequest
    @Serializable @SerialName("sony.set_assignable_action") data class SetAssignableAction(val expected:SonyAssignableDefinition,val expectedCurrent:List<Int>,val expectedMappings:List<SonyAssignableMapping>,val index:Int,val action:Int,val function:Int):SonyControlRequest
    @Serializable @SerialName("sony.set_assignable_preset") data class SetAssignablePreset(val expected: SonyAssignableDefinition, val expectedCurrent: List<Int>, val index: Int, val preset: Int) : SonyControlRequest
    @Serializable @SerialName("sony.set_multipoint") data class SetMultipoint(val expected: SonyMultipointDefinition, val enabled: Boolean) : SonyControlRequest
    @Serializable @SerialName("sony.reply_multipoint_alert") data class ReplyMultipointAlert(val expected: SonyMultipointDefinition, val expectedAlert: SonyMultipointAlert, val confirm: Boolean) : SonyControlRequest
    @Serializable @SerialName("sony.set_dsee") data class SetDsee(val expected: SonyAudioDefinition, val enabled: Boolean) : SonyControlRequest
    @Serializable @SerialName("sony.set_connection_quality") data class SetConnectionQuality(val expected: SonyAudioDefinition, val mode: Int) : SonyControlRequest
    @Serializable @SerialName("sony.set_speak_to_chat") data class SetSpeakToChat(val expected: SonyAudioDefinition, val enabled: Boolean) : SonyControlRequest
    @Serializable @SerialName("sony.set_eq_preset") data class SetEqPreset(val preset: Int) : SonyControlRequest
    @Serializable @SerialName("sony.set_eq_bands") data class SetEqBands(val steps: List<Int>, val expectedDefinition: SonyEqDefinition) : SonyControlRequest
    @Serializable @SerialName("sony.set_clear_bass") data class SetClearBass(val level: Int) : SonyControlRequest
    @Serializable @SerialName("sony.set_ambient") data class SetAmbient(val level: Int, val voice: Boolean) : SonyControlRequest
}
