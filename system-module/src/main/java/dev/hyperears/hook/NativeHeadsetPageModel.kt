package dev.hyperears.hook
import dev.hyperears.integration.*
internal object NativeHeadsetPageModel {
    fun sony(state: EarbudState) = state.adapter?.id == "sony-wh-1000xm6"
    fun eligible(state: EarbudState) = sony(state) && state.sessionActive
    fun modes(state: EarbudState) = SonyCardPolicy.MODES.filter { it in state.adapter?.supportedNoiseModes.orEmpty() }
    fun ready(state: EarbudState, address: String) = SonyCardPolicy.ready(state, address)
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode) = SonyCardPolicy.noiseRequest(state, address, mode)
    fun support(address: String, state: EarbudState): String {
        val flags = CharArray(24) { '0' }
        if (state.noiseMode != null && state.noiseMode in modes(state)) flags[16] = '1'
        return "$address,${flags.concatToString()}"
    }
    fun anc(state: EarbudState): String? = when (state.noiseMode?.takeIf { it in modes(state) }) {
        NoiseMode.ANC -> "0100"
        NoiseMode.OFF -> "0000"
        NoiseMode.TRANSPARENCY -> "0200"
        else -> null
    }
    fun battery(state: EarbudState): String {
        val reading = state.battery.overall
        val encoded = if (!reading.available) 255 else requireNotNull(reading.percent) or if (reading.charging) 128 else 0
        return "255,$encoded,255"
    }
    fun artwork(state: EarbudState) = requireNotNull(state.adapter).artwork.headset
}
