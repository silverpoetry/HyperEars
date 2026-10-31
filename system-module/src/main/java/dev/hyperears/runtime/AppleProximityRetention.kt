package dev.hyperears.runtime

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hyperears.bridge.AppleCaseDiscoveryContract as Contract
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.integration.AppleAirPodsAdapter
import dev.hyperears.protocol.apple.AppleProximityKeys
import dev.hyperears.protocol.apple.AppleProximityReport
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, process-local key retention for identities admitted by an actual AACP session. */
internal class AppleProximityRetention(
    private val context: Context,
    private val producer: IBinder,
    private val allowed: (String, AppleAirPodsAdapter) -> Boolean,
    private val onCase: (String, AppleProximityReport, Long) -> Unit,
) : Closeable {
    private class Entry(val device: BluetoothDevice, val adapter: AppleAirPodsAdapter) {
        lateinit var registration: AppleProximityRuntime.Registration
        var report: AppleProximityReport? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private val runtime = AppleProximityRuntime(context)
    private val entries = linkedMapOf<String, Entry>()
    private val nativeModels = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun nativeModelType(address: String): String? = nativeModels[address]
    private val closed = AtomicBoolean()
    private var announced: Boolean? = null
    private fun eligible(entry: Entry): Boolean = !closed.get() && runCatching {
        dev.hyperears.bridge.BluetoothHostIdentity.isPaired(entry.device) && allowed(entry.device.address, entry.adapter)
    }.getOrDefault(false)

    /** Owns a defensive copy; bootstrap transport callback remains responsible for its original. */
    fun retain(device: BluetoothDevice, adapter: AppleAirPodsAdapter, keys: AppleProximityKeys) {
        if (!handler.post {
            if (closed.get()) { keys.close(); return@post }
            val address = device.address
            val entry = entries[address] ?: Entry(device, adapter).also { created ->
                if (entries.size >= 8) {
                    val oldest = entries.keys.first(); nativeModels.remove(oldest)
                    entries.remove(oldest)?.registration?.close()
                }
                created.registration = runtime.register(device, { eligible(created) },
                    onReport = { created.report = it
                        if (!closed.get()) {
                            val model = String.format(java.util.Locale.ROOT, "%04X", it.modelId)
                            if (nativeModels.put(address, model) != model) dev.hyperears.hook.ModuleLog.debug("AppleProximity", "retained native model=$model address=${dev.hyperears.hook.maskBluetoothAddress(address)}")
                        }
                    },
                    onCaseOpen = {
                        if (eligible(created)) created.report?.let { onCase(address, it, android.os.SystemClock.elapsedRealtimeNanos()) }
                    }, activeScan = false)
                entries[address] = created
            }
            if (eligible(entry)) entry.registration.acceptKeys(keys) else { keys.close(); prune() }
            announce()
        }) keys.close()
    }
    fun observe(results: List<ScanResult>) { handler.post { prune(); runtime.observeNativeResults(results) } }
    fun disconnected(address: String?) { handler.post {
        prune()
        entries.values.filter { address == null || it.device.address.equals(address, true) }
            .forEach { it.registration.scanWindow() }
    } }
    fun refresh(forceAnnouncement: Boolean = false) { handler.post { prune(); announce(forceAnnouncement) } }
    private fun announce(force: Boolean = false) {
        val enabled = !closed.get() && entries.isNotEmpty()
        if (!force && announced == enabled) return
        announced = enabled
        BroadcastIdentity.send(context, Contract.subscribe(producer, enabled))
    }
    private fun prune() {
        entries.entries.removeAll { (_, value) ->
            (!eligible(value)).also { if (it) { nativeModels.remove(value.device.address); value.registration.close() } }
        }
        announce()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                entries.values.forEach { it.registration.close() }; entries.clear(); nativeModels.clear(); announce()
            } else {
                prune()
                if (intent.action == Intent.ACTION_USER_PRESENT) entries.values.forEach { it.registration.scanWindow() }
            }
        }
    }
    init {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            entries.values.forEach { it.registration.close() }; entries.clear(); nativeModels.clear()
            runtime.close(); announce()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
