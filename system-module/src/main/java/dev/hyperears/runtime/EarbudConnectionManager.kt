package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import dev.hyperears.bridge.AppleNativeAdmissionContract
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.integration.PlatformReservedHeadsetPolicy
import dev.hyperears.integration.AppleAirPodsAdapter
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
    private val producerStartedAt = SystemClock.elapsedRealtime()
    private val physicalConnections = mutableMapOf<String, HeadsetConnection>()
    private val appleSdpRequested = mutableSetOf<String>()
    private val appleDecisionRevisions = mutableMapOf<String, Long>()
    private val appleNativeDecisions = mutableMapOf<String, Boolean>()
    private val appleRequestedConnections = mutableMapOf<String, AppleNativeAdmissionContract.Request>()
    private val presentationAdmissions = mutableMapOf<String, NativeAdmission>()

    fun onPhysicalConnected(address: String) = synchronized(lifecycleLock) {
        if (!closed) physicalConnections.getOrPut(normalizeAddress(address)) {
            HeadsetConnection(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
        }
    }

    /** Called only after authenticating the Xiaomi host sender. */
    fun acceptAppleNativeDecision(request: AppleNativeAdmissionContract.Request, accepted: Boolean?, revision: Long) {
        val observed: ObservedDevice?
        val removals: List<Removal>
        synchronized(lifecycleLock) {
            val key = normalizeAddress(request.address)
            if (closed || request.producer != presentationProducer ||
                physicalConnections[key] != request.connection || appleRequestedConnections[key] != request ||
                revision <= (appleDecisionRevisions[key] ?: 0)) return
            observed = observedDevices[key]?.takeIf { PlatformReservedHeadsetPolicy.isAppleCandidate(it.identity) }
                ?: return
            appleDecisionRevisions[key] = revision
            if (appleNativeDecisions.containsKey(key) && appleNativeDecisions[key] == accepted) return
            if (accepted == null) appleNativeDecisions.remove(key) else appleNativeDecisions[key] = accepted
            presentationAdmissions[key] = if (accepted == true) NativeAdmission.SYSTEM else NativeAdmission.UNKNOWN
            removals = if (accepted != false) removeLocked(key) else emptyList()
        }
        finishRemovals(removals)
        if (accepted == false) observeDevice(observed!!.device, observed.identity)
    }

    fun requestAppleNativeDecisions() {
        val removals = synchronized(lifecycleLock) {
            if (closed) return
            appleRequestedConnections.clear()
            appleNativeDecisions.clear()
            appleDecisionRevisions.clear()
            val removed = observedDevices.filterValues {
                PlatformReservedHeadsetPolicy.isAppleCandidate(it.identity)
            }.flatMap { (key, _) ->
                presentationAdmissions[key] = NativeAdmission.UNKNOWN
                removeLocked(key)
            }
            observedDevices.forEach { (key, observed) -> requestAppleDecisionLocked(key, observed.identity) }
            removed
        }
        finishRemovals(removals)
    }

    private fun requestAppleDecisionLocked(key: String, identity: EarbudIdentity) {
        if (!PlatformReservedHeadsetPolicy.isAppleCandidate(identity)) return
        val connection = physicalConnections[key] ?: return
        if (appleRequestedConnections[key]?.connection == connection) return
        val request = AppleNativeAdmissionContract.Request(key, connection, presentationProducer,
            producerStartedAt, UUID.randomUUID().toString())
        runCatching {
            BroadcastIdentity.send(appContext, AppleNativeAdmissionContract.request(request))
        }.onSuccess { appleRequestedConnections[key] = request }
            .onFailure { ModuleLog.warn(COMPONENT, "native admission request failed", it) }
    }

    /** SDP can refine a pending candidate, but cannot invent a physical connection. */
    fun refreshIdentity(device: BluetoothDevice) {
        val key = normalizeAddress(device.address)
        synchronized(lifecycleLock) { if (key !in physicalConnections || key !in observedDevices) return }
        observeDevice(device, device.toEarbudIdentity())
    }

    /** Only an authenticated MiLink receipt for this exact live session can grant presentation. */
    fun confirmNativeAdmission(address: String, token: String) {
        val next = synchronized(lifecycleLock) {
            val key = normalizeAddress(address)
            val record = sessions[key]?.takeIf { it.token == token } ?: return
            if (closed || modulePaused || key in systemOwnedAddresses || !record.state.sessionActive) return
            if (presentationAdmissions[key] == NativeAdmission.HYPEREARS) return
            presentationAdmissions[key] = NativeAdmission.HYPEREARS
            record.state = record.state.copy(revision = record.state.revision + 1)
            lastRevisions[key] = record.state.revision
            snapshotLocked(record.state, record.token).also { knownDevices[key] = it }
        }
        publish(next)
    }

    private fun snapshotLocked(state: EarbudState, token: String): Snapshot {
        val key = state.address?.let(::normalizeAddress)
        val connection = physicalConnections[key] ?: knownDevices[key]?.presentation?.connection
        return Snapshot(state, token, connection?.let {
            NativePresentationMetadata(it, presentationAdmissions[key] ?: NativeAdmission.UNKNOWN,
                connectionScopedOwnership = observedDevices[key]?.identity
                    ?.let(PlatformReservedHeadsetPolicy::isAppleCandidate) == true ||
                    knownDevices[key]?.presentation?.let { previous ->
                        previous.connection == it && previous.connectionScopedOwnership
                    } == true)
        })
    }

    private var activeControlAppPackages: Set<String> = emptySet()
    private var externalControlEnabled = true
    private var modulePaused = false
    private var disabledAdapterIds: Set<String> = emptySet()

    @Volatile
    private var closed = false

    fun snapshots(): List<Snapshot> = synchronized(lifecycleLock) {
        knownDevices.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun registerDevice(
        device: BluetoothDevice,
        identity: EarbudIdentity,
        earbudAdapter: EarbudAdapter,
    ): Boolean {
        val name = identity.deviceName
        val address = runCatching { device.address }.getOrNull() ?: return false
        val key = normalizeAddress(address)

        val registration = synchronized(lifecycleLock) {
            if (closed || modulePaused) return false
            retireAddressOnlyAppleClaimLocked(key, identity)
            if (key in systemOwnedAddresses) {
                ModuleLog.debug(
                    COMPONENT,
                    "ignored system-owned device ${maskBluetoothAddress(address)}",
                )
                return false
            }
            if (earbudAdapter is AppleAirPodsAdapter && !earbudAdapter.matches(identity)) return false
            observedDevices[key] = ObservedDevice(device, identity)
            requestAppleDecisionLocked(key, identity)
            val apple = PlatformReservedHeadsetPolicy.isAppleCandidate(identity) || earbudAdapter is AppleAirPodsAdapter
            if (PlatformReservedHeadsetPolicy.reserves(identity, appleNativeDecisions[key] == false) ||
                (apple && (physicalConnections[key] == null || appleNativeDecisions[key] != false))) return false
            if (earbudAdapter.id in disabledAdapterIds) return false
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
                    "registered ${earbudAdapter.id} at ${maskBluetoothAddress(address)}",
                )
                true
            }
        }
    }

    private fun retireAddressOnlyAppleClaimLocked(key: String, identity: EarbudIdentity) {
        if (PlatformReservedHeadsetPolicy.isAppleCandidate(identity) && systemOwnedAddresses.remove(key)) {
            presentationAdmissions[key] = if (appleNativeDecisions[key] == true) NativeAdmission.SYSTEM else NativeAdmission.UNKNOWN
        }
    }

    /** Records a connected device when its currently eligible adapter set is empty. */
    @SuppressLint("MissingPermission")
    fun observeDevice(device: BluetoothDevice, identity: EarbudIdentity): Boolean {
        val address = runCatching { device.address }.getOrNull() ?: return false
        val key = normalizeAddress(address)
        val adapter = synchronized(lifecycleLock) {
            if (closed) return false
            retireAddressOnlyAppleClaimLocked(key, identity)
            if (key in systemOwnedAddresses) return false
            if (modulePaused && !PlatformReservedHeadsetPolicy.isAppleCandidate(identity)) return false
            observedDevices[key] = ObservedDevice(device, identity)
            requestAppleDecisionLocked(key, identity)
            if (PlatformReservedHeadsetPolicy.isAppleCandidate(identity) &&
                identity.serviceUuids.none { uuid -> AppleAirPodsAdapter.AAP_SERVICE_UUIDS.any { it.equals(uuid, true) } } &&
                key in physicalConnections && key !in appleSdpRequested) {
                // One event-scoped SDP read per physical connection; its broadcast re-enters this gate.
                runCatching { device.fetchUuidsWithSdp() }
                    .onSuccess { if (it) appleSdpRequested.add(key) }
            }
            if (modulePaused) return false
            EarbudAdapterRegistry.forIntegration(identity, disabledAdapterIds, appleNativeDecisions[key] == false)
        } ?: return false
        return registerDevice(device, identity, adapter)
    }

    fun unregisterDevice(device: BluetoothDevice?): Boolean {
        val address = if (device == null) {
            null
        } else {
            runCatching { device.address }.getOrNull() ?: return false
        }
        val removals = synchronized(lifecycleLock) {
            val keys = if (address == null) physicalConnections.keys.toList() else listOf(normalizeAddress(address))
            keys.forEach { key ->
                appleRequestedConnections[key]?.let { request ->
                    runCatching { BroadcastIdentity.send(appContext,
                        AppleNativeAdmissionContract.request(request, cancel = true)) }
                        .onFailure { ModuleLog.warn(COMPONENT, "native admission cancellation failed", it) }
                }
                appleNativeDecisions.remove(key)
                appleDecisionRevisions.remove(key)
                appleSdpRequested.remove(key)
                appleRequestedConnections.remove(key)
            }
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
            // Address-only MiLink claims cannot decide Apple's connection-scoped transport owner.
            // The actual native connection observer supplies the authenticated epoch-bound decision.
            if (observedDevices[key]?.identity?.let(PlatformReservedHeadsetPolicy::isAppleCandidate) == true) return false
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
    fun updateDisabledAdapters(adapterIds: Set<String>) {
        val normalized = adapterIds.toSet()
        val changes = synchronized(lifecycleLock) {
            if (closed || disabledAdapterIds == normalized) return
            disabledAdapterIds = normalized
            if (modulePaused) return

            val decisions = observedDevices.values.mapNotNull { observed ->
                val key = normalizeAddress(observed.device.address)
                val record = sessions[key]
                record?.session?.updateDisabledAdapterIds(normalized)
                val replacement = EarbudAdapterRegistry.forIntegration(
                    identity = observed.identity,
                    disabledAdapterIds = normalized,
                    appleNativeDeclined = appleNativeDecisions[key] == false,
                )
                if (replacement?.id == record?.session?.adapter?.id) {
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
            change.replacement?.let { replacement ->
                registerDevice(change.device, change.identity, replacement)
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
        if (!paused) {
            val observed = synchronized(lifecycleLock) { observedDevices.values.toList() }
            observed.forEach { observeDevice(it.device, it.identity) }
        }
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
