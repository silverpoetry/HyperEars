package dev.hyperears.hook

import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.EarbudState

/** PR #50 protocol on the current native host; no text scanning or manual selected-state override. */
internal object EdifierW820NBDoubleGoldMiLinkCardAdapter : NativeAncToggleMiLinkCardAdapter(
    presentationId = EdifierMiLinkPresentationIds.W820NB,
    modelLabel = "Edifier W820NB 双金标版",
    toggles = listOf(EdifierGameModeToggleSpec),
) {
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE
    override fun nativeSurfaceNoiseMode(state: EarbudState) = state.noiseMode
}
