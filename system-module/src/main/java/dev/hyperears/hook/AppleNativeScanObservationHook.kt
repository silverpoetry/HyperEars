package dev.hyperears.hook

import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import dev.hyperears.bridge.AppleCaseDiscoveryContract as Contract
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.protocol.apple.AppleProximityCodec
import io.github.libxposed.api.XposedInterface

/** Observes delivery of existing native scans. Never starts, changes, or extends native scanning. */
internal class AppleNativeScanObservationHook(private val context: Context) : HookContext() {
    @Volatile private var producer: IBinder? = null
    private val installation = HookInstallTransaction<XposedInterface.HookHandle> { it.unhook() }
    override fun install() = installation.install {
        // Stable framework callback contract, verified by complete signature before any hook is installed.
        val wrapper = findClass("android.bluetooth.le.BluetoothLeScanner\$BleScanCallbackWrapper")
        val single = wrapper.getDeclaredMethod("onScanResult", ScanResult::class.java)
        val batch = wrapper.getDeclaredMethod("onBatchScanResults", List::class.java)
        require(single.returnType == Void.TYPE && batch.returnType == Void.TYPE)
        listOf(single, batch).forEach { method ->
            installation.record(module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    if (producer?.isBinderAlive == true) {
                        val scans = when (val value = chain.args[0]) {
                            is ScanResult -> listOf(value)
                            is List<*> -> value.filterIsInstance<ScanResult>()
                            else -> emptyList()
                        }
                        if (scans.size <= 32) {
                            val now = SystemClock.elapsedRealtime()
                            val relevant = scans.filter { scan ->
                                val bytes = scan.scanRecord?.getManufacturerSpecificData(0x004c)
                                bytes != null && bytes.size in 3..512 &&
                                    now - scan.timestampNanos / 1_000_000 in 0..15000 &&
                                    AppleProximityCodec.messages(bytes).isNotEmpty()
                            }
                            if (relevant.isNotEmpty()) BroadcastIdentity.send(context, Contract.scans(ArrayList(relevant)))
                        }
                    }
                }
                result
            })
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid, ModuleContract.BLUETOOTH_PACKAGE)) return
                val incoming = Contract.producer(intent)?.takeIf { it.isBinderAlive } ?: return
                if (intent.getBooleanExtra("enabled", false)) producer = incoming
                else if (incoming == producer) producer = null
            }
        }, IntentFilter(Contract.SUBSCRIBE), Context.RECEIVER_EXPORTED)
        BroadcastIdentity.send(context, Intent(Contract.READY).setPackage(ModuleContract.BLUETOOTH_PACKAGE))
    }
}
