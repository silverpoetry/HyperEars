package dev.hyperears.presentation

import android.content.Context
import android.os.Handler
import dev.hyperears.integration.*

/** Serialized in the Bluetooth session host so process death also closes its private channel. */
internal class AppleLongPressRuntime(
    private val host: Host,
    private val leaseSender: (String, String, Boolean) -> Boolean,
    private val controlSender: (String, AppleControlRequest) -> Boolean,
) {
    constructor(context: Context, handler: Handler,
        leaseSender: (String, String, Boolean) -> Boolean,
        controlSender: (String, AppleControlRequest) -> Boolean,
    ) : this(AndroidAppleLongPressHost(context, handler), leaseSender, controlSender)

    interface Host {
        fun now(): Long
        fun ready(address: String): Boolean
        fun assistant(address: String): Boolean
        fun listen(changed: () -> Unit): Boolean
        fun stop()
        fun schedule(task: Runnable, delay: Long)
        fun cancel(task: Runnable)
    }
    private data class Entry(val session: String, var state: EarbudState, val lease: AppleLongPressLease)
    private val entries = linkedMapOf<String, Entry>()
    private var listening = false
    private val timer = Runnable { refresh() }

    fun update(state: EarbudState, session: String, enabled: Boolean = true) {
        val address = state.address ?: return
        if (!enabled || !state.sessionActive || !state.privateChannelConnected || !state.handshakeAccepted ||
            state.lifecycle.controlOwnership != ControlOwnership.MODULE) { remove(address); return }
        if (entries[address]?.session != session) remove(address)
        val settings = state.features.get<AppleGestureSettingsFeatureState>()
        val mode = state.features.get<AppleListeningModeFeatureState>()
        if (entries[address] == null) {
            if (!AppleLongPressActions.eligible(settings, mode) || settings?.interceptionMask == null) return
            entries[address] = Entry(session, state, AppleLongPressLease())
        } else entries.getValue(address).state = state
        if (!listening) {
            listening = true
            if (!runCatching { host.listen(::refresh) }.getOrDefault(false)) { clear(); return }
        }
        refresh()
    }

    private fun refresh() {
        host.cancel(timer)
        entries.forEach { (address, entry) ->
            val settings = entry.state.features.get<AppleGestureSettingsFeatureState>()
            val mode = entry.state.features.get<AppleListeningModeFeatureState>()
            val stem = entry.state.features.get<AppleStemFeatureState>()
            val ready = runCatching { host.ready(address) }.getOrDefault(false) &&
                AppleLongPressActions.eligible(settings, mode)
            val write = entry.lease.update(ready, settings?.interceptionMask,
                settings?.interceptionSequence ?: 0, stem?.sequence, host.now())
            if (write != null && !send(address, entry, write)) {
                entry.lease.stop()?.let { send(address, entry, it) }
            }
            if (entry.lease.consume(stem) && ready && settings != null) {
                val action = if (stem!!.bud == AppleBud.LEFT) settings.leftLongPress else settings.rightLongPress
                val success = when (action) {
                    AppleLongPressAction.DIGITAL_ASSISTANT -> runCatching { host.assistant(address) }.getOrDefault(false)
                    AppleLongPressAction.NOISE_CONTROL -> {
                        val cycle = AppleLongPressActions.cycle(settings, mode)
                        cycle.isNotEmpty() && runCatching {
                            controlSender(address, AppleControlRequest.SetListeningMode(cycle[(cycle.indexOf(mode!!.mode) + 1) % cycle.size]))
                        }.getOrDefault(false)
                    }
                    null -> false
                }
                if (!success) entry.lease.stop()?.let { send(address, entry, it) }
            }
        }
        entries.values.mapNotNull { it.lease.deadline }.minOrNull()?.let {
            host.schedule(timer, (it - host.now()).coerceAtLeast(1))
        }
    }

    private fun send(address: String, entry: Entry, write: AppleLongPressLease.Write): Boolean =
        runCatching { leaseSender(address, entry.session, write == AppleLongPressLease.Write.ACQUIRE) }.getOrDefault(false)

    /** Call synchronously before channel close or transfer of control ownership. */
    fun remove(address: String) {
        entries.remove(address)?.let { entry -> entry.lease.stop()?.let { send(address, entry, it) } }
        if (entries.isEmpty()) {
            host.cancel(timer)
            if (listening) runCatching { host.stop() }
            listening = false
        }
    }

    fun clear() { entries.keys.toList().forEach(::remove) }
}
