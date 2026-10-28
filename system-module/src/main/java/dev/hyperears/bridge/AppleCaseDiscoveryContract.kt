package dev.hyperears.bridge

import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** Private package-targeted discovery bus. Only public advertisements and projected case state; never keys. */
internal object AppleCaseDiscoveryContract {
    const val SUBSCRIBE = "dev.hyperears.action.APPLE_DISCOVERY_SUBSCRIBE"
    const val SCANS = "dev.hyperears.action.APPLE_DISCOVERY_SCANS"
    const val READY = "dev.hyperears.action.APPLE_DISCOVERY_READY"
    const val CASE = "dev.hyperears.action.APPLE_DISCOVERY_CASE"
    const val CAPTURED = "case_captured"
    const val SEQUENCE = "case_sequence"
    fun subscribe(producer: IBinder, enabled: Boolean) = Intent(SUBSCRIBE)
        .setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE).putExtra("enabled", enabled)
        .putExtras(Bundle().apply { putBinder("producer", producer) })
    fun producer(intent: Intent): IBinder? = intent.extras?.getBinder("producer")
    fun scans(results: ArrayList<ScanResult>) = Intent(SCANS).setPackage(ModuleContract.BLUETOOTH_PACKAGE)
        .putParcelableArrayListExtra("observations", results)
    fun readScans(intent: Intent): List<ScanResult> =
        intent.getParcelableArrayListExtra("observations", ScanResult::class.java)
            ?.takeIf { it.size <= 32 }.orEmpty().filter {
                val bytes = it.scanRecord?.getManufacturerSpecificData(0x004c)
                bytes != null && bytes.size in 3..512
            }
}
