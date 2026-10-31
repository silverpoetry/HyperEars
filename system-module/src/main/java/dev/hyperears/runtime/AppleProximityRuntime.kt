package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.hyperears.protocol.apple.AppleProximityCodec
import dev.hyperears.protocol.apple.AppleProximityKeys
import dev.hyperears.protocol.apple.AppleProximityOwner
import dev.hyperears.protocol.apple.AppleProximityReport
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local admitted Apple identities. No keys cross IPC or survive host destruction.
 * Explicit connection/disconnection events may open a 30-second low-power scan window;
 * an active window is never extended by advertisements, repeated requests or retries.
 * A closed window retains keys for a later explicit host event, not a permanent scan.
 */
@SuppressLint("MissingPermission")
internal class AppleProximityRuntime(private val context: Context) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val registrations = mutableSetOf<Registration>()
    private val closed = AtomicBoolean()
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var receiverRegistered = false
    private val stopWindow = Runnable { stopScan() }

    internal inner class Registration internal constructor(
        private val device: BluetoothDevice,
        private val isEligible: () -> Boolean,
        private val onReport: (AppleProximityReport) -> Unit,
        private val onCaseOpen: () -> Unit,
        private val activeScan: Boolean,
    ) : Closeable {
        private val revoked = AtomicBoolean()
        private val owner = AppleProximityOwner(device.address)
        private val token = owner.admit()
        private val confirm = Runnable {
            if (eligible() && owner.lid.confirm(SystemClock.elapsedRealtime())) onCaseOpen()
        }

        internal fun eligible(): Boolean = !closed.get() && !revoked.get() && runCatching {
            device.bondState == BluetoothDevice.BOND_BONDED && isEligible()
        }.getOrDefault(false)

        /** Takes ownership immediately. Every rejection and stale callback destroys its key copy. */
        fun acceptKeys(keys: AppleProximityKeys) {
            if (closed.get() || revoked.get()) { keys.close(); return }
            if (!handler.post {
                if (eligible()) {
                    owner.acceptKeys(token, keys)
                    if (activeScan) startScan()
                } else {
                    keys.close()
                    close()
                }
            }) keys.close()
        }

        fun scanWindow() { handler.post { if (eligible()) startScan() else close() } }

        internal fun observe(result: ScanResult, now: Long) {
            if (!eligible()) { close(); return }
            val bytes = result.scanRecord?.getManufacturerSpecificData(0x004c) ?: return
            val address = runCatching { result.device.address }.getOrNull() ?: return
            val capturedAt = result.timestampNanos / 1_000_000
            AppleProximityCodec.messages(bytes).forEach { message ->
                owner.observe(token, address, message, capturedAt, now)?.let(onReport)
            }
        }

        internal fun finishBatch(now: Long) {
            handler.removeCallbacks(confirm)
            if (!eligible()) { close(); return }
            owner.lid.confirmationDelay(now)?.let { handler.postDelayed(confirm, it) }
        }

        override fun close() {
            if (!revoked.compareAndSet(false, true)) return
            handler.post {
                handler.removeCallbacks(confirm)
                owner.revoke()
                registrations.remove(this)
                if (registrations.isEmpty()) stopScan()
            }
        }
    }

    /** Caller supplies the fresh Xiaomi-first/native and module-ownership decision, including on popup. */
    fun register(
        device: BluetoothDevice,
        isEligible: () -> Boolean,
        onReport: (AppleProximityReport) -> Unit,
        onCaseOpen: () -> Unit,
        activeScan: Boolean = true,
    ): Registration {
        val registration = Registration(device, isEligible, onReport, onCaseOpen, activeScan)
        handler.post {
            if (!registration.eligible()) { registration.close(); return@post }
            registrations += registration
            if (!receiverRegistered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                }
                receiverRegistered = runCatching {
                    context.registerReceiver(receiver, filter)
                    true
                }.getOrDefault(false)
                if (!receiverRegistered) { registration.close(); return@post }
            }
            if (activeScan) startScan()
        }
        return registration
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Recheck the actual paired device on every bond/adapter change, not an untrusted address extra.
            registrations.toList().forEach { if (!it.eligible()) it.close() }
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON
            ) registrations.toList().forEach { it.close() }
        }
    }

    private fun startScan() {
        if (closed.get() || scanCallback != null || registrations.none { it.eligible() }) return
        val activeScanner = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        }.getOrNull() ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = batch(listOf(result), this)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = batch(results, this)
            override fun onScanFailed(errorCode: Int) { handler.post { if (scanCallback === this) stopScan() } }
        }
        scanner = activeScanner
        scanCallback = callback
        try {
            activeScanner.startScan(
                listOf(ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf()).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(0).build(),
                callback,
            )
            handler.postDelayed(stopWindow, SCAN_WINDOW_MS)
        } catch (_: Exception) { stopScan() }
    }

    /** Authenticated native host observation, independent of our bounded active scan window. */
    fun observeNativeResults(results: List<ScanResult>) {
        handler.post {
            if (closed.get()) return@post
            processBatch(results)
        }
    }

    private fun processBatch(results: List<ScanResult>) {
        val now = SystemClock.elapsedRealtime()
        val owners = registrations.toList()
        results.sortedBy { it.timestampNanos }.forEach { result -> owners.forEach { it.observe(result, now) } }
        owners.forEach { it.finishBatch(now) }
    }

    private fun batch(results: List<ScanResult>, source: ScanCallback) {
        handler.post {
            if (closed.get() || scanCallback !== source) return@post
            processBatch(results)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(stopWindow)
        val oldCallback = scanCallback
        scanCallback = null
        if (oldCallback != null) runCatching { scanner?.stopScan(oldCallback) }
        scanner = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            stopScan()
            registrations.toList().forEach { it.close() }
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private companion object { const val SCAN_WINDOW_MS = 30_000L }
}
