package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec
import dev.hyperears.protocol.rose.RoseZtWireCodec
import kotlinx.serialization.Serializable

/** A command target, never a reported ringing boolean. */
@Serializable enum class RoseFindTarget { LEFT, RIGHT, BOTH, HEADSET, STOP }
/** Local successful-write ownership. No device acknowledgment is implied. */
@Serializable data class RoseFindWrittenIntent(val lease: Long, val target: RoseFindTarget)

fun RoseLegacyIdentityCodec.Model.findTargets(): Set<RoseFindTarget> = when(this) {
    RoseLegacyIdentityCodec.Model.CAMBRIAN, RoseLegacyIdentityCodec.Model.SWIFT -> setOf(RoseFindTarget.HEADSET)
    RoseLegacyIdentityCodec.Model.EXPLORER, RoseLegacyIdentityCodec.Model.OPENFUN,
    RoseLegacyIdentityCodec.Model.OPENLINK, RoseLegacyIdentityCodec.Model.SPORTFEEL -> setOf(RoseFindTarget.LEFT,RoseFindTarget.RIGHT,RoseFindTarget.BOTH)
    else -> emptySet()
}
fun RoseZtWireCodec.Model.findTargets(): Set<RoseFindTarget> = when(this) {
    RoseZtWireCodec.Model.HEADFEEL -> setOf(RoseFindTarget.HEADSET)
    RoseZtWireCodec.Model.I5_SOURCE -> setOf(RoseFindTarget.LEFT,RoseFindTarget.RIGHT)
    else -> emptySet()
}
internal fun supportsRoseFind(available: Boolean, model: String, expectedModel: String, targets: Set<RoseFindTarget>,
    intent: RoseFindWrittenIntent?, lease: Long, target: RoseFindTarget): Boolean =
    available && expectedModel == model && lease > 0 && if(target == RoseFindTarget.STOP) intent?.lease == lease
    else intent == null && target in targets
