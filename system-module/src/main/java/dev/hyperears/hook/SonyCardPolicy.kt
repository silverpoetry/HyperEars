package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SonyCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) && state.sessionActive &&
        state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) && (
        state.features.get<SonyEqFeatureState>()?.let { it.writablePreset || it.writableBands || it.writableBass } == true ||
            state.features.get<SonyAmbientFeatureState>()?.level in 1..20 ||
            state.features.get<SonyAssignableFeatureState>()?.hasSettings == true ||
            state.features.get<SonyAudioSettingsFeatureState>()?.hasSettings == true ||
            state.features.get<SonyMultipointFeatureState>()?.hasSettings == true)
    fun request(state: EarbudState, address: String, request: SonyControlRequest): SonyControlRequest? {
        if (!ready(state, address)) return null
        val supported = when (request) {
            is SonyControlRequest.SetAmbient -> state.features.get<SonyAmbientFeatureState>()?.supports(request) == true
            else -> state.features.get<SonyEqFeatureState>()?.supports(request) == true ||
                state.features.get<SonyAssignableFeatureState>()?.supports(request) == true ||
                state.features.get<SonyAudioSettingsFeatureState>()?.supports(request) == true ||
                state.features.get<SonyMultipointFeatureState>()?.supports(request) == true
        }
        return request.takeIf { supported }
    }
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? =
        StandardControlRequest.SetNoiseMode(mode).takeIf { ready(state, address) &&
            state.noiseMode != null && mode in state.adapter?.supportedNoiseModes.orEmpty() && mode != state.noiseMode }
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
}
