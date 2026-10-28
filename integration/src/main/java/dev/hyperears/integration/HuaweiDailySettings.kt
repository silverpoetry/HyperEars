package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class HuaweiGestureKind { DOUBLE, TRIPLE, HOLD, CYCLE, SWIPE }
@Serializable
enum class HuaweiGestureSide(val field: Int) { LEFT(1), RIGHT(2), CALL(4) }
@Serializable
data class HuaweiGestureState(
    val kind: HuaweiGestureKind, val left: Int? = null, val right: Int? = null,
    val call: Int? = null, val available: List<Int> = emptyList(),
    /** Independent swipe profiles pin both ears and all report TLVs across IPC/UI delay. */
    val modelId: String? = null, val raw: List<Int>? = null,
) {
    fun value(side: HuaweiGestureSide): Int? = when (side) {
        HuaweiGestureSide.LEFT -> left; HuaweiGestureSide.RIGHT -> right; HuaweiGestureSide.CALL -> call
    }
}
@Serializable
data class HuaweiCustomEqualizer(val id: Int, val name: String, val gains: List<Int>)
@Serializable
data class HuaweiEqualizerState(
    val selected: Int, val presets: List<Int>, val custom: List<HuaweiCustomEqualizer> = emptyList(),
    val customWritable: Boolean = false,
)
@Serializable
@SerialName("huawei.daily_settings")
data class HuaweiDailySettingsFeatureState(
    val pairedDevices:HuaweiPairedSnapshot?=null,
    val wearDetection: Boolean? = null, val multipoint: Boolean? = null,
    val lowLatency: Boolean? = null, val preferSoundQuality: Boolean? = null,
    val eqManaged: Boolean = false, val eqPending: Boolean = false, val eqInventory: HuaweiEqSnapshot? = null,
    val equalizer: HuaweiEqualizerState? = null, val gestures: List<HuaweiGestureState> = emptyList(),
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings get() = pairedDevices!=null || lowLatency != null || preferSoundQuality != null || wearDetection != null || multipoint != null || equalizer != null ||
        gestures.any { it.left != null || it.right != null || it.call != null }
    fun supports(request: HuaweiDailyControlRequest): Boolean = when (request) {
        is HuaweiDailyControlRequest.ManageDevice -> pairedDevices==request.expected && pairedDevices?.supports(request.target,request.action)==true
        is HuaweiDailyControlRequest.SetLowLatency -> lowLatency != null
        is HuaweiDailyControlRequest.SetSoundQualityPreference -> preferSoundQuality != null
        is HuaweiDailyControlRequest.SetWearDetection -> wearDetection != null
        is HuaweiDailyControlRequest.SetMultipoint -> multipoint != null
        is HuaweiDailyControlRequest.ManageEqualizer -> eqManaged && !eqPending && eqInventory?.supports(request)==true
        is HuaweiDailyControlRequest.SetEqualizer -> !eqManaged && request.preset in equalizer?.presets.orEmpty()
        is HuaweiDailyControlRequest.SetCustomEqualizer -> equalizer?.let { eq ->
            !eqManaged && eq.customWritable && eq.custom.any { it.id == request.id && it == request.expectedSlot } &&
                request.gains.size == 10 && request.gains.all { it in -60..60 }
        } == true
        is HuaweiDailyControlRequest.SetGesture -> gestures.singleOrNull { it.kind == request.kind }?.let {
            (if (it.modelId != null) request.expected == it && it.raw != null && it.left != null && it.right != null
                else request.expected == null) && it.value(request.side) != null && request.value in
                (if (request.side == HuaweiGestureSide.CALL) listOf(-1, 0) else it.available)
        } == true
    }
    companion object { const val FEATURE_ID = "huawei.daily_settings"; const val LOW_LATENCY_QUERY = "huawei.low_latency_readback" }
}
@Serializable
sealed interface HuaweiDailyControlRequest : ControlRequest {
    @Serializable @SerialName("huawei.manage_equalizer")
    data class ManageEqualizer(val expected:HuaweiEqSnapshot,val action:HuaweiEqAction,val id:Int,
        val name:String="",val gains:List<Int> = emptyList()):HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.manage_device")
    data class ManageDevice(val expected:HuaweiPairedSnapshot,val target:String,val action:HuaweiPairedAction):HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_low_latency")
    data class SetLowLatency(val enabled: Boolean) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_sound_quality_preference")
    data class SetSoundQualityPreference(val preferQuality: Boolean) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_wear_detection")
    data class SetWearDetection(val enabled: Boolean) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_multipoint")
    data class SetMultipoint(val enabled: Boolean) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_equalizer_preset")
    data class SetEqualizer(val preset: Int) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_custom_equalizer")
    data class SetCustomEqualizer(val id: Int, val gains: List<Int>, val expectedSlot: HuaweiCustomEqualizer) : HuaweiDailyControlRequest
    @Serializable @SerialName("huawei.set_gesture")
    data class SetGesture(val kind: HuaweiGestureKind, val side: HuaweiGestureSide, val value: Int, val expected: HuaweiGestureState? = null) : HuaweiDailyControlRequest
}
object HuaweiDailyPresentationIds {
    val SETTINGS = MiLinkCardPresentationId("huawei-daily-settings")
}
