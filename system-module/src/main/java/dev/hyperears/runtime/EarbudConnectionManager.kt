package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import dev.hyperears.bridge.StateBroadcaster
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudAdapter
import dev.hyperears.integration.EarbudAdapterRegistry
import dev.hyperears.integration.EarbudIdentity
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.TransportReadiness
import dev.hyperears.presentation.HeadsetConnection
import dev.hyperears.presentation.NativeAdmission
import dev.hyperears.presentation.NativePresentationMetadata
import java.io.Closeable
import java.util.Locale
import java.util.UUID

/**
 * Address-keyed owner of device sessions and the states exposed to system consumers.
 *
 * The shape mirrors Xiaomi's connected-device manager: every registered device owns an
 * independent logical session, while physical channel connection attempts are serialized by
 * a shared coordinator.
 */
internal class EarbudConnectionManager(
    context: Context,
) : Closeable {
    data class Snapshot(
        val state: EarbudState,
        val sessionToken: String,
        val presentation: NativePresentationMetadata? = null,
    )

    private data class SessionRecord(
        val session: EarbudDeviceSession,
        val identity: EarbudIdentity,
        val token: String,
        val candidateAdapterId: String,
        var state: EarbudState,
    )

    /** Physical connection facts retained while an integration adapter is disabled. */
    private data class ObservedDevice(
        val device: BluetoothDevice,
        val identity: EarbudIdentity,
    )

    private sealed interface Registration {
        data class Existing(val record: SessionRecord) : Registration
        data class Created(val record: SessionRecord) : Registration
    }

    private data class Removal(
        val record: SessionRecord,
        val finalSnapshot: Snapshot,
    )

    private data class AdapterReconfiguration(
        val device: BluetoothDevice,
        val identity: EarbudIdentity,
        val replacement: EarbudAdapter?,
        val removal: Removal?,
    )

    private val appContext = context.applicationContext ?: context
    private val lifecycleLock = Any()
    private val sessions = linkedMapOf<String, SessionRecord>()
    private val observedDevices = linkedMapOf<String, ObservedDevice>()
    private val knownDevices = linkedMapOf<String, Snapshot>()
    private val lastRevisions = mutableMapOf<String, Long>()
    private val systemOwnedAddresses = mutableSetOf<String>()
    private val connectionCoordinator = ConnectionAttemptCoordinator()
    val presentationProducer = Binder()
    private val physicalConnections = mutableMapOf<String, HeadsetConnection>()
    private val presentationAdmissions = mutableMapOf<String, NativeAdmission>()

    fun onPhysicalConnected(address: String) = synchronized(lifecycleLock) {
        if (!closed) physicalConnections.getOrPut(normalizeAddress(address)) {
            HeadsetConnection(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
        }
    }

    /**
     * Only the native host's authenticated report for this exact live session can grant display. On a build
     * whose native ownership contract classes are absent, the HyperEars grant comes from the module's own
     * verified reading of that absence, so the remaining guards here are the system-owned address check and
     * the presentation host's native popup occupancy check.
     */
    fun confirmNativeAdmission(address: String, token: String, admission: NativeAdmission) {
        if (admission == NativeAdmission.UNKNOWN) return
        var removals = emptyList<Removal>()
        val next = synchronized(lifecycleLock) {
            val key = normalizeAddress(address)
            val record = sessions[key]?.takeIf { it.token == token } ?: return
            if (closed || modulePaused || key in systemOwnedAddresses || !record.state.sessionActive) return
            if (presentationAdmissions[key] == admission) return
            presentationAdmissions[key] = admission
            if (admission == NativeAdmission.SYSTEM) {
                systemOwnedAddresses += key
                observedDevices.remove(key)
                removals = removeLocked(address)
                return@synchronized null
            }
            record.state = record.state.copy(revision = record.state.revision + 1)
            lastRevisions[key] = record.state.revision
            snapshotLocked(record.state, record.token).also { knownDevices[key] = it }
        }
        ModuleLog.debug(COMPONENT) {
            "native host admission=$admission address=${maskBluetoothAddress(address)}"
        }
        finishRemovals(removals)
        next?.let(::publish)
    }

    private fun snapshotLocked(state: EarbudState, token: String): Snapshot {
        val key = state.address?.let(::normalizeAddress)
        val connection = physicalConnections[key] ?: knownDevices[key]?.presentation?.connection
        return Snapshot(state, token, connection?.let {
            NativePresentationMetadata(it, presentationAdmissions[key] ?: NativeAdmission.UNKNOWN)
        })
    }

    private var activeControlAppPackages: Set<String> = emptySet()
    private var externalControlEnabled = true
    private var modulePaused = false
    private var disabledAdapterIds: Set<String> = emptySet()
    private var forcedAdapterIds: Map<String, String> = emptyMap()

    @Volatile
    private var closed = false

    fun snapshots(): List<Snapshot> = synchronized(lifecycleLock) {
        knownDevices.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun registerDevice(
        device: BluetoothDevice,
        identity: EarbudIdentity,
    ): Boolean {
        val name = identity.deviceName
        val address = runCatching { device.address }.getOrNull() ?: return false
        val key = normalizeAddress(address)

        val registration = synchronized(lifecycleLock) {
            if (closed || modulePaused) return false
            if (key in systemOwnedAddresses) {
                ModuleLog.debug(
                    COMPONENT,
                    "ignored system-owned device ${maskBluetoothAddress(address)}",
                )
                return false
            }
            observedDevices[key] = ObservedDevice(device, identity)
            val earbudAdapter = EarbudAdapterRegistry.forIntegration(
                identity, disabledAdapterIds, forcedAdapterIds[key],
            ) ?: return false
            earbudAdapter.configureDisabledAdapterIds(disabledAdapterIds)
            sessions[key]?.let(Registration::Existing) ?: run {
                val initialState = knownDevices[key]
                    ?.state
                    ?.copy(
                        lifecycle = DeviceLifecycle(),
                        revision = lastRevisions[key] ?: 0,
                    )
                    ?: EarbudState(
                        revision = lastRevisions[key] ?: 0,
                    )
                val session = EarbudDeviceSession(
                    context = appContext,
                    device = device,
                    deviceName = name ?: earbudAdapter.displayName,
                    address = address,
                    initialAdapter = earbudAdapter,
                    connectionCoordinator = connectionCoordinator,
                    listener = ::onSessionEvent,
                    initialActiveControlApps = activeControlAppPackages,
                    initialExternalControlEnabled = externalControlEnabled,
                )
                val runtime = session.snapshot()
                val state = initialState.copy(
                    adapter = runtime.adapter,
                    deviceName = session.deviceName,
                    address = address,
                    lifecycle = runtime.lifecycle,
                    features = runtime.runtime.features,
                    revision = initialState.revision + 1,
                )
                val record = SessionRecord(
                    session = session,
                    identity = identity,
                    token = UUID.randomUUID().toString(),
                    candidateAdapterId = earbudAdapter.id,
                    state = state,
                )
                sessions[key] = record
                presentationAdmissions[key] = NativeAdmission.UNKNOWN
                lastRevisions[key] = state.revision
                knownDevices[key] = snapshotLocked(state, record.token)
                Registration.Created(record)
            }
        }

        return when (registration) {
            is Registration.Existing -> {
                registration.record.session.requestConnection()
                ModuleLog.debug(
                    COMPONENT,
                    "reconnect requested for ${maskBluetoothAddress(address)}",
                )
                true
            }

            is Registration.Created -> {
                publish(registration.record)
                registration.record.session.start()
                ModuleLog.debug(
                    COMPONENT,
                    "registered ${registration.record.candidateAdapterId} at ${maskBluetoothAddress(address)}",
                )
                true
            }
        }
    }

    fun unregisterDevice(device: BluetoothDevice?): Boolean {
        val address = if (device == null) {
            null
        } else {
            runCatching { device.address }.getOrNull() ?: return false
        }
        val removals = synchronized(lifecycleLock) {
            if (address == null) {
                observedDevices.clear()
            } else {
                observedDevices.remove(normalizeAddress(address))
            }
            removeLocked(address).also {
                if (address == null) {
                    physicalConnections.clear()
                    presentationAdmissions.clear()
                } else {
                    physicalConnections.remove(normalizeAddress(address))
                    presentationAdmissions.remove(normalizeAddress(address))
                }
            }
        }
        finishRemovals(removals)
        return removals.isNotEmpty()
    }

    /**
     * Permanently yields this process lifetime's address to HyperOS native headset support.
     *
     * The ownership claim is sticky across A2DP reconnects so a session cannot be recreated
     * after MiLink has already established that the platform owns the same device.
     */
    fun claimSystemOwnership(address: String): Boolean {
        val key = normalizeAddress(address)
        val result = synchronized(lifecycleLock) {
            if (closed) return false
            val newlyClaimed = systemOwnedAddresses.add(key)
            presentationAdmissions[key] = NativeAdmission.SYSTEM
            observedDevices.remove(key)
            newlyClaimed to removeLocked(address)
        }
        finishRemovals(result.second)
        if (result.first) {
            ModuleLog.debug(
                COMPONENT,
                "yielded to system ownership ${maskBluetoothAddress(address)}",
            )
        }
        return result.first
    }

    fun updateSystemBattery(device: BluetoothDevice, percent: Int?): Boolean {
        val address = runCatching { device.address }.getOrNull() ?: return false
        val session = synchronized(lifecycleLock) {
            sessions[normalizeAddress(address)]?.session
        } ?: return false
        session.onSystemBatteryChanged(percent)
        return true
    }

    /**
     * Applies the process-wide set of currently hooked vendor controller applications.
     *
     * The set is maintained by [ControlAppPresenceRegistry]; individual sessions decide whether
     * their adapter declares one of those packages and atomically yield or resume their channel.
     */
    fun updateControlAppPresence(activePackages: Set<String>) {
        val records = synchronized(lifecycleLock) {
            if (closed) return
            activeControlAppPackages = activePackages.toSet()
            sessions.values.toList()
        }
        records.forEach { record ->
            record.session.updateControlAppPresence(activePackages)
        }
    }

    /** Enables or disables voluntary handoff while retaining observed controller-process state. */
    fun updateExternalControlEnabled(enabled: Boolean) {
        val records = synchronized(lifecycleLock) {
            if (closed || externalControlEnabled == enabled) return
            externalControlEnabled = enabled
            sessions.values.toList()
        }
        records.forEach { record ->
            record.session.updateExternalControlEnabled(enabled)
        }
    }

    /** Re-resolves active devices against the same ordered Registry after a policy change. */
    fun updateAdapterSelection(adapterIds: Set<String>, forcedIds: Map<String, String>) {
        val normalized = adapterIds.toSet()
        val forced = forcedIds.mapKeys { normalizeAddress(it.key) }
        val changes = synchronized(lifecycleLock) {
            if (closed || (disabledAdapterIds == normalized && forcedAdapterIds == forced)) return
            disabledAdapterIds = normalized
            forcedAdapterIds = forced
            if (modulePaused) return

            val decisions = observedDevices.values.mapNotNull { observed ->
                val key = normalizeAddress(observed.device.address)
                val record = sessions[key]
                record?.session?.updateDisabledAdapterIds(normalized)
                val replacement = EarbudAdapterRegistry.forIntegration(
                    identity = observed.identity,
                    disabledAdapterIds = normalized,
                    forcedAdapterId = forced[key],
                )
                if ((record == null && replacement == null) ||
                    (record != null && replacement?.id == record.candidateAdapterId &&
                        record.session.adapter.id !in normalized)) {
                    null
                } else {
                    val removal = record?.let {
                        removeLocked(it.session.address).singleOrNull()
                    }
                    AdapterReconfiguration(
                        device = observed.device,
                        identity = observed.identity,
                        replacement = replacement,
                        removal = removal,
                    )
                }
            }
            decisions
        }

        finishRemovals(changes.mapNotNull(AdapterReconfiguration::removal))
        changes.forEach { change ->
            ModuleLog.debug(COMPONENT) {
                "adapter selection changed address=${maskBluetoothAddress(change.device.address)} " +
                    "forced=${forced[normalizeAddress(change.device.address)]} " +
                    "candidate=${change.replacement?.id}"
            }
            change.replacement?.let {
                // Re-read the policy under the registration lock; a newer setting may have
                // arrived while the retired session was closing.
                registerDevice(change.device, change.identity)
            }
        }
    }

    /** Stops all HyperEars device sessions while leaving Android's native Bluetooth profiles intact. */
    fun setModulePaused(paused: Boolean) {
        val removals = synchronized(lifecycleLock) {
            if (closed || modulePaused == paused) return
            modulePaused = paused
            if (paused) removeLocked(address = null) else emptyList()
        }
        finishRemovals(removals)
    }

    fun execute(
        request: ControlRequest,
        address: String,
        sessionToken: String,
    ): Boolean {
        val target = synchronized(lifecycleLock) {
            if (modulePaused) return false
            sessions[normalizeAddress(address)]?.takeIf {
                it.token == sessionToken &&
                    it.state.sessionActive &&
                    it.state.address.equals(address, ignoreCase = true)
            }?.let { it to it.state.connected }
        } ?: return false
        val (record, connected) = target

        if (!record.session.adapter.supportsControl(request)) {
            return false
        }
        if (request is StandardControlRequest.Refresh && !connected) {
            return record.session.requestConnection()
        }
        if (!connected) return false
        return record.session.execute(request)
    }

    override fun close() {
        val removals = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            observedDevices.clear()
            removeLocked(address = null)
        }
        finishRemovals(removals)
    }

    private fun onSessionEvent(
        session: EarbudDeviceSession,
        update: EarbudDeviceSession.Snapshot,
    ) {
        val snapshot = synchronized(lifecycleLock) {
            val key = normalizeAddress(session.address)
            val record = sessions[key]?.takeIf { it.session === session }
                ?: return
            val previous = record.state
            val projected = previous.copy(
                adapter = update.adapter,
                lifecycle = update.lifecycle,
                features = update.runtime.features,
            )
            if (projected == previous) return
            val next = projected.copy(revision = previous.revision + 1)
            record.state = next
            lastRevisions[key] = next.revision
            snapshotLocked(next, record.token).also {
                knownDevices[key] = it
            }
        }
        publish(snapshot)
    }

    private fun removeLocked(address: String?): List<Removal> {
        val keys = if (address == null) {
            sessions.keys.toList()
        } else {
            listOf(normalizeAddress(address))
        }
        return keys.mapNotNull { key ->
            val record = sessions.remove(key) ?: return@mapNotNull null
            val ended = record.state.copy(
                lifecycle = DeviceLifecycle(
                    systemProfile = SystemProfileState.DISCONNECTED,
                    privateTransport = if (record.session.adapter.privateProtocolRequired) {
                        PrivateTransportState.IDLE
                    } else {
                        PrivateTransportState.NOT_REQUIRED
                    },
                    protocolHandshake = if (
                        record.session.adapter.privateProtocolRequired &&
                        record.session.adapter.transportReadiness ==
                        TransportReadiness.PROTOCOL_HANDSHAKE
                    ) {
                        ProtocolHandshakeState.PENDING
                    } else {
                        ProtocolHandshakeState.NOT_REQUIRED
                    },
                ),
                revision = record.state.revision + 1,
            )
            record.state = ended
            lastRevisions[key] = ended.revision
            val finalSnapshot = snapshotLocked(ended, record.token)
            knownDevices[key] = finalSnapshot
            Removal(record, finalSnapshot)
        }
    }

    private fun finishRemovals(removals: List<Removal>) {
        removals.forEach { removal ->
            removal.record.session.close()
            publish(removal.finalSnapshot)
            ModuleLog.debug(
                COMPONENT,
                "unregistered ${maskBluetoothAddress(removal.record.session.address)}",
            )
        }
    }

    private fun publish(record: SessionRecord) {
        publish(synchronized(lifecycleLock) { snapshotLocked(record.state, record.token) })
    }

    private fun publish(snapshot: Snapshot) {
        StateBroadcaster.publish(
            appContext,
            snapshot.state,
            snapshot.sessionToken,
            snapshot.presentation,
            presentationProducer,
        )
        val state = snapshot.state
        val featureSummary = state.features.values.joinToString(
            prefix = "[",
            postfix = "]",
        ) { feature ->
            "${feature.featureId}=$feature"
        }
        ModuleLog.debug(
            COMPONENT,
            "state address=${maskBluetoothAddress(state.address)} rev=${state.revision} " +
                "active=${state.sessionActive} connected=${state.connected} " +
                "handshake=${state.handshakeAccepted} features=$featureSummary",
        )
    }

    private fun normalizeAddress(address: String): String =
        address.uppercase(Locale.ROOT)

    private companion object {
        const val COMPONENT = "ConnectManager"
    }
}
