package dev.hyperears.hook

import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SamsungBudsSettingsFeatureState
import dev.hyperears.integration.SamsungControlRequest
import dev.hyperears.integration.SamsungMiLinkPresentationIds

/** Keeps MiLink's native ANC/off/ambient row and adds two protocol-backed Buds2 Pro options. */
internal object SamsungBuds2ProMiLinkCardAdapter : NativeAncToggleMiLinkCardAdapter(
    presentationId = SamsungMiLinkPresentationIds.BUDS2_PRO,
    modelLabel = "Samsung Galaxy Buds",
    toggles = listOf(SamsungTouchLockToggleSpec, SamsungVoiceDetectToggleSpec),
)

internal object SamsungTouchLockToggleSpec : MiLinkToggleDetailsSpec {
    override val label: String = "\u89e6\u6478\u63a7\u5236"
    override fun render(state: EarbudState): MiLinkToggleState = state.samsungToggle { !touchpadLocked }
    override fun request(state: EarbudState, checked: Boolean): SamsungControlRequest.SetTouchpadLock? =
        state.samsungRequest(checked) { !touchpadLocked }
            ?.let { touchEnabled -> SamsungControlRequest.SetTouchpadLock(!touchEnabled) }

    override fun openDetails(
        anchor: android.view.View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkToggleDetails = SamsungTouchControlDetails.open(anchor, address, environment)
}

internal object SamsungVoiceDetectToggleSpec : MiLinkToggleSpec {
    override val label: String = "\u8bed\u97f3\u68c0\u6d4b"
    override fun render(state: EarbudState): MiLinkToggleState {
        val supported = state.features.get<SamsungBudsSettingsFeatureState>()?.voiceDetectSupported == true
        return state.samsungToggle { voiceDetectEnabled }.let {
            it.copy(available = it.available && supported, enabled = it.enabled && supported)
        }
    }
    override fun request(state: EarbudState, checked: Boolean): SamsungControlRequest.SetVoiceDetect? {
        if (state.features.get<SamsungBudsSettingsFeatureState>()?.voiceDetectSupported != true) return null
        return state.samsungRequest(checked) { voiceDetectEnabled }?.let(SamsungControlRequest::SetVoiceDetect)
    }
}

private fun EarbudState.samsungToggle(value: SamsungBudsSettingsFeatureState.() -> Boolean): MiLinkToggleState {
    val feature = features.get<SamsungBudsSettingsFeatureState>()
    return MiLinkToggleState(
        checked = feature?.value() == true,
        enabled = sessionActive && connected && feature != null,
        available = feature != null,
    )
}

private fun EarbudState.samsungRequest(
    checked: Boolean,
    value: SamsungBudsSettingsFeatureState.() -> Boolean,
): Boolean? {
    val feature = features.get<SamsungBudsSettingsFeatureState>() ?: return null
    return checked.takeIf { sessionActive && connected && feature.value() != checked }
}
