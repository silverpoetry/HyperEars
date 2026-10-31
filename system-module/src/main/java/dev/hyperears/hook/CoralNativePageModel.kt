package dev.hyperears.hook

import dev.hyperears.integration.*

/** Captured Xiaomi TWS plugin format. These flags describe UI, never protocol identity. */
internal object CoralNativePageModel {
    // Page identity survives control handoff/reconnect. Write eligibility is checked separately.
    fun eligible(state: EarbudState): Boolean = state.adapter?.id == RoseCoralAdapter.ID &&
        state.sessionActive

    fun support(address: String, state: EarbudState): String {
        val flags = CharArray(24) { '0' }
        if (state.noiseMode in RoseCoralCardPolicy.MODES) flags[16] = '1'
        return "$address,${flags.concatToString()}"
    }

    fun battery(state: EarbudState): String = listOf(state.battery.left, state.battery.right, state.battery.case)
        .joinToString(",") { value ->
            if (!value.available) "255" else ((value.percent ?: 255) or if (value.charging) 128 else 0).toString()
        }

    fun anc(state: EarbudState): String? = when (state.noiseMode) {
        NoiseMode.ANC -> "0100"
        NoiseMode.OFF -> "0000"
        else -> null
    }
}
