package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * ANC depth level of the DaiLing T3.
 *
 * A real-device capture shows the vendor app sending a dedicated custom command
 * (`FF [seq] 0E 01 <level>`) after the three-state noise command. Sending only the mode leaves the
 * perceived noise cancellation unchanged, so depth is a model-specific feature rather than part of
 * the standard noise modes.
 */
@Serializable
enum class DailingAncDepth {
    @SerialName("deep")
    DEEP,

    @SerialName("middle")
    MIDDLE,

    @SerialName("light")
    LIGHT,
}

/**
 * ANC depth state of the active session.
 *
 * The RCSP channel never reports the current depth, therefore [current] stays null until a depth
 * request has been written and accepted by this module.
 */
@Serializable
@SerialName("dailing.anc_depth")
data class DailingAncDepthFeatureState(
    val current: DailingAncDepth?,
    val supported: Set<DailingAncDepth>,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "dailing.anc_depth"

        val ALL_DEPTHS: Set<DailingAncDepth> = setOf(
            DailingAncDepth.DEEP,
            DailingAncDepth.MIDDLE,
            DailingAncDepth.LIGHT,
        )
    }
}

/**
 * Play-mode (game / music / spatial) state of the active session.
 *
 * The vendor app switches the play mode with a custom command (`FF [seq] 28 <mode>`), where the
 * headset is expected to run its low-latency game profile in game mode. The RCSP channel does not
 * confirm the current value, so [enabled] stays null until this module writes it.
 */
@Serializable
@SerialName("dailing.game_mode")
data class DailingGameModeFeatureState(
    val enabled: Boolean?,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "dailing.game_mode"
    }
}

/**
 * Dual-device connection state of the active session.
 *
 * The vendor app exposes this as a switch and toggles it with the custom command `1A <0|1>`;
 * [enabled] stays null until the module has read or written a value.
 */
@Serializable
@SerialName("dailing.dual_connection")
data class DailingDualConnectionFeatureState(
    val enabled: Boolean?,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "dailing.dual_connection"
    }
}

/** Model-owned request, sibling of the standard noise-mode request. */
@Serializable
sealed interface DailingControlRequest : ControlRequest {
    @Serializable
    @SerialName("dailing.set_anc_depth")
    data class SetAncDepth(
        val depth: DailingAncDepth,
    ) : DailingControlRequest

    @Serializable
    @SerialName("dailing.set_game_mode")
    data class SetGameMode(
        val enabled: Boolean,
    ) : DailingControlRequest

    @Serializable
    @SerialName("dailing.set_dual_connection")
    data class SetDualConnection(
        val enabled: Boolean,
    ) : DailingControlRequest
}

object DailingMiLinkPresentationIds {
    /** Card extension: game-mode toggle plus the extra ANC-depth row. */
    val CARD = MiLinkCardPresentationId("dailing-t3-card")
}
