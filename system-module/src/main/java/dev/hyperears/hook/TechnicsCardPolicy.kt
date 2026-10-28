package dev.hyperears.hook

import dev.hyperears.integration.*

internal object TechnicsCardPolicy {
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
        state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<TechnicsLevelsFeatureState>() != null
    fun matches(state: TechnicsLevelsFeatureState, request: TechnicsControlRequest.SetLevel) =
        state.mode == request.expectedMode && (if (request.ambient) state.ambient else state.anc) == request.value
    fun request(state: EarbudState, address: String, request: TechnicsControlRequest.SetLevel): TechnicsControlRequest.SetLevel? {
        if (!available(state, address)) return null
        val levels = state.features.get<TechnicsLevelsFeatureState>() ?: return null
        return request.takeIf { levels.supports(it) && !matches(levels, it) }
    }
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? =
        StandardControlRequest.SetNoiseMode(mode).takeIf {
            available(state, address) && state.noiseMode in MODES && mode in MODES && state.noiseMode != mode
        }
}
