package dev.hyperears.integration

import dev.hyperears.protocol.tozo.TozoTouchCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class TozoTouchSlot(val offset: Int) {
    LEFT_SINGLE(0), LEFT_DOUBLE(1), LEFT_TRIPLE(2), LEFT_HOLD(3),
    RIGHT_SINGLE(5), RIGHT_DOUBLE(6), RIGHT_TRIPLE(7), RIGHT_HOLD(8),
}
@Serializable
enum class TozoTouchAction(val code: Int) {
    NONE(0), PLAY_PAUSE(1), NEXT(2), PREVIOUS(3), ASSISTANT(4), VOLUME_UP(5),
    VOLUME_DOWN(6), NOISE_CYCLE(8), ANC_OFF_CYCLE(10),
}
@Serializable @SerialName("tozo.nc20pro.touch")
data class TozoTouchFeatureState(val raw: List<Int>, val awaitingReadback: Boolean = false) : DeviceFeatureState {
    override val featureId: String get() = ID
    val valid: Boolean get() = raw.size == 10 && raw.all { it in 0..255 }
    fun action(slot: TozoTouchSlot): TozoTouchAction? = if (!valid) null else
        TozoTouchAction.entries.find { it.code == raw[slot.offset] }
    fun actions(slot: TozoTouchSlot): List<TozoTouchAction> = if (!valid) emptyList() else
        TozoTouchAction.entries.filter { it.code in TozoTouchCodec.actions(slot.offset) }
    fun supports(request: TozoControlRequest.SetTouch) = valid && !awaitingReadback && request.expected == this &&
        request.action in actions(request.slot) && action(request.slot) != request.action
    companion object { const val ID = "tozo.nc20pro.touch" }
}
