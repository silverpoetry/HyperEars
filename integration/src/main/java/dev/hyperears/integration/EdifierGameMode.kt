package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Edifier game-mode (低延迟游戏模式) extension, verified on Edifier W820NB 双金标版
 * (Edifier Connect v8.4.48 live capture): query `0x08` -> `[0/1]`, set `0x09` with `[1]`/`[0]`.
 *
 * Game mode is a model-specific boolean feature, exposed as a typed [DeviceFeatureState] and a
 * typed [ControlRequest] sibling of the standard requests. The card toggle renders only from
 * authoritative adapter state; a write is accepted only after the first valid game-state report.
 */
@Serializable
@SerialName("edifier.game_mode")
data class GameModeFeatureState(
    val enabled: Boolean,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "edifier.game_mode"
    }
}

/** Model-owned control requests for Edifier devices. */
@Serializable
sealed interface EdifierControlRequest : ControlRequest {
    @Serializable
    @SerialName("edifier.set_game_mode")
    data class SetGameMode(
        val enabled: Boolean,
    ) : EdifierControlRequest
}
