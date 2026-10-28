package dev.hyperears.hook

import dev.hyperears.integration.*

internal object BoseDailyCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) && state.sessionActive &&
        state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<BoseDailySettingsFeatureState>()?.hasSettings == true
    fun matches(state: BoseDailySettingsFeatureState, request: BoseDailyControlRequest) = when (request) {
        is BoseDailyControlRequest.SetEqBand -> state.eq?.firstOrNull { it.definition == request.expected }?.current == request.value
        is BoseDailyControlRequest.SetVoicePrompts -> state.promptsRaw?.let { it and 32 != 0 } == request.enabled
        is BoseDailyControlRequest.SetMultipoint -> state.multipoint == request.enabled
        is BoseDailyControlRequest.SetAutoPause -> state.autoPause == request.enabled
        is BoseDailyControlRequest.SetCncLevel -> state.cnc?.let { it.enabled && it.rawLevel == request.rawLevel } == true
        is BoseDailyControlRequest.SetAnr -> state.anr == request.value
    }
    fun request(state: EarbudState, address: String, request: BoseDailyControlRequest): BoseDailyControlRequest? {
        if (!available(state, address)) return null
        val settings = state.features.get<BoseDailySettingsFeatureState>() ?: return null
        return request.takeIf { settings.supports(it) && !matches(settings, it) }
    }
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? {
        if (!ready(state, address) || state.noiseMode !in MODES || mode !in MODES || mode == state.noiseMode) return null
        return StandardControlRequest.SetNoiseMode(mode)
    }
    val MODES = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF)
}
