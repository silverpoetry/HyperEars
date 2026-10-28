package dev.hyperears.hook

import dev.hyperears.integration.*

/** Apple-owned finite native choices; complex editors retain the existing details panel. */
internal object SettingsAppleRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        val feature = state.features.get<AppleSettingsFeatureState>() ?: return
        AppleSetting.entries.forEach { setting ->
            val selected = setting.value(feature) ?: return@forEach
            val enabled = AppleSettingsCardPolicy.available(state, address) && AppleSettingsCardPolicy.enabled(state, setting)
            fun change(value: Int) { runCatching {
                val latest = current() ?: return@runCatching
                AppleSettingsCardPolicy.request(latest, address, setting, value)?.let(send)
            } }
            val key = "hyperears.apple.${setting.name.lowercase(java.util.Locale.ROOT)}"
            add(if (setting.isToggle) host.toggle(key, setting.label, selected == 1, enabled) { change(if (it) 1 else 0) }
                else if (setting.choices.isEmpty()) host.range(key, setting.label, setting.range, selected, enabled, ::change)
                else host.choice(key, setting.label, setting.choices.ifEmpty { setting.range.map(Int::toString) }, setting.range.map(Int::toString), selected.toString(), enabled) { value -> value.toIntOrNull()?.let(::change) })
        }
    }
}
