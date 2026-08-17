package dev.hyperears.protocoltest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Method
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val STANDARD_SPP_UUID: UUID =
    UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

internal sealed interface ClientEvent {
    data class Attempt(val endpoint: RfcommEndpoint) : ClientEvent
    data class AttemptFailed(val endpoint: RfcommEndpoint, val reason: String) : ClientEvent
    data class Connected(val endpoint: RfcommEndpoint) : ClientEvent
    data class Incoming(val bytes: ByteArray) : ClientEvent
    data class Disconnected(val reason: String) : ClientEvent
}

internal sealed class RfcommEndpoint(open val id: String, open val label: String) {
    data class ServiceUuid(
        val uuid: UUID,
        override val id: String,
        override val label: String,
    ) : RfcommEndpoint(id, label)

    data class Channel(
        val number: Int,
        val secure: Boolean,
        override val id: String,
        override val label: String,
    ) : RfcommEndpoint(id, label)
}

internal enum class ProtocolTarget(
    val label: String,
    val endpoints: List<RfcommEndpoint>,
) {
    VIVO_TWS(
        label = "vivo TWS",
        endpoints = listOf(
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("00000837-d102-11e1-9b23-00025b00a5a5"),
                id = "vivo-gaia-0837",
                label = "vivo GAIA UUID 0837",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("a5a5005b-0200-239b-e111-02d137080000"),
                id = "vivo-gaia-0837-compatible",
                label = "vivo GAIA UUID 兼容字节序",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5"),
                id = "vivo-gaia-1107",
                label = "vivo GAIA UUID 1107",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
            RfcommEndpoint.Channel(
                number = 12,
                secure = true,
                id = "rfcomm-12",
                label = "RFCOMM 通道 12（Air3 Pro）",
            ),
            RfcommEndpoint.Channel(
                number = 13,
                secure = true,
                id = "rfcomm-13",
                label = "RFCOMM 通道 13（TWS 3e 参考）",
            ),
        ),
    ),
    STARRING_ULTRA(
        label = "StarRing Ultra",
        endpoints = listOf(
            RfcommEndpoint.Channel(
                number = 28,
                secure = true,
                id = "rfcomm-28",
                label = "RFCOMM 通道 28（抓包确认）",
            ),
            RfcommEndpoint.Channel(
                number = 28,
                secure = false,
                id = "rfcomm-28-insecure",
                label = "RFCOMM 通道 28（不安全）",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
            RfcommEndpoint.Channel(
                number = 5,
                secure = true,
                id = "rfcomm-5",
                label = "RFCOMM 通道 5（历史兼容）",
            ),
        ),
    ),
    BOSE_BMAP(
        label = "Bose BMAP",
        endpoints = listOf(
            RfcommEndpoint.Channel(
                number = 8,
                secure = true,
                id = "rfcomm-8",
                label = "RFCOMM 通道 8（QuietComfort 实测）",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("00000000-deca-fade-deca-deafdecacaff"),
                id = "iap2-accessory-rfcomm",
                label = "iAP2 accessory UUID（Bose 传输候选）",
            ),
            RfcommEndpoint.Channel(
                number = 2,
                secure = true,
                id = "rfcomm-2",
                label = "RFCOMM 通道 2（兼容回退）",
            ),
        ),
    ),
    EDIFIER_BES(
        label = "Edifier BES",
        endpoints = listOf(
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF"),
                id = "edifier-spp",
                label = "Edifier SPP UUID",
            ),
            RfcommEndpoint.Channel(
                number = 1,
                secure = true,
                id = "rfcomm-1",
                label = "RFCOMM 通道 1（Edifier 回退）",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
        ),
    ),
    ROSE_BUDSFEEL(
        label = "ROSE BudsFeel",
        endpoints = listOf(
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931"),
                id = "rose-budsfeel-0cf12d31",
                label = "ROSE BudsFeel RFCOMM UUID",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
            RfcommEndpoint.Channel(
                number = 1,
                secure = true,
                id = "rfcomm-1",
                label = "RFCOMM 通道 1（回退）",
            ),
            RfcommEndpoint.Channel(
                number = 5,
                secure = true,
                id = "rfcomm-5",
                label = "RFCOMM 通道 5（回退）",
            ),
        ),
    ),
    TECHNICS_RACE(
        label = "Technics RACE",
        endpoints = listOf(
            RfcommEndpoint.ServiceUuid(
                uuid = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
                id = "technics-race-rfcomm",
                label = "Technics RACE RFCOMM UUID",
            ),
            RfcommEndpoint.ServiceUuid(
                uuid = STANDARD_SPP_UUID,
                id = "standard-spp",
                label = "标准 SPP UUID",
            ),
        ),
    ),
    ;

    companion object {
        fun fromDevice(name: String, address: String): ProtocolTarget? {
            val normalized = name.lowercase()
            val compactName = normalized.filter(Char::isLetterOrDigit)
            return when {
                TECHNICS_AZ_NAME.matches(compactName) -> TECHNICS_RACE

                normalized.contains("bose") ||
                    normalized.contains("quietcomfort") ||
                    address.startsWith("BC:87:FA", ignoreCase = true) -> BOSE_BMAP

                normalized.contains("starring") ||
                    normalized.contains("star ring") ||
                    normalized.contains("lightyear") ||
                    normalized.contains("籁特") -> STARRING_ULTRA

                (normalized.contains("vivo") || normalized.contains("iqoo")) &&
                    (normalized.contains("tws") || normalized.contains("air")) -> VIVO_TWS

                normalized.contains("edifier") ||
                    normalized.contains("w860nb") ||
                    normalized.contains("w820nb") ||
                    normalized.contains("w830nb") -> EDIFIER_BES

                normalized.contains("rose") ||
                    normalized.contains("budsfeel") ||
                    normalized.contains("ceramics") ||
                    normalized.contains("弱水") -> ROSE_BUDSFEEL

                else -> null
            }
        }

        private val TECHNICS_AZ_NAME =
            Regex("^(?:technics)?(?:eah)?az\\d{2,3}[a-z0-9]*$")
    }
}

@SuppressLint("MissingPermission")
internal class RfcommProbeClient(context: Context) : Closeable {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val mutableEvents = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events = mutableEvents.asSharedFlow()

    @Volatile
    private var socket: BluetoothSocket? = null

    suspend fun connect(
        address: String,
        endpoints: List<RfcommEndpoint>,
    ): RfcommEndpoint {
        closeSocket("重新连接")
        val bluetoothAdapter = adapter ?: error("设备没有可用的蓝牙适配器")
        check(bluetoothAdapter.isEnabled) { "请先开启蓝牙" }
        bluetoothAdapter.cancelDiscovery()
        val device = bluetoothAdapter.getRemoteDevice(address)
        var lastFailure: Throwable? = null

        for (endpoint in endpoints.distinctBy { it.id }) {
            mutableEvents.emit(ClientEvent.Attempt(endpoint))
            val candidate = try {
                createSocket(device, endpoint)
            } catch (failure: Throwable) {
                lastFailure = failure
                mutableEvents.emit(ClientEvent.AttemptFailed(endpoint, failure.conciseMessage()))
                continue
            }

            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    connectCancellable(candidate)
                }
                socket = candidate
                mutableEvents.emit(ClientEvent.Connected(endpoint))
                startReader(candidate)
                return endpoint
            } catch (cancelled: CancellationException) {
                runCatching { candidate.close() }
                if (cancelled is TimeoutCancellationException) {
                    lastFailure = cancelled
                    mutableEvents.emit(ClientEvent.AttemptFailed(endpoint, "连接超时"))
                    continue
                }
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                runCatching { candidate.close() }
                mutableEvents.emit(ClientEvent.AttemptFailed(endpoint, failure.conciseMessage()))
            }
        }

        throw IOException("所有 RFCOMM 入口均连接失败", lastFailure)
    }

    suspend fun send(packet: ByteArray) {
        sendMutex.withLock {
            val active = socket ?: error("RFCOMM 尚未连接")
            withContext(Dispatchers.IO) {
                active.outputStream.write(packet)
                active.outputStream.flush()
            }
        }
    }

    private fun startReader(active: BluetoothSocket) {
        scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (currentCoroutineContext().isActive && socket === active) {
                    val count = active.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 0) mutableEvents.emit(ClientEvent.Incoming(buffer.copyOf(count)))
                }
                if (socket === active) closeSocket("耳机关闭了 RFCOMM")
            } catch (failure: IOException) {
                if (socket === active) closeSocket(failure.conciseMessage())
            }
        }
    }

    private suspend fun connectCancellable(candidate: BluetoothSocket) = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val worker = launch(Dispatchers.IO) {
                try {
                    candidate.connect()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation {
                runCatching { candidate.close() }
                worker.cancel()
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun createSocket(
        device: BluetoothDevice,
        endpoint: RfcommEndpoint,
    ): BluetoothSocket = when (endpoint) {
        is RfcommEndpoint.ServiceUuid ->
            device.createRfcommSocketToServiceRecord(endpoint.uuid)

        is RfcommEndpoint.Channel -> {
            val methodName =
                if (endpoint.secure) "createRfcommSocket" else "createInsecureRfcommSocket"
            val method: Method =
                device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
            method.invoke(device, endpoint.number) as BluetoothSocket
        }
    }

    private fun closeSocket(reason: String) {
        val active = socket
        socket = null
        runCatching { active?.close() }
        if (active != null) mutableEvents.tryEmit(ClientEvent.Disconnected(reason))
    }

    override fun close() {
        closeSocket("已主动断开")
    }

    fun destroy() {
        close()
        scope.cancel()
    }

    private fun Throwable.conciseMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_500L
    }
}
