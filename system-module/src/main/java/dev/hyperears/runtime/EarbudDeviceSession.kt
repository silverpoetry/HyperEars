package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlAppCatalog
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.BatteryFeatureState
import dev.hyperears.integration.AdapterActivation
import dev.hyperears.integration.AdapterEffect
import dev.hyperears.integration.AdapterIoResult
import dev.hyperears.integration.AdapterRuntimeState
import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.DeviceFeatureSnapshot
import dev.hyperears.integration.EarbudAdapter
import dev.hyperears.integration.HandshakeResult
import dev.hyperears.integration.InitialProtocolFailureResolution
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolEvent
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.TransportReadiness
import dev.hyperears.integration.standardIntegrationProjection
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One device-scoped private-protocol session.
 *
 * The object is created when the system profile connects and destroyed when that profile
 * disconnects. A channel loss only restarts the transport loop; it never creates a second
 * logical device session.
 */
internal class EarbudDeviceSession(
    private val context: Context,
    val device: BluetoothDevice,
    val deviceName: String,
    val address: String,
    initialAdapter: EarbudAdapter,
    private val connectionCoordinator: ConnectionAttemptCoordinator,
    private val listener: Listener,
    private val channelFactory: EarbudChannelFactory = AndroidEarbudChannelFactory,
    initialActiveControlApps: Set<String> = emptySet(),
    initialExternalControlEnabled: Boolean = true,
) : Closeable {
    fun interface Listener {
        fun onSnapshotChanged(session: EarbudDeviceSession, snapshot: Snapshot)
    }

    data class Snapshot(
        val adapter: AdapterSnapshot,
        val runtime: AdapterRuntimeState,
        val lifecycle: DeviceLifecycle,
    )

    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob + Dispatchers.IO)
    private val closed = AtomicBoolean()
    private val connectionJobLock = Any()
    private val transportLock = Any()
    private val transactionMutex = Mutex()
    private val unknownFrameLogTimes = mutableMapOf<Int, Long>()
    private val controlPacingLock = Any()
    private val refreshRequestGate = RefreshRequestGate(REFRESH_COALESCE_MS)
    private val stateRequests = StateRequestDispatcher(scope)
    private var lastControlAt = Long.MIN_VALUE

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var channel: EarbudChannel? = null

    @Volatile
    var adapter: EarbudAdapter = initialAdapter
        private set

    @Volatile
    private var activeControlAppPackages: Set<String> = initialActiveControlApps.toSet()

    @Volatile
    private var externalControlEnabled = initialExternalControlEnabled

    @Volatile
    private var externalControlApp: ControlAppSpec? =
        ControlAppCatalog.activeOwner(initialAdapter.controlApps, activeControlAppPackages)
            ?.takeIf { initialExternalControlEnabled }

    @Volatile
    private var systemBatteryPercent: Int? = null

    @Volatile
    private var privateProtocolEverConfirmed = false

    @Volatile
    private var lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED,
        privateTransport = if (externalControlApp != null) {
            PrivateTransportState.NOT_REQUIRED
        } else if (initialAdapter.privateProtocolRequired) {
            PrivateTransportState.IDLE
        } else {
            PrivateTransportState.NOT_REQUIRED
        },
        protocolHandshake = if (externalControlApp != null) {
            ProtocolHandshakeState.NOT_REQUIRED
        } else {
            initialAdapter.initialHandshakeState()
        },
        controlOwnership = if (externalControlApp != null) {
            ControlOwnership.EXTERNAL_APP
        } else {
            ControlOwnership.MODULE
        },
        externalControlApp = externalControlApp,
    )

    fun snapshot(): Snapshot {
        val currentAdapter = adapter
        val owner = externalControlApp
        return Snapshot(
            adapter = if (owner == null) {
                currentAdapter.snapshot()
            } else {
                currentAdapter.snapshot().standardIntegrationProjection()
            },
            runtime = if (owner == null) {
                currentAdapter.runtimeState()
            } else {
                AdapterRuntimeState(
                    features = DeviceFeatureSnapshot(
                        values = listOf(
                            BatteryFeatureState(
                                dev.hyperears.integration.EarbudBattery
                                    .fromSystemAggregate(systemBatteryPercent),
                            ),
                        ),
                    ),
                )
            },
            lifecycle = lifecycle,
        )
    }

    fun start() {
        publishCachedSystemBattery()
        if (externalControlApp != null) {
            publishSnapshot()
        } else if (adapter.privateProtocolRequired) {
            requestConnection()
        } else {
            publishSnapshot()
        }
    }

    fun onSystemBatteryChanged(percent: Int?) {
        if (closed.get()) return
        val normalized = percent?.takeIf { it in 0..100 }
        val aggregateChanged = systemBatteryPercent != normalized
        systemBatteryPercent = normalized
        if (externalControlApp != null) {
            if (aggregateChanged) publishSnapshot()
            return
        }
        if (adapter.effectiveBatterySource() != BatterySource.SYSTEM_AGGREGATE) return
        if (adapter.onSystemBatteryChanged(percent)) publishSnapshot()
    }

    /** Applies one process-wide control-app presence snapshot to this device session. */
    fun updateControlAppPresence(activePackages: Set<String>) {
        if (closed.get()) return
        activeControlAppPackages = activePackages.toSet()
        ModuleLog.debug(
            COMPONENT,
            "controller presence address=${maskBluetoothAddress(address)} active=$activeControlAppPackages " +
                "candidates=${adapter.controlApps.map(ControlAppSpec::packageName)}",
        )
        reconcileExternalControlOwner()
    }

    fun updateExternalControlEnabled(enabled: Boolean) {
        if (closed.get() || externalControlEnabled == enabled) return
        externalControlEnabled = enabled
        reconcileExternalControlOwner()
    }

    /** Updates policy used by family Adapters for protocol-identified replacements. */
    fun updateDisabledAdapterIds(adapterIds: Set<String>) {
        if (closed.get()) return
        adapter.configureDisabledAdapterIds(adapterIds)
    }

    private fun reconcileExternalControlOwner() {
        val nextOwner = if (externalControlEnabled) {
            ControlAppCatalog.activeOwner(adapter.controlApps, activeControlAppPackages)
        } else {
            null
        }
        val previousOwner = externalControlApp
        if (nextOwner == previousOwner) return
        if (nextOwner != null) {
            yieldToControlApp(nextOwner)
        } else {
            resumeModuleControl(previousOwner)
        }
    }

    /**
     * Starts one bounded connection cycle.
     *
     * Re-register and explicit refresh events may wake a dormant session, but duplicate
     * requests never create concurrent socket attempts for the same device.
     */
    fun requestConnection(): Boolean {
        if (closed.get()) {
            ModuleLog.debug(COMPONENT, "ignored connection request; session closed")
            return false
        }
        if (externalControlApp != null) {
            ModuleLog.debug(COMPONENT, "ignored connection request; external owner=${externalControlApp?.packageName}")
            return true
        }
        if (!adapter.privateProtocolRequired) {
            ModuleLog.debug(COMPONENT, "ignored connection request; adapter=${adapter.id} uses no private protocol")
            return true
        }
        var createdNewJob = false
        val job = synchronized(connectionJobLock) {
            if (closed.get()) return false
            if (channel != null || connectionJob?.isActive == true) return true
            scope.launch(start = CoroutineStart.LAZY) {
                runConnectionCycle()
            }.also { created ->
                createdNewJob = true
                connectionJob = created
                created.invokeOnCompletion {
                    synchronized(connectionJobLock) {
                        if (connectionJob === created) connectionJob = null
                    }
                }
            }
        }
        if (createdNewJob) {
            ModuleLog.debug(COMPONENT, "connection cycle requested address=${maskBluetoothAddress(address)} adapter=${adapter.id}")
            updateLifecycle(
                privateTransport = PrivateTransportState.CONNECTING,
                protocolHandshake = adapter.initialHandshakeState(),
            )
        }
        job.start()
        return true
    }

    fun execute(request: ControlRequest): Boolean {
        if (closed.get()) return false
        if (request === StandardControlRequest.Refresh && !refreshRequestGate.tryAcquire()) {
            ModuleLog.debug(
                COMPONENT,
                "coalesced duplicate state refresh address=${maskBluetoothAddress(address)}",
            )
            return true
        }
        if (externalControlApp != null) {
            if (request === StandardControlRequest.Refresh) publishSnapshot()
            return request === StandardControlRequest.Refresh
        }
        if (!adapter.privateProtocolRequired) {
            return request === StandardControlRequest.Refresh
        }
        if (!adapter.supportsControl(request)) {
            return false
        }
        val policy = adapter.controlPolicy(request)
        val activeChannel = channel ?: return false
        if (!isControlPacingReady(policy.cooldownMs)) return false
        scope.launch {
            runCatching {
                transactionMutex.withLock {
                    val result = adapter.executeControl(request)
                    if (!result.accepted) return@withLock
                    sendCommands(
                        activeChannel = activeChannel,
                        commands = result.commands,
                        gapMs = COMMAND_GAP_MS,
                        description = request.description(),
                    )
                    applyAdapterEffects(
                        activeChannel = activeChannel,
                        expectedAdapter = adapter,
                        effects = adapter.controlWritten(request),
                    )
                    if (result.stateChanged) publishSnapshot()
                    val readback = result.readback
                    if (readback.isNotEmpty()) {
                        delay(policy.readbackDelayMs)
                        sendCommands(
                            activeChannel = activeChannel,
                            commands = readback,
                            gapMs = COMMAND_GAP_MS,
                            description = "${request.description()} readback",
                        )
                    }
                }
            }.onFailure {
                if (it !is CancellationException) {
                    ModuleLog.warn(
                        COMPONENT,
                        "control write failed: ${request.javaClass.simpleName}",
                        it,
                    )
                    activeChannel.close()
                }
            }
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stateRequests.cancelAll("device session closed")
        val activeChannel = synchronized(transportLock) {
            channel.also {
                channel = null
                adapter.resetProtocolSession()
            }
        }
        activeChannel?.close()
        synchronized(connectionJobLock) {
            connectionJob.also { connectionJob = null }
        }?.cancel()
        scope.cancel()
        lifecycle = lifecycle.copy(
            systemProfile = SystemProfileState.DISCONNECTED,
            privateTransport = if (adapter.privateProtocolRequired) {
                PrivateTransportState.IDLE
            } else {
                PrivateTransportState.NOT_REQUIRED
            },
            protocolHandshake = if (adapter.privateProtocolRequired) {
                adapter.initialHandshakeState()
            } else {
                ProtocolHandshakeState.NOT_REQUIRED
            },
            controlOwnership = ControlOwnership.MODULE,
            externalControlApp = null,
        )
        ModuleLog.debug(COMPONENT, "closed ${maskBluetoothAddress(address)}")
    }

    @SuppressLint("MissingPermission")
    private suspend fun runConnectionCycle() {
        var consecutiveFailures = 0
        cancelDiscoveryOnce()

        while (
            currentCoroutineContext().isActive &&
            !closed.get() &&
            externalControlApp == null
        ) {
            var connectedAt = 0L
            try {
                val connectedTransport = connectFirstTransport()
                val activeChannel = connectedTransport.channel
                currentCoroutineContext().ensureActive()
                if (closed.get()) {
                    activeChannel.close()
                    return
                }

                synchronized(transportLock) {
                    channel = activeChannel
                }
                connectedAt = SystemClock.elapsedRealtime()
                ModuleLog.debug(
                    COMPONENT,
                    "channel connected endpoint=${activeChannel.endpointId} " +
                        "address=${maskBluetoothAddress(address)}",
                )
                updateLifecycle(
                    privateTransport = PrivateTransportState.CONNECTED,
                    protocolHandshake = adapter.readyHandshakeState(),
                )
                readFrames(activeChannel)
                throw IOException("vendor channel stream ended")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                clearTransport()
                if (
                    closed.get() ||
                    externalControlApp != null ||
                    !currentCoroutineContext().isActive
                ) return
                val stableConnection =
                    connectedAt != 0L &&
                        SystemClock.elapsedRealtime() - connectedAt >= STABLE_CONNECTION_MS
                if (stableConnection) consecutiveFailures = 0
                val waitMs = ChannelRecoveryPolicy.delayBeforeRetry(consecutiveFailures)
                if (waitMs == null) {
                    if (applyInitialProtocolFallback()) return
                    updateLifecycle(
                        privateTransport = PrivateTransportState.DORMANT,
                        protocolHandshake = adapter.initialHandshakeState(),
                    )
                    ModuleLog.warn(
                        COMPONENT,
                        "channel dormant after bounded recovery failures=$consecutiveFailures " +
                            "error=${error.javaClass.simpleName}:${error.message}",
                    )
                    return
                }
                consecutiveFailures += 1
                updateLifecycle(
                    privateTransport = PrivateTransportState.RECOVERING,
                    protocolHandshake = adapter.initialHandshakeState(),
                )
                ModuleLog.warn(
                    COMPONENT,
                    "channel unavailable failures=$consecutiveFailures retryIn=${waitMs}ms " +
                        "error=${error.javaClass.simpleName}:${error.message}",
                )
                delay(waitMs)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscoveryOnce() {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.takeIf { it.isDiscovering }
                ?.cancelDiscovery()
        }
    }

    private fun publishCachedSystemBattery() {
        val percent = BluetoothSystemBattery.cachedLevel(device)
        systemBatteryPercent = percent?.takeIf { it in 0..100 }
        if (externalControlApp == null &&
            adapter.effectiveBatterySource() == BatterySource.SYSTEM_AGGREGATE
        ) {
            adapter.onSystemBatteryChanged(percent)
        }
    }

    private suspend fun connectFirstTransport(): ConnectedTransport {
        return connectionCoordinator.run {
            connectFirstTransportSerially()
        }
    }

    private suspend fun connectFirstTransportSerially(): ConnectedTransport {
        var lastError: Throwable? = null
        adapter.transports.forEach { transport ->
            currentCoroutineContext().ensureActive()
            if (externalControlApp != null) {
                throw CancellationException("vendor control owned by external app")
            }
            val candidate = try {
                channelFactory.create(context, device, transport)
            } catch (error: Throwable) {
                lastError = error
                return@forEach
            }
            adapter.resetProtocolSession()
            synchronized(transportLock) { channel = candidate }
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { candidate.connect() }
                updateLifecycle(
                    privateTransport = PrivateTransportState.CONNECTED,
                    protocolHandshake = adapter.initialHandshakeState(),
                )
                val initial = adapter.beginHandshake()
                sendCommands(
                    activeChannel = candidate,
                    commands = initial.commands,
                    gapMs = INITIAL_COMMAND_GAP_MS,
                    description = "adapter handshake",
                )
                when (val handshake = initial.handshake) {
                    HandshakeResult.Ready, null -> return ConnectedTransport(candidate)
                    HandshakeResult.AwaitingEvidence -> awaitAcceptedHandshake(candidate)
                    is HandshakeResult.Replace -> applyReplacement(handshake, candidate)
                    HandshakeResult.Rejected -> throw IOException("adapter handshake rejected")
                }
                return ConnectedTransport(candidate)
            } catch (error: Throwable) {
                adapter.resetProtocolSession()
                candidate.close()
                synchronized(transportLock) {
                    if (channel === candidate) channel = null
                }
                if (error is CancellationException &&
                    (!currentCoroutineContext().isActive || closed.get())
                ) {
                    throw error
                }
                lastError = error
                ModuleLog.debug(
                    COMPONENT,
                    "transport ${transport.id} failed: " +
                        "${error.javaClass.simpleName}:" +
                        (error.message ?: error.cause?.message ?: "<no-message>"),
                )
            }
        }
        throw IOException("all vendor-channel endpoints failed", lastError)
    }

    private suspend fun awaitAcceptedHandshake(
        candidate: EarbudChannel,
    ) = candidate.withIoDeadline(
        timeoutMs = PROTOCOL_HANDSHAKE_TIMEOUT_MS,
        operation = "protocol handshake",
    ) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) throw IOException("vendor channel ended before protocol handshake")
            offerAdapterBytes(candidate, buffer.copyOf(count))
            if (lifecycle.protocolReady) return@withIoDeadline
        }
    }

    private suspend fun readFrames(
        activeChannel: EarbudChannel,
    ) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (currentCoroutineContext().isActive && !closed.get()) {
            val count = activeChannel.read(buffer)
            if (count < 0) return
            offerAdapterBytes(activeChannel, buffer.copyOf(count))
        }
    }

    private suspend fun offerAdapterBytes(
        activeChannel: EarbudChannel,
        bytes: ByteArray,
    ): AdapterIoResult = transactionMutex.withLock {
        ModuleLog.debug(COMPONENT) {
            "received endpoint=${activeChannel.endpointId} bytes=${bytes.toHex()}"
        }
        val receivingAdapter = adapter
        val result = receivingAdapter.receive(bytes)
        if (result.commands.isNotEmpty()) {
            sendCommands(
                activeChannel = activeChannel,
                commands = result.commands,
                gapMs = 0L,
                description = "adapter response",
            )
        }
        result.unknownFrames.forEach(::logUnknownFrame)
        var publishRequired = result.stateChanged
        when (val handshake = result.handshake) {
            HandshakeResult.Ready -> {
                markPrivateProtocolConfirmed()
                publishRequired = setLifecycle(
                    protocolHandshake = adapter.readyHandshakeState(),
                ) || publishRequired
            }

            HandshakeResult.Rejected -> {
                setLifecycle(protocolHandshake = ProtocolHandshakeState.REJECTED)
                publishSnapshot()
                throw IOException("protocol rejected active adapter")
            }

            is HandshakeResult.Replace -> {
                applyReplacement(handshake, activeChannel)
                privateProtocolEverConfirmed = true
                publishRequired = true
                when (handshake.activation) {
                    AdapterActivation.KEEP_CHANNEL_READY -> {
                        publishRequired = setLifecycle(
                            protocolHandshake = adapter.readyHandshakeState(),
                        ) || publishRequired
                    }

                    AdapterActivation.RESTART_ON_CURRENT_CHANNEL -> {
                        publishRequired = setLifecycle(
                            protocolHandshake = adapter.initialHandshakeState(),
                        ) || publishRequired
                        val restart = adapter.beginHandshake()
                        sendCommands(
                            activeChannel,
                            restart.commands,
                            INITIAL_COMMAND_GAP_MS,
                            "replacement handshake",
                        )
                        when (restart.handshake) {
                            HandshakeResult.Ready, null -> {
                                publishRequired = setLifecycle(
                                    protocolHandshake = adapter.readyHandshakeState(),
                                ) || publishRequired
                            }

                            HandshakeResult.AwaitingEvidence -> Unit
                            HandshakeResult.Rejected -> {
                                setLifecycle(protocolHandshake = ProtocolHandshakeState.REJECTED)
                                publishSnapshot()
                                throw IOException("replacement adapter handshake rejected")
                            }

                            is HandshakeResult.Replace ->
                                throw IOException("nested adapter replacement is not supported")
                        }
                    }

                    AdapterActivation.RECONNECT ->
                        throw IOException("adapter replacement requires reconnect")
                }
            }

            HandshakeResult.AwaitingEvidence, null -> Unit
        }
        if (adapter === receivingAdapter) {
            applyAdapterEffects(
                activeChannel = activeChannel,
                expectedAdapter = receivingAdapter,
                effects = result.effects,
            )
        }
        if (publishRequired) publishSnapshot()
        result
    }

    private fun applyReplacement(
        replacement: HandshakeResult.Replace,
        activeChannel: EarbudChannel,
    ) {
        if (channel !== activeChannel) throw CancellationException("stale adapter replacement")
        stateRequests.cancelAll("adapter replaced")
        adapter = replacement.adapter
        ControlAppCatalog.activeOwner(adapter.controlApps, activeControlAppPackages)
            ?.takeIf { externalControlEnabled }
            ?.let { owner ->
                yieldToControlApp(owner)
                throw CancellationException("replacement adapter yielded to external app")
            }
    }

    private fun markPrivateProtocolConfirmed() {
        if (adapter.transportReadiness == TransportReadiness.PROTOCOL_HANDSHAKE) {
            privateProtocolEverConfirmed = true
        }
    }

    /** Executes an Adapter-owned fallback without embedding vendor knowledge in the session. */
    private fun applyInitialProtocolFallback(): Boolean {
        if (privateProtocolEverConfirmed) return false
        val previous = adapter
        val fallback = when (val resolution = previous.resolveInitialProtocolFailure()) {
            InitialProtocolFailureResolution.KeepDormant -> return false
            is InitialProtocolFailureResolution.FallbackTo -> resolution.adapter
        }
        require(!fallback.privateProtocolRequired) {
            "Initial protocol fallback must not require another private transport"
        }
        fallback.adoptRuntimeState(previous.runtimeState())
        adapter = fallback
        publishCachedSystemBattery()
        updateLifecycle(
            privateTransport = PrivateTransportState.NOT_REQUIRED,
            protocolHandshake = ProtocolHandshakeState.NOT_REQUIRED,
        )
        ModuleLog.debug(
            COMPONENT,
            "adapter fallback ${previous.id} -> ${fallback.id} " +
                "address=${maskBluetoothAddress(address)}",
        )
        return true
    }

    private fun updateLifecycle(
        systemProfile: SystemProfileState = lifecycle.systemProfile,
        privateTransport: PrivateTransportState = lifecycle.privateTransport,
        protocolHandshake: ProtocolHandshakeState = lifecycle.protocolHandshake,
        controlOwnership: ControlOwnership = lifecycle.controlOwnership,
        externalControlApp: ControlAppSpec? = lifecycle.externalControlApp,
    ) {
        if (
            setLifecycle(
                systemProfile,
                privateTransport,
                protocolHandshake,
                controlOwnership,
                externalControlApp,
            )
        ) publishSnapshot()
    }

    private fun setLifecycle(
        systemProfile: SystemProfileState = lifecycle.systemProfile,
        privateTransport: PrivateTransportState = lifecycle.privateTransport,
        protocolHandshake: ProtocolHandshakeState = lifecycle.protocolHandshake,
        controlOwnership: ControlOwnership = lifecycle.controlOwnership,
        externalControlApp: ControlAppSpec? = lifecycle.externalControlApp,
    ): Boolean {
        val next = DeviceLifecycle(
            systemProfile = systemProfile,
            privateTransport = privateTransport,
            protocolHandshake = protocolHandshake,
            controlOwnership = controlOwnership,
            externalControlApp = externalControlApp,
        )
        if (next == lifecycle) return false
        lifecycle = next
        return true
    }

    private fun publishSnapshot() {
        listener.onSnapshotChanged(this, snapshot())
    }

    private suspend fun sendCommands(
        activeChannel: EarbudChannel,
        commands: List<ByteArray>,
        gapMs: Long,
        description: String,
    ) {
        commands.forEachIndexed { index, command ->
            currentCoroutineContext().ensureActive()
            if (closed.get() || channel !== activeChannel) {
                throw CancellationException("stale vendor-channel writer")
            }
            if (externalControlApp != null) {
                throw CancellationException("vendor control owned by external app")
            }
            activeChannel.write(command)
            ModuleLog.debug(
                COMPONENT,
                "$description wrote bytes=${command.toHex()}",
            )
            if (index != commands.lastIndex) delay(gapMs)
        }
    }

    /** Executes ordered Adapter effects while retaining all timer and transport ownership. */
    private fun applyAdapterEffects(
        activeChannel: EarbudChannel,
        expectedAdapter: EarbudAdapter,
        effects: List<AdapterEffect>,
    ) {
        effects.forEach { effect ->
            when (effect) {
                is AdapterEffect.RequestState -> {
                    stateRequests.request(
                        featureId = effect.featureId,
                        delayMs = effect.delayMs,
                        task = {
                            transactionMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                if (
                                    closed.get() ||
                                    externalControlApp != null ||
                                    channel !== activeChannel ||
                                    adapter !== expectedAdapter
                                ) {
                                    return@withLock
                                }
                                val commands = expectedAdapter.queryState(effect.featureId)
                                if (commands.isEmpty()) {
                                    ModuleLog.debug(
                                        COMPONENT,
                                        "state request produced no command feature=${effect.featureId}",
                                    )
                                    return@withLock
                                }
                                sendCommands(
                                    activeChannel = activeChannel,
                                    commands = commands,
                                    gapMs = COMMAND_GAP_MS,
                                    description = "state request ${effect.featureId}",
                                )
                            }
                        },
                        onFailure = { error ->
                            ModuleLog.warn(
                                COMPONENT,
                                "state request failed feature=${effect.featureId}",
                                error,
                            )
                            activeChannel.close()
                        },
                    )
                    ModuleLog.debug(
                        COMPONENT,
                        "state request scheduled feature=${effect.featureId} " +
                            "delay=${effect.delayMs}ms",
                    )
                }

                is AdapterEffect.CancelStateRequest -> {
                    stateRequests.cancel(
                        featureId = effect.featureId,
                        reason = "adapter accepted state report",
                    )
                }
            }
        }
    }

    private fun clearTransport() {
        stateRequests.cancelAll("vendor channel cleared")
        val oldChannel = synchronized(transportLock) {
            channel.also {
                channel = null
                adapter.resetProtocolSession()
                if (adapter.effectiveBatterySource() == BatterySource.SYSTEM_AGGREGATE) {
                    adapter.onSystemBatteryChanged(systemBatteryPercent)
                }
            }
        }
        oldChannel?.close()
    }

    private fun yieldToControlApp(owner: ControlAppSpec) {
        if (closed.get()) return
        externalControlApp = owner
        synchronized(connectionJobLock) {
            connectionJob.also { connectionJob = null }
        }?.cancel(CancellationException("vendor control yielded to ${owner.packageName}"))
        clearTransport()
        updateLifecycle(
            privateTransport = PrivateTransportState.NOT_REQUIRED,
            protocolHandshake = ProtocolHandshakeState.NOT_REQUIRED,
            controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = owner,
        )
        ModuleLog.debug(
            COMPONENT,
            "yielded vendor control to ${owner.packageName} " +
                "address=${maskBluetoothAddress(address)}",
        )
    }

    private fun resumeModuleControl(previousOwner: ControlAppSpec?) {
        if (closed.get() || externalControlApp == null) return
        externalControlApp = null
        updateLifecycle(
            privateTransport = if (adapter.privateProtocolRequired) {
                PrivateTransportState.IDLE
            } else {
                PrivateTransportState.NOT_REQUIRED
            },
            protocolHandshake = adapter.initialHandshakeState(),
            controlOwnership = ControlOwnership.MODULE,
            externalControlApp = null,
        )
        ModuleLog.debug(
            COMPONENT,
            "resuming vendor control after ${previousOwner?.packageName ?: "external app"} " +
                "address=${maskBluetoothAddress(address)}",
        )
        if (adapter.privateProtocolRequired) {
            requestConnection()
        } else {
            publishCachedSystemBattery()
            publishSnapshot()
        }
    }

    private fun logUnknownFrame(event: ProtocolEvent.UnknownFrame) {
        val key = (event.vendor shl 16) or event.command
        val now = SystemClock.elapsedRealtime()
        val last = unknownFrameLogTimes[key] ?: Long.MIN_VALUE
        if (now - last < UNKNOWN_FRAME_LOG_INTERVAL_MS) return
        unknownFrameLogTimes[key] = now
        ModuleLog.debug(
            COMPONENT,
            "unmapped frame vendor=%04X command=%04X bytes=%d".format(
                event.vendor,
                event.command,
                event.payloadSize,
            ),
        )
    }

    private fun ControlRequest.description(): String =
        javaClass.simpleName.ifBlank { "ControlRequest" }

    private fun isControlPacingReady(cooldownMs: Long): Boolean {
        if (cooldownMs <= 0L) return true
        val now = SystemClock.elapsedRealtime()
        return synchronized(controlPacingLock) {
            if (lastControlAt != Long.MIN_VALUE && now - lastControlAt < cooldownMs) {
                false
            } else {
                lastControlAt = now
                true
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }

    private fun EarbudAdapter.initialHandshakeState(): ProtocolHandshakeState =
        if (privateProtocolRequired && transportReadiness == TransportReadiness.PROTOCOL_HANDSHAKE) {
            ProtocolHandshakeState.PENDING
        } else {
            ProtocolHandshakeState.NOT_REQUIRED
        }

    private fun EarbudAdapter.readyHandshakeState(): ProtocolHandshakeState =
        if (privateProtocolRequired && transportReadiness == TransportReadiness.PROTOCOL_HANDSHAKE) {
            ProtocolHandshakeState.CONFIRMED
        } else {
            ProtocolHandshakeState.NOT_REQUIRED
        }

    private companion object {
        data class ConnectedTransport(
            val channel: EarbudChannel,
        )

        const val COMPONENT = "DeviceSession"
        const val CONNECT_TIMEOUT_MS = 60_000L
        // Local test build: WH-1000XM4 pushes its startup notifications before the init
        // reply, which can exceed the stock 2.5s budget. 6s keeps failure latency bounded
        // while giving slower headsets room to answer.
        const val PROTOCOL_HANDSHAKE_TIMEOUT_MS = 6_000L
        const val INITIAL_COMMAND_GAP_MS = 150L
        const val COMMAND_GAP_MS = 120L
        const val STABLE_CONNECTION_MS = 30_000L
        const val UNKNOWN_FRAME_LOG_INTERVAL_MS = 5 * 60_000L
        const val REFRESH_COALESCE_MS = 1_500L
        const val READ_BUFFER_SIZE = 1_024
    }
}
