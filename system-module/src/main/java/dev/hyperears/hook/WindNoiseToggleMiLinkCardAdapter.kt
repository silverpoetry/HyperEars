package dev.hyperears.hook

import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest

/** Protocol-confirmed wind option on the shared native ANC host. */
internal open class WindNoiseToggleMiLinkCardAdapter(
    presentationId: MiLinkCardPresentationId,
    modelLabel: String,
) : NativeAncToggleMiLinkCardAdapter(presentationId, modelLabel, listOf(WindNoiseToggleSpec))

internal object WindNoiseToggleSpec : MiLinkToggleSpec {
    override val label = "抗风噪"

    override fun render(state: EarbudState): MiLinkToggleState {
        val value = WindNoiseToggleControlPolicy.render(state)
        return MiLinkToggleState(
            checked = value.checked,
            enabled = value.enabled,
            available = state.adapter?.capabilities?.windNoiseControl == true,
        )
    }

    override fun request(state: EarbudState, checked: Boolean): StandardControlRequest.SetNoiseMode? =
        WindNoiseToggleControlPolicy.request(state, checked)?.let(StandardControlRequest::SetNoiseMode)
}

/** Pure four-state-to-switch policy; UI code contains no independent mode state. */
internal object WindNoiseToggleControlPolicy {
    data class ToggleState(val checked: Boolean, val enabled: Boolean)

    fun render(state: EarbudState): ToggleState {
        val inAncBranch = state.noiseMode == NoiseMode.ANC || state.noiseMode == NoiseMode.WIND
        return ToggleState(
            checked = state.noiseMode == NoiseMode.WIND,
            enabled = state.sessionActive && state.connected && inAncBranch,
        )
    }

    fun request(state: EarbudState, checked: Boolean): NoiseMode? {
        if (!render(state).enabled) return null
        return when {
            checked && state.noiseMode == NoiseMode.ANC -> NoiseMode.WIND
            !checked && state.noiseMode == NoiseMode.WIND -> NoiseMode.ANC
            else -> null
        }
    }
}
