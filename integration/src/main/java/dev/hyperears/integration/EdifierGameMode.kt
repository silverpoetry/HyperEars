package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Protocol-confirmed low-latency mode exposed only by compatible Edifier adapters. */
@Serializable
@SerialName("edifier.game_mode")
data class EdifierGameModeFeatureState(
    val enabled: Boolean,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "edifier.game_mode"
    }
}

/** Edifier-owned requests remain outside the common refresh and three-state control family. */
@Serializable
sealed interface EdifierControlRequest : ControlRequest {
    @Serializable
    @SerialName("edifier.set_game_mode")
    data class SetGameMode(
        val enabled: Boolean,
    ) : EdifierControlRequest
}
