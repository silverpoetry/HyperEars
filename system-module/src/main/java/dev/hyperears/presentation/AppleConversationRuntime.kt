package dev.hyperears.presentation

import android.content.Context
import android.os.Handler
import dev.hyperears.integration.*

/** Main-thread consumer of authenticated, connection-scoped Apple activity reports. */
internal class AppleConversationRuntime(private val backend: AppleConversationBackend) {
    constructor(context: Context, handler: Handler) : this(AndroidAppleConversationBackend(context, handler))
    private data class Record(val session: String, val lease: AppleConversationLease)
    private val records = mutableMapOf<String, Record>()
    private val history = AppleConversationHistory()
    private val shaped = mutableMapOf<Pair<Int, Int>, AppleConversationAudio.Player>()
    private val releasing = mutableMapOf<Pair<Int, Int>, AppleConversationAudio.Player>()
    private var listening = false
    private val deadline = Runnable { reconcile() }

    fun update(state: EarbudState, session: String, admission: NativeAdmission, enabled: Boolean) {
        val address = state.address ?: return
        val activity = state.features.get<AppleConversationFeatureState>()
        val repeatedStart = history.consume(address, session,
            activity?.supplementaryActivity ?: AppleConversationActivity.UNKNOWN)
        val eligible = enabled && admission == NativeAdmission.HYPEREARS && state.sessionActive &&
            state.privateChannelConnected && state.handshakeAccepted &&
            state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
            state.features.get<AppleSettingsFeatureState>()?.conversationAwareness == true
        if (!eligible || activity == null || !backend.available) { remove(address); return }
        if (records[address]?.let { it.session != session } == true) remove(address)
        val record = records[address]?.takeIf { it.session == session } ?: Record(session, AppleConversationLease()).also {
            if (repeatedStart) it.lease.invalidate()
            records[address] = it
        }
        if (!listen()) { clear(); return }
        val routed = backend.normalMode && runCatching { backend.players(address).isNotEmpty() }.getOrDefault(false)
        record.lease.report(activity.supplementaryActivity, backend.now(), routed)
        reconcile()
    }

    fun remove(address: String) { records.remove(address); reconcile() }

    fun clear() {
        records.clear()
        reconcile()
    }

    private fun reconcile() {
        backend.cancel(deadline)
        val now = backend.now()
        val wanted = mutableMapOf<Pair<Int, Int>, Pair<AppleConversationAudio.Player, Long>>()
        records.forEach { (address, record) ->
            if (!backend.normalMode) record.lease.invalidate()
            if (record.lease.active(now)) {
                val players = runCatching { backend.players(address) }.getOrDefault(emptyList())
                if (players.isEmpty()) record.lease.invalidate()
                players.forEach { player -> wanted[player.id to player.uid] = player to (record.lease.deadline!! - now) }
            }
        }
        shaped.keys.filter { it !in wanted }.toList().forEach { key ->
            val player = shaped.remove(key)!!
            releasing[key] = player
            runCatching { backend.release(player) }.onFailure { runCatching { backend.terminate(player) } }
            backend.schedule({
                if (releasing[key] === player) {
                    releasing.remove(key)
                    runCatching { backend.terminate(player) }
                }
            }, 350)
        }
        wanted.forEach { (key, value) ->
            if (key !in shaped && value.second >= 600) {
                releasing.remove(key)?.let { runCatching { backend.terminate(it) } }
                runCatching { backend.duck(value.first, value.second) }.onSuccess { shaped[key] = value.first }
            }
        }
        records.values.mapNotNull { it.lease.deadline }.minOrNull()?.let {
            backend.schedule(deadline, (it - now).coerceAtLeast(1))
        }
        if (records.isEmpty()) stopListening()
    }

    private fun listen(): Boolean {
        if (listening) return true
        listening = true
        return runCatching {
            backend.listen(::reconcile)
        }.onFailure { stopListening() }.isSuccess
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { backend.stopListening() }
    }
}
