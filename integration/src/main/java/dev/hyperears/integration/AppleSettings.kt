package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleAapWireCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class AppleMicrophone(val wireValue: Int) {
    @SerialName("automatic") AUTOMATIC(0),
    @SerialName("right") RIGHT(1),
    @SerialName("left") LEFT(2),
}

/** Neutral source-defined levels: no inferred timing direction or milliseconds. */
@Serializable
enum class AppleIntervalLevel(val wireValue: Int) {
    @SerialName("level_0") LEVEL_0(0),
    @SerialName("level_1") LEVEL_1(1),
    @SerialName("level_2") LEVEL_2(2),
}

@Serializable
enum class AppleCallMapping {
    @SerialName("default") DEFAULT,
    @SerialName("flipped") FLIPPED,
}

/** Last valid current-session reports. Null means no valid report, never an inferred default.
 * Malformed reports retain the previous value and capability, matching Apple listening behavior.
 * Raw unowned payload bytes stay private to the protocol session and are cleared on reset.
 */
@Serializable
@SerialName("apple.settings")
data class AppleSettingsFeatureState(
    val microphone: AppleMicrophone? = null,
    val oneBudAnc: Boolean? = null,
    val conversationAwareness: Boolean? = null,
    val personalizedVolume: Boolean? = null,
    val wearingDetection: Boolean? = null,
    val volumeSwipe: Boolean? = null,
    val doublePressInterval: AppleIntervalLevel? = null,
    val holdInterval: AppleIntervalLevel? = null,
    val swipeInterval: AppleIntervalLevel? = null,
    /** Wire percentage, 0..100. Adjustment additionally requires actual ADAPTIVE mode. */
    val adaptiveStrength: Int? = null,
    val callMapping: AppleCallMapping? = null,
    val allowOff: Boolean? = null,
    val chimeVolume: Int? = null,
    val sleepPause: Boolean? = null,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object { const val FEATURE_ID = "apple.settings" }
}

/** Same semantic validation at adapter admission and at the session encoding boundary. */
internal fun AppleSettingsFeatureState.supports(
    request: AppleControlRequest,
    listeningMode: AppleListeningMode?,
): Boolean = when (request) {
    is AppleControlRequest.SetMicrophone -> microphone != null
    is AppleControlRequest.SetOneBudAnc -> oneBudAnc != null
    is AppleControlRequest.SetConversationAwareness -> conversationAwareness != null
    is AppleControlRequest.SetPersonalizedVolume -> personalizedVolume != null
    is AppleControlRequest.SetWearingDetection -> wearingDetection != null
    is AppleControlRequest.SetVolumeSwipe -> volumeSwipe != null
    is AppleControlRequest.SetDoublePressInterval -> doublePressInterval != null
    is AppleControlRequest.SetHoldInterval -> holdInterval != null
    is AppleControlRequest.SetSwipeInterval -> swipeInterval != null
    is AppleControlRequest.SetAdaptiveStrength -> adaptiveStrength in 0..100 && request.value in 0..100 &&
        listeningMode == AppleListeningMode.ADAPTIVE
    is AppleControlRequest.SetCallMapping -> callMapping != null
    is AppleControlRequest.SetAllowOff -> allowOff != null
    is AppleControlRequest.SetChimeVolume -> chimeVolume in 15..100 && request.value in 15..100
    is AppleControlRequest.SetSleepPause -> sleepPause != null
    else -> false
}

/** AndPods frame schema plus active HyperPods-X controls; see apple-settings-followup.md.
 * Invalid/unknown reports do not revoke last valid state or replace the associated raw bytes.
 */
internal fun AppleSettingsFeatureState.acceptReport(report: AppleAapWireCodec.State.Control): AppleSettingsFeatureState? {
    if (report.values.size != 4 || report.values.any { it !in 0..255 }) return null
    val value = report.values[0]
    return when (report.identifier) {
        0x01 -> if (value in 0..2) copy(microphone = AppleMicrophone.entries.first { it.wireValue == value }) else null
        0x1b -> if (value in 1..2) copy(oneBudAnc = value == 1) else null
        0x28 -> if (value in 1..2) copy(conversationAwareness = value == 1) else null
        0x26 -> if (value in 1..2) copy(personalizedVolume = value == 1) else null
        0x0a -> if (value in 1..2) copy(wearingDetection = value == 1) else null
        0x25 -> if (value in 1..2) copy(volumeSwipe = value == 1) else null
        0x17 -> if (value in 0..2) copy(doublePressInterval = AppleIntervalLevel.entries.first { it.wireValue == value }) else null
        0x18 -> if (value in 0..2) copy(holdInterval = AppleIntervalLevel.entries.first { it.wireValue == value }) else null
        0x23 -> if (value in 0..2) copy(swipeInterval = AppleIntervalLevel.entries.first { it.wireValue == value }) else null
        0x2e -> if (value in 0..100) copy(adaptiveStrength = value) else null
        // Call mapping owns byte 1; byte 0 and the remaining bytes are preserved, not interpreted.
        0x24 -> when (report.values[1]) {
            2 -> copy(callMapping = AppleCallMapping.FLIPPED)
            3 -> copy(callMapping = AppleCallMapping.DEFAULT)
            else -> null
        }
        0x34 -> if (value in 1..2) copy(allowOff = value == 1) else null
        0x1f -> if (value in 15..100) copy(chimeVolume = value) else null
        0x35 -> if (value in 1..2) copy(sleepPause = value == 1) else null
        else -> null
    }
}

internal data class AppleSettingWrite(val identifier: Int, val index: Int, val value: Int)

internal fun AppleControlRequest.settingWrite(): AppleSettingWrite? = when (this) {
    is AppleControlRequest.SetMicrophone -> AppleSettingWrite(0x01, 0, value.wireValue)
    is AppleControlRequest.SetOneBudAnc -> AppleSettingWrite(0x1b, 0, if (value) 1 else 2)
    is AppleControlRequest.SetConversationAwareness -> AppleSettingWrite(0x28, 0, if (value) 1 else 2)
    is AppleControlRequest.SetPersonalizedVolume -> AppleSettingWrite(0x26, 0, if (value) 1 else 2)
    is AppleControlRequest.SetWearingDetection -> AppleSettingWrite(0x0a, 0, if (value) 1 else 2)
    is AppleControlRequest.SetVolumeSwipe -> AppleSettingWrite(0x25, 0, if (value) 1 else 2)
    is AppleControlRequest.SetDoublePressInterval -> AppleSettingWrite(0x17, 0, value.wireValue)
    is AppleControlRequest.SetHoldInterval -> AppleSettingWrite(0x18, 0, value.wireValue)
    is AppleControlRequest.SetSwipeInterval -> AppleSettingWrite(0x23, 0, value.wireValue)
    is AppleControlRequest.SetAdaptiveStrength -> AppleSettingWrite(0x2e, 0, value)
    is AppleControlRequest.SetCallMapping -> AppleSettingWrite(0x24, 1, if (value == AppleCallMapping.FLIPPED) 2 else 3)
    is AppleControlRequest.SetAllowOff -> AppleSettingWrite(0x34, 0, if (value) 1 else 2)
    is AppleControlRequest.SetChimeVolume -> AppleSettingWrite(0x1f, 0, value)
    is AppleControlRequest.SetSleepPause -> AppleSettingWrite(0x35, 0, if (value) 1 else 2)
    else -> null
}
