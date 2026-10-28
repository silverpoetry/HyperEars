package dev.hyperears.hook

import dev.hyperears.integration.*

internal object PuddingCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) && state.sessionActive &&
        state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<PuddingSettingsFeatureState>()?.values?.isNotEmpty() == true
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? {
        if (!ready(state,address) || state.features.get<PuddingSettingsFeatureState>()?.values?.containsKey(PuddingSetting.NOISE) != true || mode !in MODES || mode == state.noiseMode) return null
        return StandardControlRequest.SetNoiseMode(mode)
    }
    fun matches(state: PuddingSettingsFeatureState, request: PuddingControlRequest.SetValue) =
        state.values[request.setting] == request.value
    fun request(state: EarbudState, address: String, request: PuddingControlRequest.SetValue): PuddingControlRequest.SetValue? {
        if (!available(state, address)) return null
        val settings = state.features.get<PuddingSettingsFeatureState>() ?: return null
        return request.takeIf { settings.supports(it) && !matches(settings, it) }
    }
}
