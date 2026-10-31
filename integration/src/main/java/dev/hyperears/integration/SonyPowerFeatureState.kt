package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
@Serializable @SerialName("sony.power")
data class SonyPowerFeatureState(val version: Int, val available: Boolean) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun supports(request: SonyControlRequest.PowerOff) = available && version in 1..2 && request.expectedVersion == version
    companion object { const val FEATURE_ID = "sony.power" }
}
