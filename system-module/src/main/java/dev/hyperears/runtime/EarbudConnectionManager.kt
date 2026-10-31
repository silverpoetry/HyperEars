package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import dev.hyperears.bridge.StateBroadcaster
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.ControlOwnership
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
    private val sonyPresence = SonyLeAudioPresence()
    private val sonyProfileEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int,String>>()
    private val sonySnapshotStarted = java.util.concurrent.atomic.AtomicBoolean()
    private val sonyRouteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sonyLeOperations = mutableMapOf<String,Pair<String,dev.hyperears.integration.SonyLeAudioOperation>>()
    private val sonyLeGate = lazy { SonyLeAudioProfileGate.create(appContext) }
    private val sonyLeController = lazy {
        SonyLeAudioController(
            current = { address, token -> synchronized(lifecycleLock) {
                sessions[normalizeAddress(address)]?.takeIf {
                    !closed && !modulePaused && it.token == token && it.state.sessionActive &&
                    it.state.connected && it.state.privateChannelConnected && it.state.handshakeAccepted &&
                    it.state.lifecycle.controlOwnership == ControlOwnership.MODULE
                }?.state?.features?.get<dev.hyperears.integration.SonyLeAudioFeatureState>()
            } },
            send = ::executeSonyLeDirect,
            profile = { callback -> sonyLeGate.value.request(callback) },
            bonded = ::pairedSonyAddresses,
            progress = ::publishSonyLeProgress,
            completed = { result -> ModuleLog.debug(COMPONENT,
                "Sony LE Audio confirmed=${result.confirmed} pairingRequired=${result.pairingRequired}: ${result.reason}") },
        )
    }

    // Runs in the Bluetooth host, not the module APK; check the actual host grant below.
    @SuppressLint("MissingPermission")
    private fun pairedSonyAddresses(): Set<String> {
        if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return emptySet()
        return try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            manager?.adapter?.bondedDevices.orEmpty().map { it.address }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

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

    /** Only the native host's authenticated report for this exact live session can grant display. */
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
        val operation = sonyLeOperations[key]?.takeIf { it.first == token }?.second
        val projected = if (operation == null) state else state.copy(features = state.features.update(operation))
        return Snapshot(projected, token, connection?.let {
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

    private fun publishSonyLeProgress(address:String,token:String,operation:dev.hyperears.integration.SonyLeAudioOperation) {
        val next = synchronized(lifecycleLock) {
            if (closed) return
            val key=normalizeAddress(address)
            val current=knownDevices[key]?.takeIf {it.sessionToken==token}?:return
            sonyLeOperations[key]=token to operation
            val state=current.state.copy(revision=current.state.revision+1)
            sessions[key]?.takeIf {it.token==token}?.let {it.state=it.state.copy(revision=state.revision)}
            lastRevisions[key]=state.revision
            snapshotLocked(state,token).also {knownDevices[key]=it}
        }
        publish(next)
    }

    fun snapshotSonyAudioProfiles() {
        if (!sonySnapshotStarted.compareAndSet(false,true)) return
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager ?: return
        val bluetooth = manager.adapter ?: return
        for (requested in listOf(android.bluetooth.BluetoothProfile.A2DP,android.bluetooth.BluetoothProfile.LE_AUDIO)) {
            runCatching { bluetooth.getProfileProxy(appContext,object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    sonyRouteHandler.post {
                        try {
                            if (synchronized(lifecycleLock) {closed}) return@post
                            if (profile != requested) return@post
                            runCatching { proxy.connectedDevices }.getOrDefault(emptyList()).forEach { device ->
                                if ((profile to normalizeAddress(device.address)) in sonyProfileEvents) return@forEach
                                if (profile == android.bluetooth.BluetoothProfile.LE_AUDIO) {
                                    onSonyLeState(device,android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                                } else if (EarbudAdapterRegistry.resolve(device.toEarbudIdentity())?.id == "sony-wh-1000xm6") {
                                    val owned = onSonyA2dpState(device,android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                                    if (!owned) {
                                        onPhysicalConnected(device.address)
                                        registerDevice(device,device.toEarbudIdentity())
                                    }
                                }
                            }
                        } finally { runCatching { bluetooth.closeProfileProxy(profile,proxy) } }
                    }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            },requested) }
        }
    }

    fun onSonyA2dpState(device: BluetoothDevice, state: Int): Boolean {
        val address = device.address
        sonyProfileEvents.add(android.bluetooth.BluetoothProfile.A2DP to normalizeAddress(address))
        val classic = sonyPresence.classicFor(address)
        val sony = classic != null || EarbudAdapterRegistry.resolve(device.toEarbudIdentity())?.id == "sony-wh-1000xm6"
        if (!sony) return false
        if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTING) return true
        if (state !in setOf(android.bluetooth.BluetoothProfile.STATE_CONNECTED, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)) return false
        sonyPresence.a2dp(address, state == android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        if (classic == null && sonyPresence.hasLeAudio(address)) return true
        if (classic != null && sonyPresence.route(classic)?.preferGatt == true) {
            sonyRouteHandler.post { reconcileSonyRoute(classic) }
            return true
        }
        return false
    }

    fun onSonyLeState(device: BluetoothDevice, state: Int) {
        if (state !in setOf(android.bluetooth.BluetoothProfile.STATE_CONNECTED, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)) return
        val connected = state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        val address = device.address
        sonyProfileEvents.add(android.bluetooth.BluetoothProfile.LE_AUDIO to normalizeAddress(address))
        sonyPresence.leAudio(address, connected)
        val classic = sonyPresence.classicFor(address)
        if (classic != null) {
            sonyRouteHandler.post { reconcileSonyRoute(classic) }
        } else if (connected && dev.hyperears.bridge.BluetoothHostIdentity.isPaired(device)) {
            val identity = device.toEarbudIdentity()
            val candidate = EarbudAdapterRegistry.resolve(identity)
            if (candidate?.id == "sony-wh-1000xm6") registerDevice(device,identity,candidate,preferGatt=true)
        } else if (!connected) {
            val provisional = synchronized(lifecycleLock) {sessions[normalizeAddress(address)]?.takeIf {
                it.session.preferGatt && it.session.adapter.id == "sony-wh-1000xm6"
            } != null}
            if (provisional && !sonyPresence.hasA2dp(address)) unregisterDevice(device)
        }
    }

    fun forgetSonyBond(device: BluetoothDevice) {
        val address = normalizeAddress(device.address)
        val classic = sonyPresence.classicFor(address) ?: return
        val oldRoute = sonyPresence.route(classic)
        val hadA2dp = sonyPresence.hasA2dp(classic)
        val removals = synchronized(lifecycleLock) {
            sessions.filter { (_,record) -> record.session.adapter.id == "sony-wh-1000xm6" &&
                (record.session.address.equals(address,true) || record.session.controlDevice.address.equals(address,true))
            }.keys.toList().flatMap(::removeLocked)
        }
        sonyPresence.forget(address)
        finishRemovals(removals)
        if (removals.isEmpty()) return
        val bluetooth = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter ?: return
        val fallback = when {
            address != classic && hadA2dp -> SonyLeAudioPresence.Route(classic,classic,false)
            oldRoute?.preferGatt == true && !oldRoute.controlAddress.equals(address,true) ->
                SonyLeAudioPresence.Route(oldRoute.controlAddress,oldRoute.controlAddress,true)
            else -> null
        } ?: return
        val peer = bluetooth.getRemoteDevice(fallback.controlAddress)
        if (!dev.hyperears.bridge.BluetoothHostIdentity.isPaired(peer)) return
        if (fallback.preferGatt) sonyPresence.leAudio(peer.address,true) else sonyPresence.a2dp(peer.address,true)
        val candidate = EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)) ?: return
        registerDevice(peer,peer.toEarbudIdentity(),candidate,peer,fallback.preferGatt,removals.map {it.record.session.closedCompletion})
    }

    private fun reconcileSonyRoute(classic: String) {
        if (synchronized(lifecycleLock) {closed || modulePaused}) return
        val route = sonyPresence.route(classic)
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager ?: return
        val bluetooth = manager.adapter ?: return
        val logical = bluetooth.getRemoteDevice(classic)
        // A first-ever LE-only pairing may have no paired Classic item: retain its provisional page.
        if (route != null && !dev.hyperears.bridge.BluetoothHostIdentity.isPaired(logical)) return
        val removals = synchronized(lifecycleLock) {
            val group = sessions.filter { (address,record) -> record.session.adapter.id == "sony-wh-1000xm6" && sonyPresence.classicFor(address) == classic }
            if (route != null && group.size == 1 && group[classic]?.session?.let {
                    it.controlDevice.address.equals(route.controlAddress,true) && it.preferGatt == route.preferGatt
                } == true) return
            group.keys.flatMap(::removeLocked)
        }
        finishRemovals(removals)
        if (route == null || sonyPresence.route(classic) != route) return
        val peer = bluetooth.getRemoteDevice(route.controlAddress)
        if (!dev.hyperears.bridge.BluetoothHostIdentity.isPaired(peer)) return
        // This candidate is justified by the already validated XM6 identity report, not an LE name.
        val candidate = EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)) ?: return
        registerDevice(logical,logical.toEarbudIdentity(),candidate,peer,route.preferGatt,
            removals.map {it.record.session.closedCompletion})
    }

    private fun executeSonyLeDirect(request: ControlRequest, address: String, token: String): Boolean {
        if (request !is dev.hyperears.integration.SonyControlRequest.SetLeAudio &&
            request !is dev.hyperears.integration.SonyControlRequest.ReadLeAudioHistory) return false
        val session = synchronized(lifecycleLock) {
            sessions[normalizeAddress(address)]?.takeIf {
                !closed && !modulePaused && it.token == token && it.state.sessionActive && it.state.connected &&
                    it.state.privateChannelConnected && it.state.handshakeAccepted &&
                    it.state.lifecycle.controlOwnership == ControlOwnership.MODULE
            }?.session
        } ?: return false
        if (!session.adapter.supportsControl(request)) return false
        return session.execute(request)
    }

    fun snapshots(): List<Snapshot> = synchronized(lifecycleLock) {
        knownDevices.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun registerDevice(
        device: BluetoothDevice,
        identity: EarbudIdentity,
        candidate: EarbudAdapter? = null,
        controlDevice: BluetoothDevice = device,
        preferGatt: Boolean = false,
        previousRouteClosures: List<kotlinx.coroutines.Deferred<Unit>> = emptyList(),
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
            if (identity.nativeSystemEarbud || dev.hyperears.integration.PlatformReservedHeadsetPolicy.reserves(identity)) return false
            val earbudAdapter = if (candidate == null || forcedAdapterIds[key] != null)
                EarbudAdapterRegistry.forIntegration(identity, disabledAdapterIds, forcedAdapterIds[key]) ?: return false
            else candidate.takeUnless { it.id in disabledAdapterIds } ?: return false
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
                    controlDevice = controlDevice,
                    preferGatt = preferGatt,
                    previousRouteClosures = previousRouteClosures,
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
        if (request is dev.hyperears.integration.SonyControlRequest.SetLeAudio)
            return sonyLeController.value.begin(address, sessionToken, request)
        val accepted = record.session.execute(request)
        if (accepted && request is dev.hyperears.integration.SonyControlRequest.ReplyLeAudioAlert && !request.confirm && sonyLeController.isInitialized())
            sonyLeController.value.cancel(address, sessionToken)
        return accepted
    }

    override fun close() {
        sonyPresence.clear()
        sonyProfileEvents.clear()
        if (sonyLeController.isInitialized()) sonyLeController.value.close()
        if (sonyLeGate.isInitialized()) sonyLeGate.value.close()
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
            if (projected == previous && !update.forceRevision) return
            val next = projected.copy(revision = previous.revision + 1)
            record.state = next
            lastRevisions[key] = next.revision
            snapshotLocked(next, record.token).also {
                knownDevices[key] = it
            }
        }
        publish(snapshot)
        if (snapshot.state.adapter?.id == "sony-wh-1000xm6" && snapshot.state.handshakeAccepted) {
            val identity = snapshot.state.features.get<dev.hyperears.integration.SonyLeAudioFeatureState>()?.identity
            val source = runCatching { session.controlDevice.address }.getOrNull()
            if (identity != null && source != null && sonyPresence.learn(source,identity) && sonyPresence.route(identity.classic) != null)
                sonyRouteHandler.post { reconcileSonyRoute(identity.classic.uppercase(java.util.Locale.ROOT)) }
        }
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
        if (sonyLeController.isInitialized()) snapshot.state.address?.let {
            sonyLeController.value.observe(it, snapshot.sessionToken, snapshot.state.features.get<dev.hyperears.integration.SonyLeAudioFeatureState>())
        }

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
