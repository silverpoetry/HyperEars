package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.util.size
import dev.hyperears.integration.EarbudTransportSpec
import dev.hyperears.integration.GattPeerIdentity
import dev.hyperears.integration.GattPeerSelection
import dev.hyperears.integration.GattScanFilterSpec
import dev.hyperears.integration.GattTransportSpec
import dev.hyperears.integration.GattWriteMode
import dev.hyperears.integration.ProtocolPacket
import dev.hyperears.integration.L2capEndpointSpec
import dev.hyperears.integration.RfcommEndpointSpec
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * A connected vendor-control byte channel.
 *
 * Sessions and protocols do not know whether bytes travel over RFCOMM or BLE GATT. GATT
 * notifications are exposed as the same ordered byte stream consumed by protocol decoders.
 */
internal interface EarbudChannel : Closeable {
    val endpointId: String
    val connectionDetails: String? get() = null

    suspend fun connect()

    suspend fun read(buffer: ByteArray): ProtocolPacket?

    suspend fun write(bytes: ByteArray, route: String? = null)
}

/** Only an actual characteristic-write callback can produce this failure. */
internal class GattWriteException(val status: Int, val route: String?) :
    IOException("GATT write status=$status route=${route ?: "primary"}")

internal fun interface EarbudChannelFactory {
    fun create(
        context: Context,
        device: BluetoothDevice,
        transport: EarbudTransportSpec,
    ): EarbudChannel
}

internal class EarbudChannelDeadlineException(
    endpointId: String,
    operation: String,
    timeoutMs: Long,
    cause: Throwable? = null,
) : IOException("$operation timed out after ${timeoutMs}ms on $endpointId", cause)

/**
 * Enforces a real deadline for blocking Bluetooth I/O.
 *
 * Coroutine cancellation alone does not reliably release every Android BluetoothSocket read.
 * The deadline therefore owns the channel and closes it when it wins the completion race.
 */
internal suspend fun <T> EarbudChannel.withIoDeadline(
    timeoutMs: Long,
    operation: String,
    block: suspend EarbudChannel.() -> T,
): T = coroutineScope {
    require(timeoutMs > 0L)
    val outcome = AtomicInteger(IO_ACTIVE)
    val deadline = launch(Dispatchers.IO) {
        delay(timeoutMs)
        if (outcome.compareAndSet(IO_ACTIVE, IO_TIMED_OUT)) {
            close()
        }
    }
    try {
        val result = block()
        if (!outcome.compareAndSet(IO_ACTIVE, IO_COMPLETED)) {
            throw EarbudChannelDeadlineException(endpointId, operation, timeoutMs)
        }
        result
    } catch (error: Throwable) {
        if (outcome.compareAndSet(IO_ACTIVE, IO_COMPLETED)) throw error
        if (error is CancellationException) throw error
        if (error is EarbudChannelDeadlineException) throw error
        throw EarbudChannelDeadlineException(endpointId, operation, timeoutMs, error)
    } finally {
        deadline.cancel()
    }
}

private const val IO_ACTIVE = 0
private const val IO_COMPLETED = 1
private const val IO_TIMED_OUT = 2

internal object AndroidEarbudChannelFactory : EarbudChannelFactory {
    override fun create(
        context: Context,
        device: BluetoothDevice,
        transport: EarbudTransportSpec,
    ): EarbudChannel = when (transport) {
        is RfcommEndpointSpec -> AndroidBluetoothSocketChannel(
            socket = createSocket(device, transport),
            endpointId = transport.id,
        )

        is L2capEndpointSpec -> AndroidBluetoothSocketChannel(
            socket = createL2capSocket(context, device, transport),
            endpointId = transport.id,
        )

        is GattTransportSpec -> AndroidGattChannel(
            context = context,
            sessionDevice = device,
            spec = transport,
        )
    }

    private fun createSocket(
        device: BluetoothDevice,
        endpoint: RfcommEndpointSpec,
    ): BluetoothSocket = when (endpoint) {
        is RfcommEndpointSpec.ServiceUuid ->
            device.createRfcommSocketToServiceRecord(UUID.fromString(endpoint.uuid))

        is RfcommEndpointSpec.Channel -> {
            val methodName = if (endpoint.secure) {
                "createRfcommSocket"
            } else {
                "createInsecureRfcommSocket"
            }
            device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
                .invoke(device, endpoint.number) as BluetoothSocket
        }
    }

    /**
     * Creates the hidden BR/EDR L2CAP socket used by Apple's AAP service.
     *
     * The hidden constructor changed several times: older releases used a type-first signature
     * with an explicit file descriptor, later releases moved [BluetoothDevice] first, and Android
     * 16 QPR3 additionally passes [BluetoothAdapter]. Keep the known AOSP signatures explicit and
     * fail closed when none exists; no constructor scanning or obfuscated ROM class is involved.
     */
    private fun createL2capSocket(
        context: Context,
        device: BluetoothDevice,
        endpoint: L2capEndpointSpec,
    ): BluetoothSocket {
        val uuid = ParcelUuid.fromString(endpoint.serviceUuid)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val candidates = listOf(
            socketConstructorCandidate(
                adapter,
                device,
                BLUETOOTH_SOCKET_TYPE_L2CAP,
                endpoint.authenticated,
                endpoint.encrypted,
                endpoint.psm,
                uuid,
            ),
            socketConstructorCandidate(
                device,
                BLUETOOTH_SOCKET_TYPE_L2CAP,
                endpoint.authenticated,
                endpoint.encrypted,
                endpoint.psm,
                uuid,
            ),
            socketConstructorCandidate(
                device,
                BLUETOOTH_SOCKET_TYPE_L2CAP,
                INVALID_FILE_DESCRIPTOR,
                endpoint.authenticated,
                endpoint.encrypted,
                endpoint.psm,
                uuid,
            ),
            socketConstructorCandidate(
                BLUETOOTH_SOCKET_TYPE_L2CAP,
                INVALID_FILE_DESCRIPTOR,
                endpoint.authenticated,
                endpoint.encrypted,
                device,
                endpoint.psm,
                uuid,
            ),
            socketConstructorCandidate(
                BLUETOOTH_SOCKET_TYPE_L2CAP,
                endpoint.authenticated,
                endpoint.encrypted,
                device,
                endpoint.psm,
                uuid,
            ),
        )

        candidates.forEach { candidate ->
            val constructor = try {
                BluetoothSocket::class.java.getDeclaredConstructor(*candidate.parameterTypes)
            } catch (_: NoSuchMethodException) {
                return@forEach
            }
            try {
                return constructor
                    .apply { isAccessible = true }
                    .newInstance(*candidate.arguments) as BluetoothSocket
            } catch (error: InvocationTargetException) {
                throw error.cause ?: error
            }
        }
        throw NoSuchMethodException("No supported BluetoothSocket L2CAP constructor")
    }

    private class SocketConstructorCandidate(val arguments: Array<out Any>) {
        val parameterTypes: Array<Class<*>> = arguments
            .map { argument ->
                when (argument) {
                    is Int -> INT_TYPE
                    is Boolean -> BOOLEAN_TYPE
                    is BluetoothAdapter -> BluetoothAdapter::class.java
                    is BluetoothDevice -> BluetoothDevice::class.java
                    is ParcelUuid -> ParcelUuid::class.java
                    else -> error("Unsupported BluetoothSocket argument: ${argument.javaClass.name}")
                }
            }
            .toTypedArray()
    }

    private fun socketConstructorCandidate(vararg arguments: Any): SocketConstructorCandidate =
        SocketConstructorCandidate(arguments)

    private val INT_TYPE = requireNotNull(Int::class.javaPrimitiveType)
    private val BOOLEAN_TYPE = requireNotNull(Boolean::class.javaPrimitiveType)
    private const val BLUETOOTH_SOCKET_TYPE_L2CAP = 3
    private const val INVALID_FILE_DESCRIPTOR = -1
}

/** Ordered byte-stream channel shared by RFCOMM and BR/EDR L2CAP sockets. */
private class AndroidBluetoothSocketChannel(
    private val socket: BluetoothSocket,
    override val endpointId: String,
) : EarbudChannel {
    override suspend fun connect() {
        runInterruptible(Dispatchers.IO) { socket.connect() }
    }

    override suspend fun read(buffer: ByteArray): ProtocolPacket? =
        runInterruptible(Dispatchers.IO) { socket.inputStream.read(buffer) }
            .takeIf { it >= 0 }?.let { ProtocolPacket(buffer.copyOf(it)) }

    override suspend fun write(bytes: ByteArray, route: String?) {
        require(route == null) { "Socket transports have no characteristic routes" }
        runInterruptible(Dispatchers.IO) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    override fun close() {
        runCatching(socket::close)
    }
}

/** BLE GATT implementation of the common byte-channel contract. */
@SuppressLint("MissingPermission")
private class AndroidGattChannel(
    context: Context,
    private val sessionDevice: BluetoothDevice,
    private val spec: GattTransportSpec,
) : EarbudChannel {
    override val endpointId: String = spec.id
    override val connectionDetails: String? get() = connectedDetails

    private val appContext = context.applicationContext ?: context
    private val closed = AtomicBoolean()
    private val connectCompletion = CompletableDeferred<Unit>()
    private val incoming = Channel<ProtocolPacket>(Channel.BUFFERED)
    private val writeMutex = Mutex()
    private var lastWriteStartedAt: Long? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var routes: Map<String?, ResolvedGattRoute> = emptyMap()

    @Volatile
    private var pendingWrite: PendingGattWrite? = null

    @Volatile
    private var peerResolution: CompletableDeferred<BluetoothDevice>? = null

    @Volatile
    private var connectedDetails: String? = null

    private val servicesRequested = AtomicBoolean()
    private var subscriptions: List<BluetoothGattCharacteristic> = emptyList()
    private var pendingSubscription: BluetoothGattDescriptor? = null

    private data class ResolvedGattRoute(
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic,
        val mode: GattWriteMode,
    )

    private data class PendingGattWrite(
        val route: String?,
        val characteristic: BluetoothGattCharacteristic,
        val completion: CompletableDeferred<Unit>,
    )

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!owns(gatt)) return
            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    terminate(IOException("GATT connection status=$status"))

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    val minimumMtu = spec.minimumMtu
                    if (minimumMtu == null) {
                        discoverServices(gatt)
                    } else if (!gatt.requestMtu(minimumMtu)) {
                        terminate(IOException("GATT MTU request did not start minimum=$minimumMtu"))
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    terminate(IOException("GATT disconnected"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!owns(gatt)) return
            val minimum = spec.minimumMtu ?: return
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < minimum) {
                terminate(IOException("GATT MTU insufficient minimum=$minimum actual=$mtu status=$status"))
                return
            }
            ModuleLog.debug("GattChannel", "endpoint=${spec.id} MTU=$mtu minimum=$minimum")
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!owns(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                terminate(IOException("GATT service discovery status=$status"))
                return
            }

            val requestedService = spec.serviceUuid?.let(UUID::fromString)
            val service = requestedService?.let(gatt::getService)
            if (requestedService != null && service == null) {
                val discovered = gatt.services.joinToString(prefix = "[", postfix = "]") {
                    it.uuid.toString()
                }
                terminate(
                    IOException(
                        "GATT service $requestedService unavailable; discovered=$discovered",
                    ),
                )
                return
            }
            val characteristics = service?.characteristics
                ?: gatt.services.flatMap(BluetoothGattService::getCharacteristics)
            val write = characteristics.resolve(
                uuid = UUID.fromString(spec.writeCharacteristicUuid),
                instanceId = spec.writeInstanceId,
            ) { it.canWrite(spec.writeMode) }
            val notify = characteristics.resolve(
                uuid = UUID.fromString(spec.notifyCharacteristicUuid),
                instanceId = spec.notifyInstanceId,
            ) { it.canNotify() }
            if (write == null || notify == null) {
                val discovered = characteristics.joinToString(prefix = "[", postfix = "]") {
                    it.describe()
                }
                terminate(
                    IOException(
                        "GATT characteristics unavailable " +
                            "write=${spec.writeCharacteristicUuid}/${spec.writeInstanceId} " +
                            "notify=${spec.notifyCharacteristicUuid}/${spec.notifyInstanceId}; " +
                            "discovered=$discovered",
                    ),
                )
                return
            }

            val resolved = linkedMapOf<String?, ResolvedGattRoute>(
                null to ResolvedGattRoute(write, notify, spec.writeMode),
            )
            for (route in spec.additionalRoutes) {
                val routeWrite = characteristics.resolve(UUID.fromString(route.writeCharacteristicUuid), null) {
                    it.canWrite(route.writeMode)
                }
                val routeNotify = characteristics.resolve(UUID.fromString(route.notifyCharacteristicUuid), null) {
                    it.canNotify()
                }
                if (routeWrite == null || routeNotify == null) {
                    terminate(IOException("GATT route ${route.id} characteristics unavailable"))
                    return
                }
                resolved[route.id] = ResolvedGattRoute(routeWrite, routeNotify, route.writeMode)
            }
            routes = resolved
            connectedDetails =
                "service=${service?.uuid ?: "<any>"} " + resolved.entries.joinToString { (id, route) ->
                    "route=${id ?: "primary"} write=${route.write.describe()} notify=${route.notify.describe()}"
                }
            subscriptions = resolved.values.map { it.notify }.distinct()
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!owns(gatt)) return
            val expected = pendingSubscription ?: return
            if (descriptor.uuid != expected.uuid ||
                !descriptor.characteristic.sameAs(expected.characteristic)
            ) return
            pendingSubscription = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribeNext(gatt)
            } else {
                terminate(IOException("GATT CCCD write status=$status"))
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            offerIncoming(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            offerIncoming(gatt, characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!owns(gatt)) return
            val pending = pendingWrite ?: return
            if (!characteristic.sameAs(pending.characteristic)) return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending.completion.complete(Unit)
            } else {
                pending.completion.completeExceptionally(GattWriteException(status, pending.route))
            }
        }
    }

    override suspend fun connect() {
        check(!closed.get()) { "GATT channel is closed" }
        val target = resolvePeer()
        check(!closed.get()) { "GATT channel is closed" }
        val active = target.connectGatt(
            appContext,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
        ) ?: error("could not create GATT client")
        gatt = active
        connectCompletion.await()
    }

    private suspend fun resolvePeer(): BluetoothDevice = when (val selection = spec.peerSelection) {
        GattPeerSelection.SessionDevice -> sessionDevice
        is GattPeerSelection.CompanionDevice -> resolveCompanionPeer(selection)
    }

    private suspend fun resolveCompanionPeer(
        selection: GattPeerSelection.CompanionDevice,
    ): BluetoothDevice {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: throw IOException("Bluetooth manager is unavailable")
        val adapter = manager.adapter
            ?: throw IOException("Bluetooth adapter is unavailable")
        val sessionIdentity = sessionDevice.toGattPeerIdentity()
        runCatching { adapter.bondedDevices.orEmpty() }
            .getOrDefault(emptySet())
            .firstOrNull { candidate ->
                (selection.allowSessionAddress || !candidate.sameAddressAs(sessionDevice)) &&
                    selection.matcher.matches(
                        sessionIdentity,
                        candidate.toGattPeerIdentity(),
                    )
            }
            ?.let { return it }

        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("BLE scanner is unavailable")
        val completion = CompletableDeferred<BluetoothDevice>()
        peerResolution = completion
        val callbackCount = AtomicInteger(0)
        val uniqueCandidates = ConcurrentHashMap.newKeySet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                callbackCount.incrementAndGet()
                accept(result, callbackType)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                callbackCount.addAndGet(results.size)
                results.forEach { result -> accept(result, callbackType = null) }
            }

            override fun onScanFailed(errorCode: Int) {
                ModuleLog.debug(
                    GATT_SCAN_COMPONENT,
                    "scan failed endpoint=${spec.id} error=$errorCode " +
                        "callbacks=${callbackCount.get()} unique=${uniqueCandidates.size}",
                )
                completion.completeExceptionally(
                    IOException("BLE companion scan failed with error=$errorCode"),
                )
            }

            private fun accept(result: ScanResult, callbackType: Int?) {
                if (completion.isCompleted) return
                if (!selection.allowSessionAddress && result.device.sameAddressAs(sessionDevice)) return
                val candidate = result.toGattPeerIdentity()
                val candidateKey = candidate.deviceAddress
                    ?: "anonymous-${System.identityHashCode(result.device)}"
                val firstObservation = uniqueCandidates.add(candidateKey)
                val matched = selection.matcher.matches(sessionIdentity, candidate)
                if (firstObservation || matched) {
                    ModuleLog.debug(GATT_SCAN_COMPONENT) {
                        "candidate endpoint=${spec.id} callbackType=${callbackType ?: "batch"} " +
                            "address=${maskBluetoothAddress(candidate.deviceAddress)} " +
                            "name=${candidate.deviceName ?: "<none>"} rssi=${result.rssi} " +
                            "services=${candidate.serviceUuids.ifEmpty { setOf("<none>") }} " +
                            "manufacturerData=${candidate.manufacturerData.diagnosticSummary()} " +
                            "matched=$matched"
                    }
                }
                if (matched) {
                    ModuleLog.debug(
                        GATT_SCAN_COMPONENT,
                        "companion matched endpoint=${spec.id} " +
                            "address=${maskBluetoothAddress(candidate.deviceAddress)} " +
                            "callbacks=${callbackCount.get()} unique=${uniqueCandidates.size}",
                    )
                    completion.complete(result.device)
                }
            }
        }
        val scanFilters = if (
            selection.filter.manufacturerId == null && selection.filter.serviceUuid == null
        ) {
            // Device names are intentionally matched from ScanResult in software. Passing a
            // device-name-only spec as an empty ScanFilter can suppress advertisements on some
            // Android Bluetooth stacks, so use the platform's explicit unfiltered form.
            emptyList()
        } else {
            listOf(selection.filter.toAndroidScanFilter())
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        ModuleLog.debug(
            GATT_SCAN_COMPONENT,
            "scan starting endpoint=${spec.id} timeoutMs=${selection.scanTimeoutMs} " +
                "platformFilters=${scanFilters.size} callbackType=${scanSettings.callbackType}",
        )
        try {
            scanner.startScan(scanFilters, scanSettings, callback)
            return try {
                withTimeout(selection.scanTimeoutMs) { completion.await() }
            } catch (error: TimeoutCancellationException) {
                ModuleLog.debug(
                    GATT_SCAN_COMPONENT,
                    "scan timed out endpoint=${spec.id} callbacks=${callbackCount.get()} " +
                        "unique=${uniqueCandidates.size}",
                )
                throw IOException(
                    "BLE companion endpoint was not found within ${selection.scanTimeoutMs}ms",
                    error,
                )
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
            ModuleLog.debug(
                GATT_SCAN_COMPONENT,
                "scan stopped endpoint=${spec.id} completed=${completion.isCompleted} " +
                    "callbacks=${callbackCount.get()} unique=${uniqueCandidates.size}",
            )
            if (peerResolution === completion) peerResolution = null
        }
    }

    override suspend fun read(buffer: ByteArray): ProtocolPacket? {
        val result = incoming.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun write(bytes: ByteArray, route: String?) {
        require(bytes.isNotEmpty())
        writeMutex.withLock {
            if (spec.minimumWriteIntervalMs > 0L) {
                lastWriteStartedAt?.let { last ->
                    val remaining = spec.minimumWriteIntervalMs - (SystemClock.elapsedRealtime() - last)
                    if (remaining > 0L) delay(remaining)
                }
            }
            check(!closed.get()) { "GATT channel is closed" }
            val active = gatt ?: error("GATT is not connected")
            val target = routes[route] ?: error("GATT route ${route ?: "primary"} is not ready")
            val characteristic = target.write
            val writeType = when (target.mode) {
                GattWriteMode.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                GattWriteMode.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            if (spec.minimumWriteIntervalMs > 0L) lastWriteStartedAt = SystemClock.elapsedRealtime()
            if (target.mode == GattWriteMode.WITHOUT_RESPONSE) {
                val started = active.writeCharacteristic(
                    characteristic,
                    bytes,
                    writeType,
                ) == BluetoothStatusCodes.SUCCESS
                if (!started) throw IOException("GATT write command did not start")
                return@withLock
            }

            val completion = CompletableDeferred<Unit>()
            val pending = PendingGattWrite(route, characteristic, completion)
            pendingWrite = pending
            val started = active.writeCharacteristic(
                characteristic,
                bytes,
                writeType,
            ) == BluetoothStatusCodes.SUCCESS
            if (!started) {
                pendingWrite = null
                throw IOException("GATT write did not start")
            }
            try {
                withTimeout(WRITE_TIMEOUT_MS) { completion.await() }
            } finally {
                if (pendingWrite === pending) pendingWrite = null
            }
        }
    }

    override fun close() {
        terminate(IOException("GATT channel closed"))
    }

    private fun owns(candidate: BluetoothGatt): Boolean =
        !closed.get() && (gatt == null || gatt === candidate)

    private fun offerIncoming(
        candidate: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
    ) {
        if (!owns(candidate) || value == null || value.isEmpty()) return
        val route = routes.entries.singleOrNull { characteristic.sameAs(it.value.notify) } ?: return
        if (incoming.trySend(ProtocolPacket(value.copyOf(), route.key)).isFailure) {
            terminate(IOException("GATT incoming queue overflow"))
        }
    }

    private fun discoverServices(active: BluetoothGatt) {
        if (servicesRequested.compareAndSet(false, true) && !active.discoverServices()) {
            terminate(IOException("GATT service discovery did not start"))
        }
    }

    /** Android permits one outstanding GATT operation; serialize all CCCD registrations. */
    private fun subscribeNext(active: BluetoothGatt) {
        while (subscriptions.isNotEmpty()) {
            val notify = subscriptions.first()
            subscriptions = subscriptions.drop(1)
            if (!active.setCharacteristicNotification(notify, true)) {
                terminate(IOException("GATT notification registration failed for ${notify.uuid}"))
                return
            }
            val cccd = notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
            if (cccd == null) {
                if (spec.notificationsRequired) {
                    terminate(IOException("GATT notify characteristic ${notify.uuid} has no CCCD"))
                    return
                }
                continue
            }
            pendingSubscription = cccd
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (active.writeDescriptor(cccd, value) != BluetoothStatusCodes.SUCCESS) {
                terminate(IOException("GATT CCCD write did not start"))
            }
            return
        }
        connectCompletion.complete(Unit)
    }

    private fun terminate(error: IOException) {
        if (!closed.compareAndSet(false, true)) return
        peerResolution?.completeExceptionally(error)
        peerResolution = null
        connectCompletion.completeExceptionally(error)
        pendingWrite?.completion?.completeExceptionally(error)
        pendingWrite = null
        incoming.close(error)
        val active = gatt
        gatt = null
        routes = emptyMap()
        pendingSubscription = null
        subscriptions = emptyList()
        runCatching { active?.disconnect() }
        runCatching { active?.close() }
    }

    private fun List<BluetoothGattCharacteristic>.resolve(
        uuid: UUID,
        instanceId: Int?,
        predicate: (BluetoothGattCharacteristic) -> Boolean,
    ): BluetoothGattCharacteristic? =
        firstOrNull {
            instanceId != null && it.uuid == uuid && it.instanceId == instanceId && predicate(it)
        } ?: firstOrNull {
            it.uuid == uuid && predicate(it)
        }

    private fun BluetoothGattCharacteristic.canWrite(mode: GattWriteMode): Boolean = when (mode) {
        GattWriteMode.WITH_RESPONSE ->
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        GattWriteMode.WITHOUT_RESPONSE ->
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    private fun BluetoothGattCharacteristic.canNotify(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

    private fun BluetoothGattCharacteristic.sameAs(other: BluetoothGattCharacteristic): Boolean =
        uuid == other.uuid && instanceId == other.instanceId && service?.uuid == other.service?.uuid

    private fun BluetoothGattCharacteristic.describe(): String =
        "${uuid}#${instanceId}/properties=0x${properties.toString(16)}"

    private fun GattScanFilterSpec.toAndroidScanFilter(): ScanFilter {
        val builder = ScanFilter.Builder()
        manufacturerId?.let { builder.setManufacturerData(it, byteArrayOf()) }
        serviceUuid?.let { builder.setServiceUuid(ParcelUuid.fromString(it)) }
        // Device names are matched in software from ScanResult plus BluetoothDevice cache. Some
        // companion endpoints expose their name only after GATT discovery, so platform name
        // filtering can suppress an otherwise valid candidate before the Adapter sees it.
        return builder.build()
    }

    private fun BluetoothDevice.toGattPeerIdentity(): GattPeerIdentity = GattPeerIdentity(
        deviceName = runCatching { name ?: alias }.getOrNull(),
        deviceAddress = runCatching { address }.getOrNull(),
        serviceUuids = runCatching {
            uuids.orEmpty().mapTo(linkedSetOf()) { it.uuid.toString() }
        }.getOrDefault(emptySet()),
    )

    private fun ScanResult.toGattPeerIdentity(): GattPeerIdentity {
        val record = scanRecord
        val cached = device.toGattPeerIdentity()
        val advertisedServices = record?.serviceUuids.orEmpty()
            .mapTo(linkedSetOf()) { it.uuid.toString() }
        val manufacturerData = buildMap {
            val values = record?.manufacturerSpecificData ?: return@buildMap
            repeat(values.size) { index ->
                put(values.keyAt(index), values.valueAt(index).copyOf())
            }
        }
        return cached.copy(
            deviceName = record?.deviceName ?: cached.deviceName,
            serviceUuids = cached.serviceUuids + advertisedServices,
            manufacturerData = manufacturerData,
        )
    }

    private fun Map<Int, ByteArray>.diagnosticSummary(): String =
        if (isEmpty()) {
            "<none>"
        } else {
            entries.joinToString(prefix = "[", postfix = "]") { (id, bytes) ->
                "0x${id.toString(16).uppercase()}:${bytes.toHex()}"
            }
        }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private fun BluetoothDevice.sameAddressAs(other: BluetoothDevice): Boolean {
        val first = runCatching { address }.getOrNull() ?: return false
        val second = runCatching { other.address }.getOrNull() ?: return false
        return first.equals(second, ignoreCase = true)
    }

    private companion object {
        const val GATT_SCAN_COMPONENT = "GattScan"
        const val WRITE_TIMEOUT_MS = 4_000L
        val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
