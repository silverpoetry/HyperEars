package dev.hyperears.hook

import android.content.Intent
import android.os.IBinder
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.NativeAdmission
import dev.hyperears.presentation.NativePresentationMetadata

/** Settings is a state consumer only. It cannot grant native admission or open a transport. */
internal class SettingsHeadsetState {
    data class Record(val state: EarbudState, val token: String, val metadata: NativePresentationMetadata, val producer: IBinder)
    private val registry = DeviceStateRegistry()
    private val scopes = MiLinkDeviceOwnershipRegistry()
    private val records = mutableMapOf<String, Record>()
    fun accept(intent: Intent): Boolean {
        val state = with(ModuleContract) { intent.readState() } ?: return false
        val address = state.address ?: return false
        val token = with(ModuleContract) { intent.readSessionToken() } ?: return false
        val metadata = NativePresentationContract.metadata(intent) ?: return false
        val producer = NativePresentationContract.producer(intent)?.takeIf { it.isBinderAlive } ?: return false
        val scope = MiLinkDeviceOwnershipRegistry.ConnectionScope(producer, metadata.connection)
        if (!scopes.acceptsConnectionScope(address, scope)) return false
        if (registry.accept(state, token) == null) return false
        scopes.bindConnectionScope(address, scope)
        if (metadata.admission == NativeAdmission.SYSTEM) scopes.claimSharedSystemOwnership(address, scope)
        records[address.uppercase(java.util.Locale.ROOT)] = Record(state, token, metadata, producer)
        return true
    }
    fun record(address: String): Record? = records[address.uppercase(java.util.Locale.ROOT)]?.takeIf {
        !scopes.isSystemOwned(address) && it.producer.isBinderAlive && it.metadata.admission == NativeAdmission.HYPEREARS &&
            it.state.address.equals(address, true) && it.state.sessionActive &&
            it.state.lifecycle.controlOwnership == ControlOwnership.MODULE
    }
    fun clear() { registry.clear(); scopes.clear(); records.clear() }
}
