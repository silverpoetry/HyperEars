package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Shared lifecycle for models that explicitly opt into BES game-mode telemetry. */
abstract class EdifierGameModeAdapter : EdifierEarbudAdapter() {
    final override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is EdifierGameModeFeatureState
        }

    final override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is EdifierControlRequest.SetGameMode &&
                adapter.runtimeState().features.get<EdifierGameModeFeatureState>() != null
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is EdifierControlRequest.SetGameMode ->
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        else -> super.controlPolicy(request)
    }

    final override fun onProtocolReset() {
        super.onProtocolReset()
        removeFeatureState(EdifierGameModeFeatureState.FEATURE_ID)
    }
}

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
