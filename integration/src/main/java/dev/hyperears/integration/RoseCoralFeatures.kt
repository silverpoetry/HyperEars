package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Exact Coral configuration's selectable protocol modes, not negotiated Android codec state. */
@Serializable
enum class RoseCoralAudioMode(val wire: Int) { AAC_SBC(0), LDAC(1), LHDC(2), LC3(3) }

@Serializable
@SerialName("rose.coral.audio")
data class RoseCoralAudioFeatureState(val mode: RoseCoralAudioMode) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.coral.audio" }
}

/** Read-only version bytes; vendor presentation concatenates decimal values without separators. */
@Serializable
@SerialName("rose.coral.info")
data class RoseCoralInfoFeatureState(
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
) : DeviceFeatureState {
    init {
        require(firmwareMajor in 0..255 && firmwareMinor in 0..255 && firmwarePatch in 0..255)
    }
    val firmwareVersion: String get() = "$firmwareMajor$firmwareMinor$firmwarePatch"
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.coral.info" }
}

/** Each nullable field is independently confirmed by a valid report in this session. */
@Serializable
@SerialName("rose.coral.settings")
data class RoseCoralSettingsFeatureState(
    val gameMode: Boolean? = null,
    val multipoint: Boolean? = null,
    val bassLevel: Int? = null,
    val promptVolume: Int? = null,
    // Legacy serialized name: Coral reports true when touch is DISABLED (device verified).
    val touchEnabled: Boolean? = null,
) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.coral.settings" }
}

@Serializable
enum class RoseCoralTouchAction {
    NONE, PLAY_PAUSE, PREVIOUS, NEXT, VOLUME_UP, VOLUME_DOWN, GAME, ASSISTANT, ANC_CYCLE,
}

@Serializable
enum class RoseCoralTouchSlot(val wire: Int) {
    LEFT_SINGLE(1), LEFT_DOUBLE(2), LEFT_TRIPLE(3), LEFT_HOLD(4),
    RIGHT_SINGLE(0x11), RIGHT_DOUBLE(0x12), RIGHT_TRIPLE(0x13), RIGHT_HOLD(0x14),
}

/** Replaced atomically only after all eight distinct slot/action pairs validate. */
@Serializable
@SerialName("rose.coral.touch")
data class RoseCoralTouchFeatureState(
    val leftSingle: RoseCoralTouchAction,
    val leftDouble: RoseCoralTouchAction,
    val leftTriple: RoseCoralTouchAction,
    val leftHold: RoseCoralTouchAction,
    val rightSingle: RoseCoralTouchAction,
    val rightDouble: RoseCoralTouchAction,
    val rightTriple: RoseCoralTouchAction,
    val rightHold: RoseCoralTouchAction,
) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    fun action(slot: RoseCoralTouchSlot): RoseCoralTouchAction = when (slot) {
        RoseCoralTouchSlot.LEFT_SINGLE -> leftSingle
        RoseCoralTouchSlot.LEFT_DOUBLE -> leftDouble
        RoseCoralTouchSlot.LEFT_TRIPLE -> leftTriple
        RoseCoralTouchSlot.LEFT_HOLD -> leftHold
        RoseCoralTouchSlot.RIGHT_SINGLE -> rightSingle
        RoseCoralTouchSlot.RIGHT_DOUBLE -> rightDouble
        RoseCoralTouchSlot.RIGHT_TRIPLE -> rightTriple
        RoseCoralTouchSlot.RIGHT_HOLD -> rightHold
    }
    companion object { const val FEATURE_ID = "rose.coral.touch" }
}

@Serializable
@SerialName("rose.coral.find")
data class RoseCoralFindFeatureState(val left: Boolean? = null, val right: Boolean? = null) : DeviceFeatureState {
    @Transient override val featureId: String = FEATURE_ID
    companion object { const val FEATURE_ID = "rose.coral.find" }
}

@Serializable
enum class RoseCoralFindTarget(val wire: Int) { LEFT(1), RIGHT(2), BOTH(3), STOP(4) }

@Serializable
sealed interface RoseCoralControlRequest : ControlRequest {
    @Serializable @SerialName("rose.coral.set_audio_mode")
    data class SetAudioMode(val mode: RoseCoralAudioMode) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_game_mode")
    data class SetGameMode(val enabled: Boolean) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_multipoint")
    data class SetMultipoint(val enabled: Boolean) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_bass_level")
    data class SetBassLevel(val level: Int) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_prompt_volume")
    data class SetPromptVolume(val level: Int) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_touch_enabled")
    data class SetTouchEnabled(val enabled: Boolean) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_touch")
    data class SetTouch(val slot: RoseCoralTouchSlot, val action: RoseCoralTouchAction) : RoseCoralControlRequest
    @Serializable @SerialName("rose.coral.set_find")
    data class SetFind(val target: RoseCoralFindTarget) : RoseCoralControlRequest
}
