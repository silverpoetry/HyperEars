package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class PuddingSetting(val query: Int, val values: Set<Int>) {
    NOISE(64,(0..4).toSet()), GAIN(30,(0..2).toSet()), LED(38,setOf(0,1)),
}
@Serializable
@SerialName("pudding.settings")
data class PuddingSettingsFeatureState(val values: Map<PuddingSetting,Int> = emptyMap()) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun allowed(setting: PuddingSetting) = setting.values
    fun supports(request: PuddingControlRequest.SetValue) = values.containsKey(request.setting) && request.value in request.setting.values
    companion object {
        const val FEATURE_ID = "pudding.settings"
        const val DIALECT_QUERY = "pudding.dialect"
        val ADVANCED = MiLinkCardPresentationId("pudding-advanced")
        val SETTINGS_ONLY = MiLinkCardPresentationId("pudding-settings-only")
    }
}
@Serializable
sealed interface PuddingControlRequest : ControlRequest {
    @Serializable @SerialName("pudding.set_value")
    data class SetValue(val setting: PuddingSetting, val value: Int) : PuddingControlRequest
}
