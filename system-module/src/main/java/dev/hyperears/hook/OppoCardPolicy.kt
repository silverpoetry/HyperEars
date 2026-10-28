package dev.hyperears.hook

import dev.hyperears.integration.*

internal object OppoCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
        state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<OppoSettingsFeatureState>()?.hasSettings == true
    fun request(state: EarbudState, address: String, request: OppoControlRequest): OppoControlRequest? {
        if (!ready(state, address)) return null
        val settings = state.features.get<OppoSettingsFeatureState>() ?: return null
        return request.takeIf { settings.supports(it) && !matches(settings, it) }
    }
    fun matches(state: OppoSettingsFeatureState, request: OppoControlRequest): Boolean = when (request) {
        is OppoControlRequest.SetPromptVolume -> state.promptVolume == request.level
        is OppoControlRequest.SetSpatialSound -> state.spatialProductId == request.expectedProductId && state.spatialSound == request.enabled
        is OppoControlRequest.SelectNoiseOption -> state.noise?.let {
            it.definition == request.expected && (it.reportedIndex == request.index ||
                it.selected?.mode == request.expected.option(request.index)?.mode)
        } == true
        is OppoControlRequest.SetToggle -> state.toggles[request.setting] == request.enabled
        is OppoControlRequest.SelectEqualizer -> state.currentEqualizer == request.id
        is OppoControlRequest.UpdateEqualizer -> state.equalizers?.firstOrNull { it.id == request.id }?.gains == request.gains
    }

    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? {
        if (!ready(state, address) || state.noiseMode !in MODES || mode !in MODES || mode == state.noiseMode) return null
        return StandardControlRequest.SetNoiseMode(mode)
    }
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
}
