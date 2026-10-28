package dev.hyperears.hook

import dev.hyperears.integration.*

internal object QcyCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
        state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<QcySettingsFeatureState>() != null
    fun request(state: EarbudState, address: String, request: QcyControlRequest): QcyControlRequest? {
        if (!ready(state, address)) return null
        val settings = state.features.get<QcySettingsFeatureState>() ?: return null
        return request.takeIf { settings.supports(it) && !matches(settings, it) }
    }
    fun matches(state: QcySettingsFeatureState, request: QcyControlRequest): Boolean = when (request) {
        is QcyControlRequest.SetToggle -> state.toggles[request.setting] == request.enabled
        is QcyControlRequest.SetSpatialMode -> state.spatialMode == request.mode
        is QcyControlRequest.SetEqPreset -> state.eqPreset == request.preset
        is QcyControlRequest.SetCustomEq -> state.eqPreset == 0 && state.equalizer?.customGains() == request.gains
    }
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? {
        if (!ready(state, address) || state.noiseMode !in MODES || mode !in MODES || mode == state.noiseMode) return null
        return StandardControlRequest.SetNoiseMode(mode)
    }
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
}
