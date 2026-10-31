package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleModelCatalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Current private-session readback only; never Android alias, display-name matching or serial data. */
@Serializable @SerialName("apple.device_information")
data class AppleDeviceInformationFeatureState(val reportedName: String, val modelIdentifier: String, val firmwareActive: String) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val catalogModel get() = AppleModelCatalog.byModelIdentifier(modelIdentifier)
    companion object { const val FEATURE_ID = "apple.device_information" }
}
