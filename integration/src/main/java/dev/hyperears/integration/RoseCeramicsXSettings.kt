package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseCeramicsXSettingsCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class RoseCeramicsXSetting(val field: Int) {
    EQ(4), GAME(8), LANGUAGE(10), AUDIO(11), ANC_LEVEL(17), TRANSPARENCY_LEVEL(18), TOUCH(22);
    val values: IntRange get() = RoseCeramicsXSettingsCodec.ranges.getValue(this.field)
}

@Serializable
@SerialName("rose.ceramics_x.settings")
data class RoseCeramicsXSettingsFeatureState(val values: Map<RoseCeramicsXSetting, Int> = emptyMap()) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.ceramics_x.settings" }
    fun supports(request: RoseCeramicsXControlRequest.SetValue, noise: NoiseMode?): Boolean =
        request.setting in values && request.value in request.setting.values && when (request.setting) {
            RoseCeramicsXSetting.ANC_LEVEL -> noise == NoiseMode.ANC
            RoseCeramicsXSetting.TRANSPARENCY_LEVEL -> noise == NoiseMode.TRANSPARENCY
            else -> true
        }
}

@Serializable
sealed interface RoseCeramicsXControlRequest : ControlRequest {
    @Serializable @SerialName("rose.ceramics_x.set_value")
    data class SetValue(val setting: RoseCeramicsXSetting, val value: Int) : RoseCeramicsXControlRequest
}
