package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRfcommSettingsCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class RoseRfcommSetting(val type: Int) {
    NOISE(9), GAME(14), EQ(42), ANC_DEPTH(44), TRANSPARENCY_DEPTH(45), PROMPT_LANGUAGE(7), PROMPT_LEVEL(46);
    val values: Set<Int> get() = RoseRfcommSettingsCodec.ranges.getValue(type)
}

enum class RoseRfcommSettingsProfile {
    NONE, GAME_ONLY, CERAMICS_U;
    fun allows(setting: RoseRfcommSetting) = this == CERAMICS_U || (this == GAME_ONLY && setting == RoseRfcommSetting.GAME)
}

@Serializable
@SerialName("rose.rfcomm.settings")
data class RoseRfcommSettingsFeatureState(val values: Map<RoseRfcommSetting, Int> = emptyMap()) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.rfcomm.settings" }
}

@Serializable
sealed interface RoseRfcommControlRequest : ControlRequest {
    @Serializable @SerialName("rose.rfcomm.set_value")
    data class SetValue(val setting: RoseRfcommSetting, val value: Int) : RoseRfcommControlRequest
}

fun RoseRfcommSettingsFeatureState.supports(request: RoseRfcommControlRequest.SetValue) =
    values.containsKey(request.setting) && request.value in request.setting.values
