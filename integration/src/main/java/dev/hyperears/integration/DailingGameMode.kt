package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Present only after a valid device work-mode response. */
@Serializable
@SerialName("dailing.game_mode")
data class DailingGameModeFeatureState(val enabled: Boolean) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "dailing.game_mode"
    }
}

@Serializable
sealed interface DailingControlRequest : ControlRequest {
    @Serializable
    @SerialName("dailing.set_game_mode")
    data class SetGameMode(val enabled: Boolean) : DailingControlRequest
}

object DailingMiLinkPresentationIds {
    val CARD = MiLinkCardPresentationId("dailing-t3-card")
}
