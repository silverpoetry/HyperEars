package dev.hyperears.hook

import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds

/** ANC stays native; wind and game options independently follow protocol evidence. */
internal object EdifierFitBudsTurboMiLinkCardAdapter : NativeAncToggleMiLinkCardAdapter(
    presentationId = EdifierMiLinkPresentationIds.FITBUDS_TURBO,
    modelLabel = "Edifier FitBuds Turbo",
    toggles = listOf(WindNoiseToggleSpec, EdifierGameModeToggleSpec),
)

internal object EdifierGameModeToggleSpec : MiLinkToggleSpec {
    override val label = "游戏模式"

    override fun render(state: EarbudState): MiLinkToggleState = FitBudsTurboGameModePolicy.render(state)

    override fun request(state: EarbudState, checked: Boolean): EdifierControlRequest.SetGameMode? =
        FitBudsTurboGameModePolicy.request(state, checked)?.let(EdifierControlRequest::SetGameMode)
}

internal object FitBudsTurboGameModePolicy {
    fun render(state: EarbudState): MiLinkToggleState {
        val feature = state.features.get<EdifierGameModeFeatureState>()
        return MiLinkToggleState(
            checked = feature?.enabled == true,
            enabled = state.sessionActive && state.connected && feature != null,
            available = feature != null,
        )
    }

    fun request(state: EarbudState, checked: Boolean): Boolean? {
        val current = render(state)
        return checked.takeIf { current.enabled && current.checked != checked }
    }
}
