package dev.hyperears.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One user-triggered readiness check. No polling, pairing, disconnect or connection-policy writes. */
internal class SonyLeAudioProfileGate(
    private val backend: Backend,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = 5_000,
) : Closeable {
    interface Backend {
        fun usable(): Boolean
        fun bind(listener: BluetoothProfile.ServiceListener): Boolean
        fun release(profile: Int, proxy: BluetoothProfile)
    }
    private val closed = AtomicBoolean(false)
    private val pending = mutableSetOf<Attempt>() // handler-confined

    fun request(completed: (Boolean) -> Unit): Closeable {
        val attempt = Attempt(completed)
        handler.post {
            if (closed.get() || attempt.cancelled.get()) return@post
            pending += attempt
            if (!runCatching { backend.usable() }.getOrDefault(false)) {
                attempt.finish(false)
            } else {
                handler.postDelayed(attempt.deadline, timeoutMs)
                if (!runCatching { backend.bind(attempt.listener) }.getOrDefault(false)) attempt.finish(false)
            }
        }
        return attempt
    }

    private inner class Attempt(val completed: (Boolean) -> Unit) : Closeable {
        val cancelled = AtomicBoolean(false)
        private var terminal = false
        val deadline = Runnable { finish(false) }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                handler.post {
                    val ready = profile == BluetoothProfile.LE_AUDIO && runCatching { backend.usable() }.getOrDefault(false)
                    // Closing the service proxy only releases our client binding, not the device connection.
                    runCatching { backend.release(profile, proxy) }
                    finish(ready)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.LE_AUDIO) handler.post { finish(false) }
            }
        }
        fun finish(ready: Boolean) {
            if (terminal) return
            terminal = true
            handler.removeCallbacks(deadline)
            pending.remove(this)
            if (!cancelled.get() && !closed.get()) completed(ready)
        }
        override fun close() {
            cancelled.set(true)
            handler.post { finish(false) }
        }
    }

    override fun close() {
        closed.set(true)
        handler.post { pending.toList().forEach { it.close() } }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun create(context: Context): SonyLeAudioProfileGate {
            val app = context.applicationContext ?: context
            val adapter: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            return SonyLeAudioProfileGate(object : Backend {
                override fun usable() = adapter?.isEnabled == true && adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED
                override fun bind(listener: BluetoothProfile.ServiceListener) = adapter?.getProfileProxy(app,listener,BluetoothProfile.LE_AUDIO) == true
                override fun release(profile: Int, proxy: BluetoothProfile) { adapter?.closeProfileProxy(profile,proxy) }
            })
        }
    }
}
