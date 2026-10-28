package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class NiceHckSetting(val query: Int, val write: Int, val values: Set<Int>) {
    NOISE(0x0101, 0x0201, setOf(0, 1, 2, 3, 16, 17)), EQ(0x0107, 0x0207, (0..6).toSet()),
    GAME(0x0108, 0x0208, setOf(0, 1)), LOW_LATENCY(0x0106, 0x0206, setOf(0, 1)),
    MULTIPOINT(0x0105, 0x0205, setOf(0, 1)), WEAR_DETECTION(0x0109, 0x0209, setOf(0, 1)),
    WIND_SUPPRESSION(0x01E1, 0x02E1, setOf(0, 1)),
}

@Serializable
@SerialName("nicehck.settings")
data class NiceHckSettingsFeatureState(
    val values: Map<NiceHckSetting, Int> = emptyMap(),
    val firmwareMajor: Int? = null,
    val firmwareMinor: Int? = null,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun allowed(setting: NiceHckSetting): Set<Int> = if (setting == NiceHckSetting.EQ &&
        (firmwareMinor == null || firmwareMinor < 8)) (0..4).toSet() else setting.values
    fun supports(request: NiceHckControlRequest.SetValue) =
        values.containsKey(request.setting) && request.value in allowed(request.setting)
    companion object { const val FEATURE_ID = "nicehck.settings" }
}

@Serializable
sealed interface NiceHckControlRequest : ControlRequest {
    @Serializable @SerialName("nicehck.set_value")
    data class SetValue(val setting: NiceHckSetting, val value: Int) : NiceHckControlRequest
}
