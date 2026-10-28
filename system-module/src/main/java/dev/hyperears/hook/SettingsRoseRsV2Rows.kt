package dev.hyperears.hook

import dev.hyperears.integration.*

/** Exact source UI levels; dB values are device settings, not measured sound exposure. */
internal object RoseRsV2DailyLabels {
    val gain = listOf("档位 0", "档位 1", "档位 2")
    val hearingProtection = listOf("关闭", "75 dB", "80 dB", "85 dB", "90 dB", "95 dB", "100 dB")
}

internal object SettingsRoseRsV2Rows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        val feature = state.features.get<RoseRsV2SettingsFeatureState>() ?: return
        if (feature.profile?.gainAndProtection != true) return
        fun choice(key: String, title: String, selected: Int?, labels: List<String>, request: (Int) -> RoseRsV2ControlRequest) {
            if (selected !in labels.indices) return
            add(host.choice("hyperears.rsv2.$key", title, labels, labels.indices.map(Int::toString), selected.toString(),
                RoseRsV2SettingsPolicy.available(state, address)) { value -> runCatching {
                val latest = current() ?: return@runCatching
                val number = value.toIntOrNull()?.takeIf { it in labels.indices } ?: return@runCatching
                RoseRsV2SettingsPolicy.request(latest, address, request(number))?.let(send)
            } })
        }
        choice("gain", "增益", feature.gain, RoseRsV2DailyLabels.gain) { RoseRsV2ControlRequest.SetGain(feature.model, it) }
        choice("hearing", "听力保护", feature.hearingProtection, RoseRsV2DailyLabels.hearingProtection) {
            RoseRsV2ControlRequest.SetHearingProtection(feature.model, it)
        }
    }
}
