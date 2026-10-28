package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable
data class SamsungCustomAmbient(val enabled: Boolean, val left: Int, val right: Int, val tone: Int, val maximum: Int)

@Serializable @SerialName("samsung.daily_settings")
data class SamsungDailySettingsFeatureState(
    val model: String,
    val revision: Int,
    val equalizerEnabled: Boolean? = null,
    /** 0..4 presets; 6 is custom. Mode confirmation does not confirm curve gains. */
    val equalizerPreset: Int? = null,
    val ambientVolume: Int? = null,
    val ambientMaximum: Int = 0,
    val ancLevel: Int? = null,
    val customAmbient: SamsungCustomAmbient? = null,
    val locked: Boolean? = null,
    val leftHold: Int? = null,
    val rightHold: Int? = null,
    val voiceFocus: Boolean? = null,
    val customEqAvailable: Boolean = false,
    val findLeftMuted: Boolean? = null,
    val findRightMuted: Boolean? = null,
    val findStopped: Boolean? = null,
    val conversationTimeoutSeconds: Int? = null,
    /** Local workflow ownership, not a device-running report. */
    val fitLease:Long?=null,val fitResult:List<Int>?=null,val fitResultLease:Long?=null,
) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    val sourceModel: SamsungBudsCodec.Model get() = SamsungBudsCodec.Model.entries.firstOrNull { it.name == model } ?: SamsungBudsCodec.Model.UNKNOWN
    fun supports(request: SamsungDailyControlRequest): Boolean = when (request) {
        is SamsungDailyControlRequest.FitTest -> dev.hyperears.protocol.samsung.SamsungFitTestCodec.supported(sourceModel) &&
            request.expectedModel==model && request.lease>0 &&
            (if(request.start)request.expectedRevision==revision && fitLease==null else fitLease==request.lease)
        is SamsungDailyControlRequest.SetConversationTimeout -> sourceModel == SamsungBudsCodec.Model.BUDS2_PRO &&
            conversationTimeoutSeconds != null && request.seconds in setOf(5,10,15)
        is SamsungDailyControlRequest.SetEqualizer -> equalizerEnabled != null && equalizerPreset != null && request.preset in 0..4
        is SamsungDailyControlRequest.SetAmbientVolume -> ambientVolume != null && request.level in 0..ambientMaximum
        is SamsungDailyControlRequest.SetAncLevel -> ancLevel != null && request.level in 0..1
        is SamsungDailyControlRequest.SetCustomAmbient -> customAmbient?.let { request.expected == it &&
            request.left in 0..it.maximum && request.right in 0..it.maximum && request.tone in 0..4 } == true
        is SamsungDailyControlRequest.SetCustomEqualizer -> customEqAvailable && equalizerEnabled != null && equalizerPreset != null &&
            request.expectedModel == model && request.expectedRevision == revision && request.expectedPreset == equalizerPreset && request.expectedEnabled == equalizerEnabled && request.gains.size == 9 && request.gains.all { it in -10..10 }
        is SamsungDailyControlRequest.SetLegacyLock -> sourceModel.ambientOnly && locked != null
        is SamsungDailyControlRequest.SetLegacyHold -> sourceModel.ambientOnly && leftHold != null && rightHold != null &&
            request.left in (if (sourceModel.original) 0..4 else 1..4) && request.right in (if (sourceModel.original) 0..4 else 1..4)
        is SamsungDailyControlRequest.SetVoiceFocus -> sourceModel.original && voiceFocus != null
        is SamsungDailyControlRequest.Find -> sourceModel != SamsungBudsCodec.Model.UNKNOWN
        is SamsungDailyControlRequest.MuteFind -> sourceModel != SamsungBudsCodec.Model.UNKNOWN
    }
    companion object { const val FEATURE_ID = "samsung.daily_settings" }
}

/** Transmission intent only. Neither custom curve gains nor start-ringing have reliable status reports. */
@Serializable @SerialName("samsung.daily_intent")
data class SamsungDailyIntentFeatureState(val submittedCurve: List<Int>? = null, val findRequested: Boolean? = null, val curveWritten: Boolean = false, val findWritten: Boolean = false) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "samsung.daily_intent" }
}

@Serializable
sealed interface SamsungDailyControlRequest : ControlRequest {
    @Serializable @SerialName("samsung.fit_test") data class FitTest(val expectedModel:String,val expectedRevision:Int,val lease:Long,val start:Boolean):SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_conversation_timeout") data class SetConversationTimeout(val seconds: Int) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_eq") data class SetEqualizer(val enabled: Boolean, val preset: Int) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_ambient") data class SetAmbientVolume(val level: Int) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_anc_level") data class SetAncLevel(val level: Int) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_custom_ambient") data class SetCustomAmbient(val enabled: Boolean, val left: Int,
        val right: Int, val tone: Int, val expected: SamsungCustomAmbient) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_custom_eq") data class SetCustomEqualizer(val gains: List<Int>,
        val expectedModel: String, val expectedRevision: Int, val expectedPreset: Int, val expectedEnabled: Boolean) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_legacy_lock") data class SetLegacyLock(val locked: Boolean) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_legacy_hold") data class SetLegacyHold(val left: Int, val right: Int) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_voice_focus") data class SetVoiceFocus(val enabled: Boolean) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_find") data class Find(val start: Boolean) : SamsungDailyControlRequest
    @Serializable @SerialName("samsung.daily_find_mute") data class MuteFind(val left: Boolean, val right: Boolean) : SamsungDailyControlRequest
}
object SamsungDailyPresentationIds { val SETTINGS = MiLinkCardPresentationId("samsung-daily-settings") }
