package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable enum class AppleSpatialMode { OFF, FIXED, TRACKING }

/** Host-confirmed rendering and calibration; never inferred from an Apple model identifier. */
@Serializable
@SerialName("apple.spatial")
data class AppleSpatialFeatureState(
    val rendererAvailable: Boolean,
    val trackerAvailable: Boolean,
    val mode: AppleSpatialMode = AppleSpatialMode.OFF,
    val pending: Boolean = false,
    val calibrated: Boolean = false,
    val calibrationStep: Int = -1,
    val collecting: Boolean = false,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "apple.spatial" }
}

@Serializable sealed interface AppleSpatialControlRequest : ControlRequest {
    @Serializable @SerialName("apple.spatial.set_mode")
    data class SetMode(val mode: AppleSpatialMode) : AppleSpatialControlRequest
    @Serializable @SerialName("apple.spatial.begin_calibration")
    data object BeginCalibration : AppleSpatialControlRequest
    @Serializable @SerialName("apple.spatial.calibration_step")
    data object CalibrationStep : AppleSpatialControlRequest
    @Serializable @SerialName("apple.spatial.recenter")
    data object Recenter : AppleSpatialControlRequest
}
