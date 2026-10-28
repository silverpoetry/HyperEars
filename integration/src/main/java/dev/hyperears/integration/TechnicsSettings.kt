package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@SerialName("technics.levels")
data class TechnicsLevelsFeatureState(val mode: NoiseMode, val anc: Int, val ambient: Int) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    fun supports(request: TechnicsControlRequest.SetLevel) = request.value in 0..100 &&
        mode == request.expectedMode && mode == if (request.ambient) NoiseMode.TRANSPARENCY else NoiseMode.ANC
    companion object { const val FEATURE_ID = "technics.levels" }
}

@Serializable
sealed interface TechnicsControlRequest : ControlRequest {
    @Serializable @SerialName("technics.set_level")
    data class SetLevel(val ambient: Boolean, val value: Int, val expectedMode: NoiseMode) : TechnicsControlRequest
}
