package dev.hyperears.hook

import dev.hyperears.integration.*

internal object RoseRfcommCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) && state.sessionActive &&
        state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<RoseRfcommSettingsFeatureState>()?.values?.isNotEmpty() == true
    fun matches(state: RoseRfcommSettingsFeatureState, request: RoseRfcommControlRequest.SetValue) =
        state.values[request.setting] == request.value
    fun request(state: EarbudState, address: String, request: RoseRfcommControlRequest.SetValue): RoseRfcommControlRequest.SetValue? {
        if (!available(state, address)) return null
        val settings = state.features.get<RoseRfcommSettingsFeatureState>() ?: return null
        return request.takeIf { settings.supports(it) && !matches(settings, it) }
    }
}
