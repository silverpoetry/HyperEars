package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoSettingsCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class OppoToggle(val featureId: Int) {
    AUTO_PLAY_PAUSE(0x04), LOW_LATENCY(0x06), MULTIPOINT(0x11), GAME_MODE(0x28),
}

@Serializable
data class OppoEqualizerDefinition(val id: Int, val name: String, val minimum: Int,
    val maximum: Int, val frequencies: List<Int>)

@Serializable
data class OppoEqualizer(val id: Int, val name: String, val minimum: Int, val maximum: Int,
    val frequencies: List<Int>, val gains: List<Int>) {
    internal fun wire() = OppoSettingsCodec.Equalizer(id, name, false, minimum, maximum, frequencies, gains)
    fun definition() = OppoEqualizerDefinition(id, name, minimum, maximum, frequencies)
}

@Serializable
@SerialName("oppo.settings")
data class OppoSettingsFeatureState(
    val toggles: Map<OppoToggle, Boolean> = emptyMap(),
    val currentEqualizer: Int? = null,
    val equalizers: List<OppoEqualizer>? = null,
    val editableEqualizers: Set<Int> = emptySet(),
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "oppo.settings" }
}

@Serializable
sealed interface OppoControlRequest : ControlRequest {
    @Serializable @SerialName("oppo.set_toggle")
    data class SetToggle(val setting: OppoToggle, val enabled: Boolean) : OppoControlRequest
    @Serializable @SerialName("oppo.select_equalizer")
    data class SelectEqualizer(val id: Int) : OppoControlRequest
    @Serializable @SerialName("oppo.update_equalizer")
    data class UpdateEqualizer(val id: Int, val gains: List<Int>, val expected: OppoEqualizerDefinition) : OppoControlRequest
}

fun OppoSettingsFeatureState.supports(request: OppoControlRequest): Boolean = when (request) {
    is OppoControlRequest.SetToggle -> toggles.containsKey(request.setting)
    is OppoControlRequest.SelectEqualizer -> equalizers?.any { it.id == request.id } == true
    is OppoControlRequest.UpdateEqualizer -> request.id in editableEqualizers &&
        equalizers?.firstOrNull { it.id == request.id }?.let {
            it.definition() == request.expected && OppoSettingsCodec.canUpdate(it.wire(), request.gains)
        } == true
}
