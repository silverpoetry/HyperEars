package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Identity-verified lid telemetry. Sequence advances only after the host's settled open edge. */
@Serializable
@SerialName("apple.case")
data class AppleCaseFeatureState(val isOpen: Boolean, val sequence: Long = 0) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "apple.case" }
}
