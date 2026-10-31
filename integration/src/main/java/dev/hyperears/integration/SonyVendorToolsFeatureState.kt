package dev.hyperears.integration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Discovery-only vendor navigation; never grants private device controls or calibration results. */
@Serializable @SerialName("sony.vendor_tools")
data class SonyVendorToolsFeatureState(val version:Int=1,val optimizerAvailable:Boolean=false):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    companion object {const val FEATURE_ID="sony.vendor_tools"}
}
