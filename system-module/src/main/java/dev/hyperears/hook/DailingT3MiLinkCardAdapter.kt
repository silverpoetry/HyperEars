package dev.hyperears.hook

import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.DailingControlRequest
import dev.hyperears.integration.DailingGameModeFeatureState
import dev.hyperears.integration.DailingMiLinkPresentationIds
import dev.hyperears.integration.EarbudState

/** Retains the native ANC row and More Settings; only adds a protocol-confirmed game toggle. */
internal object DailingT3CardAdapter : NativeAncToggleMiLinkCardAdapter(
    presentationId = DailingMiLinkPresentationIds.CARD,
    modelLabel = "戴灵 T3",
    toggles = listOf(DailingGameModeToggleSpec),
)

internal object DailingGameModeToggleSpec : MiLinkToggleSpec {
    override val label = "游戏模式"

    override fun render(state: EarbudState): MiLinkToggleState {
        val feature = state.features.get<DailingGameModeFeatureState>()
        return MiLinkToggleState(
            checked = feature?.enabled == true,
            enabled = state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
                state.lifecycle.controlOwnership == ControlOwnership.MODULE && feature != null,
            available = feature != null,
        )
    }

    override fun request(state: EarbudState, checked: Boolean): DailingControlRequest.SetGameMode? =
        render(state).let { current ->
            if (current.enabled && current.checked != checked) DailingControlRequest.SetGameMode(checked)
            else null
        }
}
