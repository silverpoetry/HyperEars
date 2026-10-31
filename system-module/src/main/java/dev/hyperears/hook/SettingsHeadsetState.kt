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
    fun trustedRecord(address: String): Record? = records[address.uppercase(java.util.Locale.ROOT)]?.takeIf {
        !scopes.isSystemOwned(address) && it.producer.isBinderAlive && it.metadata.admission == NativeAdmission.HYPEREARS &&
            it.state.address.equals(address, true) && it.state.sessionActive
    }
    fun record(address: String): Record? = trustedRecord(address)?.takeIf { it.state.lifecycle.controlOwnership == ControlOwnership.MODULE }
    fun clear() { registry.clear(); scopes.clear(); records.clear() }
}

/** Only this Settings process's exact explicit lease can authorize an external-owner End. */
internal class SettingsControlGate {
    private data class Lease(val record: SettingsHeadsetState.Record, val app: String, val id: String, var released: Boolean = false)
    private val leases = linkedMapOf<String, Lease>()
    private fun key(address: String) = address.uppercase(java.util.Locale.ROOT)
    private fun matches(a: SettingsHeadsetState.Record, b: SettingsHeadsetState.Record) =
        a.token == b.token && a.producer == b.producer && a.metadata.connection == b.metadata.connection
    fun observe(address: String, record: SettingsHeadsetState.Record?) {
        val lease = leases[key(address)] ?: return
        if (record == null || !matches(lease.record, record)) { leases.remove(key(address)); return }
        if (record.state.lifecycle.controlOwnership == ControlOwnership.EXTERNAL_APP) {
            if (record.state.lifecycle.externalControlApp?.packageName == lease.app) lease.released = true
            else leases.remove(key(address))
        } else if (lease.released && record.state.revision > lease.record.state.revision) leases.remove(key(address))
    }
    fun canReturn(record: SettingsHeadsetState.Record): Boolean {
        val lease = leases[key(record.state.address ?: return false)] ?: return false
        return matches(lease.record, record) && record.state.lifecycle.controlOwnership == ControlOwnership.EXTERNAL_APP &&
            record.state.lifecycle.externalControlApp?.packageName == lease.app
    }
    fun send(record: SettingsHeadsetState.Record, request: ControlRequest, emit: () -> Unit): Boolean {
        val address = record.state.address ?: return false
        val key = key(address)
        val writable = record.state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
            record.state.privateChannelConnected && record.state.handshakeAccepted
        when (request) {
            is StandardControlRequest.BeginVendorHandoff -> {
                if (!writable || request.leaseId.isBlank() || request.leaseId.length > 128 ||
                    record.state.adapter?.controlApps?.none { it.packageName == request.packageName } != false) return false
                val old = leases[key]
                if (old != null && matches(old.record, record)) return false
                leases[key] = Lease(record, request.packageName, request.leaseId)
                return runCatching { emit(); while (leases.size > 32) leases.remove(leases.keys.first()); true }
                    .getOrElse { if (old == null) leases.remove(key) else leases[key] = old; false }
            }
            is StandardControlRequest.EndVendorHandoff -> {
                val lease = leases[key] ?: return false
                if (!matches(lease.record, record) || request.leaseId != lease.id ||
                    (record.state.lifecycle.controlOwnership == ControlOwnership.EXTERNAL_APP &&
                        record.state.lifecycle.externalControlApp?.packageName != lease.app)) return false
                return runCatching { emit(); leases.remove(key); true }.getOrDefault(false)
            }
            else -> if (!writable) return false
        }
        return runCatching { emit(); true }.getOrDefault(false)
    }
}
