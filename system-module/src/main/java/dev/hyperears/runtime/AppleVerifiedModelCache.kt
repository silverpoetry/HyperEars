package dev.hyperears.runtime

import android.content.Context
import androidx.core.content.edit
import dev.hyperears.integration.AppleDeviceInformationFeatureState
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.protocol.apple.AppleModelCatalog

/** Retains model identity only, never native ownership or feature support. */
internal class AppleVerifiedModelCache(context: Context) {
    private val prefs = context.getSharedPreferences("hyperears_apple_verified_models", Context.MODE_PRIVATE)
    fun type(address: String): String? = prefs.getString(address, null)?.takeIf { it.matches(Regex("[0-9A-F]{4}")) }
    fun forget(address: String) { prefs.edit { remove(address) } }
    fun observe(state: EarbudState) {
        val address = state.address ?: return
        if (state.lifecycle.systemProfile != SystemProfileState.CONNECTED || !state.lifecycle.protocolConfirmed) return
        val model = state.features.get<AppleDeviceInformationFeatureState>()?.catalogModel ?: return
        if (model.namespace != AppleModelCatalog.Namespace.APPLE_AACP || model.modelIndex !in 0..255) return
        // Apple's product index is the first model byte; Xiaomi's AIR_MODEL entries retain
        // the 0x20 family byte (for example A2084 -> 0E20 and A3063 -> 2720).
        val type = String.format(java.util.Locale.ROOT, "%02X20", model.modelIndex)
        if (type(address) != type) prefs.edit { putString(address, type) }
    }
}
