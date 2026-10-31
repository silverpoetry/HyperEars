package dev.hyperears.integration

import dev.hyperears.protocol.tozo.TozoDailyCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class TozoDailySetting {
    GAME, WEAR, MULTIPOINT, LDAC;
    val peer: TozoDailySetting? get() = when(this) { MULTIPOINT -> LDAC; LDAC -> MULTIPOINT; else -> null }
    internal fun wire() = TozoDailyCodec.Setting.valueOf(name)
}
@Serializable @SerialName("tozo.nc20pro.daily")
data class TozoDailyFeatureState(val values: Map<TozoDailySetting,Boolean> = emptyMap()) : DeviceFeatureState {
    override val featureId: String get() = ID
    fun canChange(setting: TozoDailySetting) = values.containsKey(setting) &&
        (setting.peer?.let(values::containsKey) ?: true)
    fun supports(request: TozoControlRequest.SetDaily) = canChange(request.setting) &&
        values[request.setting] == request.expected && request.enabled != request.expected &&
        request.expectedPeer == request.setting.peer?.let(values::get)
    fun request(setting: TozoDailySetting, enabled: Boolean): TozoControlRequest.SetDaily? {
        val old = values[setting] ?: return null
        return TozoControlRequest.SetDaily(setting,old,enabled,setting.peer?.let(values::get)).takeIf(::supports)
    }
    companion object { const val ID = "tozo.nc20pro.daily" }
}
