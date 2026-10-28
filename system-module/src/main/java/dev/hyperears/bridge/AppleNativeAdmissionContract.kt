package dev.hyperears.bridge

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import dev.hyperears.presentation.HeadsetConnection

/** Connection-scoped transport permission, separate from MiLink presentation admission. */
internal object AppleNativeAdmissionContract {
    const val REQUEST = "dev.hyperears.action.APPLE_NATIVE_REQUEST"
    const val DECISION = "dev.hyperears.action.APPLE_NATIVE_DECISION"
    const val READY = "dev.hyperears.action.APPLE_NATIVE_READY"
    const val CANCEL = "dev.hyperears.action.APPLE_NATIVE_CANCEL"
    data class Request(val address: String, val connection: HeadsetConnection, val producer: IBinder, val producerStartedAt: Long = connection.connectedAtElapsedMs, val query: String = connection.id)
    fun request(value: Request, cancel: Boolean = false): Intent = attach(
        Intent(if (cancel) CANCEL else REQUEST).setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE), value)
    data class Decision(val request: Request, val accepted: Boolean?, val revision: Long)
    fun decision(value: Request, accepted: Boolean?, revision: Long): Intent = attach(
        Intent(DECISION).setPackage(ModuleContract.BLUETOOTH_PACKAGE), value)
        .putExtra("revision", revision).apply { if (accepted != null) putExtra("accepted", accepted) }
    private fun attach(intent: Intent, value: Request): Intent = intent
        .putExtra("address", value.address).putExtra("connection", value.connection.id)
        .putExtra("started", value.connection.connectedAtElapsedMs)
        .putExtra("producer_started", value.producerStartedAt).putExtra("query", value.query)
        .putExtras(Bundle().apply { putBinder("producer", value.producer) })
    fun read(intent: Intent): Request? {
        val address = intent.getStringExtra("address")?.takeIf(BluetoothAdapter::checkBluetoothAddress) ?: return null
        val token = intent.getStringExtra("connection")?.takeIf { it.length in 1..80 } ?: return null
        val started = intent.getLongExtra("started", -1).takeIf { it >= 0 } ?: return null
        val producer = intent.extras?.getBinder("producer") ?: return null
        val producerStarted = intent.getLongExtra("producer_started", -1).takeIf { it in 0..started } ?: return null
        val query = intent.getStringExtra("query")?.takeIf { it.length in 1..80 } ?: return null
        return Request(address, HeadsetConnection(token, started), producer, producerStarted, query)
    }
    @Suppress("DEPRECATION")
    fun readDecision(intent: Intent): Decision? {
        val request = read(intent) ?: return null
        val revision = (intent.extras?.get("revision") as? Long)?.takeIf { it > 0 } ?: return null
        val raw = intent.extras?.get("accepted")
        if (intent.hasExtra("accepted") && raw !is Boolean) return null
        return Decision(request, raw as? Boolean, revision)
    }
}
