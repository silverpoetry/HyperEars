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
import androidx.core.util.size
import dev.hyperears.integration.EarbudTransportSpec
import dev.hyperears.integration.GattPeerIdentity
import dev.hyperears.integration.GattPeerSelection
import dev.hyperears.integration.GattScanFilterSpec
import dev.hyperears.integration.GattTransportSpec
import dev.hyperears.integration.GattWriteMode
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

    suspend fun read(buffer: ByteArray): Int

    suspend fun write(bytes: ByteArray)
}

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

    override suspend fun read(buffer: ByteArray): Int =
        runInterruptible(Dispatchers.IO) { socket.inputStream.read(buffer) }

    override suspend fun write(bytes: ByteArray) {
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
    private val incoming = Channel<ByteArray>(Channel.BUFFERED)
    private val writeMutex = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var pendingWrite: CompletableDeferred<Unit>? = null

    @Volatile
    private var peerResolution: CompletableDeferred<BluetoothDevice>? = null

    @Volatile
    private var connectedDetails: String? = null

    private var pendingRead = ByteArray(0)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!owns(gatt)) return
            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    terminate(IOException("GATT connection status=$status"))

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        terminate(IOException("GATT service discovery did not start"))
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    terminate(IOException("GATT disconnected"))
            }
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

            writeCharacteristic = write
            connectedDetails =
                "service=${service?.uuid ?: "<any>"} write=${write.describe()} " +
                    "notify=${notify.describe()}"
            if (!gatt.setCharacteristicNotification(notify, true)) {
                terminate(IOException("GATT notification registration failed"))
                return
            }
            val cccd = notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
            if (cccd == null) {
                if (spec.notificationsRequired) {
                    terminate(IOException("GATT notify characteristic has no CCCD"))
                } else {
                    connectCompletion.complete(Unit)
                }
                return
            }
            val started =
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            if (!started) terminate(IOException("GATT CCCD write did not start"))
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!owns(gatt) || descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectCompletion.complete(Unit)
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
            offerIncoming(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            offerIncoming(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!owns(gatt)) return
            val completion = pendingWrite ?: return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completion.complete(Unit)
            } else {
                completion.completeExceptionally(IOException("GATT write status=$status"))
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
                !candidate.sameAddressAs(sessionDevice) &&
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
                if (completion.isCompleted || result.device.sameAddressAs(sessionDevice)) return
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

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        if (pendingRead.isEmpty()) {
            val result = incoming.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            pendingRead = result.getOrNull() ?: return -1
        }
        val count = minOf(buffer.size, pendingRead.size)
        pendingRead.copyInto(buffer, endIndex = count)
        pendingRead = pendingRead.copyOfRange(count, pendingRead.size)
        return count
    }

    override suspend fun write(bytes: ByteArray) {
        require(bytes.isNotEmpty())
        writeMutex.withLock {
            val active = gatt ?: error("GATT is not connected")
            val characteristic = writeCharacteristic ?: error("GATT write characteristic is not ready")
            val writeType = when (spec.writeMode) {
                GattWriteMode.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                GattWriteMode.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            if (spec.writeMode == GattWriteMode.WITHOUT_RESPONSE) {
                val started = active.writeCharacteristic(
                    characteristic,
                    bytes,
                    writeType,
                ) == BluetoothStatusCodes.SUCCESS
                if (!started) throw IOException("GATT write command did not start")
                return@withLock
            }

            val completion = CompletableDeferred<Unit>()
            pendingWrite = completion
            val started = active.writeCharacteristic(
                characteristic,
                bytes,
                writeType,
            ) == BluetoothStatusCodes.SUCCESS
            if (!started) {
                pendingWrite = null
                throw IOException("GATT write did not start")
            }
            withTimeout(WRITE_TIMEOUT_MS) { completion.await() }
        }
    }

    override fun close() {
        terminate(IOException("GATT channel closed"))
    }

    private fun owns(candidate: BluetoothGatt): Boolean =
        !closed.get() && (gatt == null || gatt === candidate)

    private fun offerIncoming(value: ByteArray?) {
        if (value != null && value.isNotEmpty()) incoming.trySend(value.copyOf())
    }

    private fun terminate(error: IOException) {
        if (!closed.compareAndSet(false, true)) return
        peerResolution?.completeExceptionally(error)
        peerResolution = null
        connectCompletion.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        pendingWrite = null
        incoming.close(error)
        val active = gatt
        gatt = null
        writeCharacteristic = null
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
