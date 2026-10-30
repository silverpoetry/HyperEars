package dev.hyperears.settings

import java.util.Locale

/** Platform-string persistence only; adapter instances and private capabilities are never stored. */
internal object ForcedAdapterSettings {
    private val addressPattern = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val adapterPattern = Regex("[a-z0-9][a-z0-9._-]*")

    fun address(value: String): String? = value.uppercase(Locale.ROOT).takeIf(addressPattern::matches)

    fun decode(entries: Set<String>): Map<String, String> = entries.mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator < 0) return@mapNotNull null
        val address = address(entry.substring(0, separator)) ?: return@mapNotNull null
        val adapter = entry.substring(separator + 1).takeIf(adapterPattern::matches) ?: return@mapNotNull null
        address to adapter
    }.groupBy({ it.first }, { it.second }).mapNotNull { (address, adapters) ->
        adapters.distinct().singleOrNull()?.let { address to it }
    }.toMap()

    fun encode(entries: Map<String, String>): Set<String> = entries.mapNotNull { (key, value) ->
        val address = address(key) ?: return@mapNotNull null
        value.takeIf(adapterPattern::matches)?.let { "$address=$it" }
    }.toSet()
}

internal fun ModuleSettings.withForcedAdapter(address: String, adapterId: String?): ModuleSettings {
    val key = ForcedAdapterSettings.address(address) ?: return this
    return copy(forcedAdapterIds = if (adapterId == null) forcedAdapterIds - key else {
        ForcedAdapterSettings.decode(ForcedAdapterSettings.encode(forcedAdapterIds + (key to adapterId)))
    })
}
