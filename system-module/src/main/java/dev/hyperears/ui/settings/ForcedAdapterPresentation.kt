package dev.hyperears.ui.settings

import dev.hyperears.integration.EarbudAdapterGroup
import dev.hyperears.integration.EarbudState
import dev.hyperears.settings.ForcedAdapterSettings

internal data class ForcedAdapterDevice(val address: String, val name: String, val connected: Boolean)

internal fun forcedAdapterDevices(
    states: List<EarbudState>,
    selections: Map<String, String>,
): List<ForcedAdapterDevice> {
    val current = states.mapNotNull { state ->
        val address = state.address?.let(ForcedAdapterSettings::address) ?: return@mapNotNull null
        ForcedAdapterDevice(address, state.deviceName?.takeIf(String::isNotBlank) ?: address, state.sessionActive)
    }.associateBy(ForcedAdapterDevice::address)
    return (current.keys + selections.keys).map { address ->
        current[address] ?: ForcedAdapterDevice(address, address, false)
    }.sortedWith(compareByDescending<ForcedAdapterDevice> { it.connected }.thenBy { it.name })
}

internal fun filterProbeGroups(groups: List<EarbudAdapterGroup>, query: String): List<EarbudAdapterGroup> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return groups.mapNotNull { group ->
        val adapters = group.adapters.filter { adapter ->
            terms.all { term -> "${group.displayName} ${adapter.displayName} ${adapter.id}".contains(term, true) }
        }
        adapters.takeIf { it.isNotEmpty() }?.let { group.copy(adapters = it) }
    }
}
