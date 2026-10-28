package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleAapWireCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class AppleWearPosition { IN_EAR, OUT_OF_EAR, IN_CASE, DISCONNECTED }

/** Actual telemetry, independent of the wearing-detection configuration switch. */
@Serializable
@SerialName("apple.wear")
data class AppleWearFeatureState(val primary: AppleWearPosition, val secondary: AppleWearPosition) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val inEarCount: Int get() = listOf(primary, secondary).count { it == AppleWearPosition.IN_EAR }
    companion object { const val FEATURE_ID = "apple.wear" }
}

@Serializable
enum class AppleConversationActivity { START, RELEASE, UNKNOWN }

/** Raw source values survive transport. Only subtype 1 has established host semantics. */
@Serializable
@SerialName("apple.conversation")
data class AppleConversationFeatureState(val subtype: Int, val status: Int) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    /** AndPods primary mapping; status 3/7 is not attributed to that source. */
    val primaryActivity: AppleConversationActivity get() = when {
        subtype != 1 -> AppleConversationActivity.UNKNOWN
        status in setOf(1, 2) -> AppleConversationActivity.START
        status in setOf(6, 8, 9) -> AppleConversationActivity.RELEASE
        else -> AppleConversationActivity.UNKNOWN
    }
    /** Explicit supplementary HyperPods-X/LibrePods mapping. */
    val supplementaryActivity: AppleConversationActivity get() = when {
        subtype == 1 && status == 3 -> AppleConversationActivity.START
        subtype == 1 && status == 7 -> AppleConversationActivity.RELEASE
        else -> primaryActivity
    }
    companion object { const val FEATURE_ID = "apple.conversation" }
}

@Serializable
enum class AppleStemPress { SINGLE, DOUBLE, TRIPLE, LONG }
@Serializable
enum class AppleBud { LEFT, RIGHT }
@Serializable
enum class AppleLongPressAction(val wireValue: Int) { NOISE_CONTROL(1), DIGITAL_ASSISTANT(5) }

/** Monotonic session-local sequence prevents identical physical presses being deduplicated. */
@Serializable
@SerialName("apple.stem")
data class AppleStemFeatureState(val press: AppleStemPress, val bud: AppleBud, val sequence: Long) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "apple.stem" }
}

@Serializable
@SerialName("apple.gesture_settings")
data class AppleGestureSettingsFeatureState(
    val rightLongPress: AppleLongPressAction? = null,
    val leftLongPress: AppleLongPressAction? = null,
    /** Bits 1 off, 2 ANC, 4 transparency, 8 adaptive; no default inferred from model. */
    val listeningCycleMask: Int? = null,
    /** Observation only. Writing disables firmware handling and requires a separate host lease. */
    val interceptionMask: Int? = null,
    /** Counts actual routing reports so a cached snapshot cannot acknowledge a new lease. */
    val interceptionSequence: Long = 0,
) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "apple.gesture_settings" }
}

@Serializable
@SerialName("apple.equalizer")
data class AppleEqualizerFeatureState(val state: Int, val low: Int, val mid: Int, val high: Int) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    companion object { const val FEATURE_ID = "apple.equalizer" }
}

internal fun AppleGestureSettingsFeatureState.acceptReport(report: AppleAapWireCodec.State.Control): AppleGestureSettingsFeatureState? {
    val v = report.values
    if (v.size != 4 || v.any { it !in 0..255 }) return null
    return when (report.identifier) {
        0x16 -> {
            val right = AppleLongPressAction.entries.find { it.wireValue == v[0] } ?: return null
            val left = AppleLongPressAction.entries.find { it.wireValue == v[1] } ?: return null
            copy(rightLongPress = right, leftLongPress = left)
        }
        0x1a -> if (v[0] in 1..15) copy(listeningCycleMask = v[0]) else null
        0x39 -> copy(interceptionMask = v[0] and 15, interceptionSequence = interceptionSequence + 1)
        else -> null
    }
}

internal fun AppleGestureSettingsFeatureState.supports(request: AppleControlRequest, modes: Set<AppleListeningMode>): Boolean = when (request) {
    is AppleControlRequest.SetLongPress -> rightLongPress != null && leftLongPress != null
    is AppleControlRequest.SetListeningCycle -> listeningCycleMask != null && request.mask in 1..15 &&
        modes.isNotEmpty() && request.mask and modes.fold(0) { mask, mode -> mask or mode.cycleBit } == request.mask
    else -> false
}

internal val AppleListeningMode.cycleBit: Int get() = when (this) {
    AppleListeningMode.OFF -> 1
    AppleListeningMode.ANC -> 2
    AppleListeningMode.TRANSPARENCY -> 4
    AppleListeningMode.ADAPTIVE -> 8
}

internal fun AppleControlRequest.SetEqualizer.valid(): Boolean =
    state in 1..2 && listOf(low, mid, high).all { it in 0..100 }
