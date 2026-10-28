package dev.hyperears.presentation

import dev.hyperears.integration.AppleConversationActivity
import dev.hyperears.integration.AppleConversationFeatureState
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.settings.ConnectionVolumePreset
import dev.hyperears.settings.ModuleSettings
import java.util.Locale

internal interface ConnectionVolumeHost {
    fun now(): Long
    fun currentVolume(): Int?
    fun normalMode(): Boolean
    fun start(changed: () -> Unit): Boolean
    fun stop()
    fun routeMatches(address: String): Boolean
    fun apply(address: String, percent: Int, expectedVolume: Int, valid: () -> Boolean): Boolean
    fun schedule(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

/** Main-thread, one opportunity per physical connection. Consumed history survives all cancellation. */
internal class ConnectionVolumeRuntime(
    private val host: ConnectionVolumeHost,
    private val producerAlive: (Any) -> Boolean = { true },
    private val settings: () -> ModuleSettings,
) {
    private data class Scope(val connection: HeadsetConnection, val producer: Any)
    private data class Record(val scope: Scope, val session: String, val state: EarbudState, val metadata: NativePresentationMetadata)
    private data class Pending(val scope: Scope, val session: String, val preset: ConnectionVolumePreset, val volume: Int, val deadline: Long)
    private val history = linkedMapOf<String, Scope>()
    private val current = mutableMapOf<String, Record>()
    private val pending = linkedMapOf<String, Pending>()
    private var listening = false
    private var reconciling = false
    private val deadline = Runnable { reconcile() }

    fun update(state: EarbudState, session: String, metadata: NativePresentationMetadata, producer: Any) {
        val address = state.address?.uppercase(Locale.ROOT)?.takeIf { MAC.matches(it) } ?: return
        val scope = Scope(metadata.connection, producer)
        val previous = history[address]
        if (previous != null && scope.connection.connectedAtElapsedMs < previous.connection.connectedAtElapsedMs) return
        val record = Record(scope, session, state, metadata)
        current[address] = record
        if (previous != scope) {
            // Keep all admitted identities bounded without evicting a live duplicate-suppression guard.
            if (previous == null && history.size >= MAX_ADDRESSES) { current.remove(address); return }
            history[address] = scope
            pending.remove(address)
            val config = settings()
            val now = host.now()
            val end = metadata.connection.connectedAtElapsedMs + WINDOW_MS
            if (!metadata.replay && config.connectionVolume.percent != null && eligible(record, config, allowUnknown = true) &&
                now >= metadata.connection.connectedAtElapsedMs && now < end) {
                val volume = runCatching { host.currentVolume() }.getOrNull()
                if (volume != null && volume >= 0) pending[address] = Pending(scope, session, config.connectionVolume, volume, end)
            }
        }
        reconcile()
    }

    fun settingsChanged() { pending.clear(); reconcile() }
    fun remove(address: String) { val key = address.uppercase(Locale.ROOT); current.remove(key); pending.remove(key); reconcile() }
    fun clear() { current.clear(); pending.clear(); reconcile() }

    private fun eligible(record: Record, config: ModuleSettings, allowUnknown: Boolean): Boolean =
        !config.modulePaused && producerAlive(record.scope.producer) && record.state.adapter?.id !in config.disabledAdapterIds && record.state.sessionActive && record.state.lifecycle.systemProfile == SystemProfileState.CONNECTED &&
            record.state.lifecycle.controlOwnership == ControlOwnership.MODULE && record.metadata.admission != NativeAdmission.SYSTEM &&
            (allowUnknown || record.metadata.admission == NativeAdmission.HYPEREARS) &&
            record.state.features.get<AppleConversationFeatureState>()?.supplementaryActivity != AppleConversationActivity.START

    private fun valid(address: String, attempt: Pending, allowUnknown: Boolean): Boolean {
        val record = current[address] ?: return false
        val config = settings()
        return record.scope == attempt.scope && record.session == attempt.session && config.connectionVolume == attempt.preset &&
            host.now() < attempt.deadline && eligible(record, config, allowUnknown)
    }

    private fun reconcile() {
        if (reconciling) return
        reconciling = true
        try {
            host.cancel(deadline)
            if (pending.isNotEmpty() && !listening) {
                listening = true
                if (!runCatching { host.start(::reconcile) }.getOrDefault(false)) pending.clear()
            }
            pending.toList().forEach { (address, attempt) ->
                val safe = runCatching {
                    valid(address, attempt, allowUnknown = true) && host.normalMode() && host.currentVolume() == attempt.volume
                }.getOrDefault(false)
                if (!safe) { pending.remove(address); return@forEach }
                if (!runCatching { valid(address, attempt, allowUnknown = false) && host.routeMatches(address) }.getOrDefault(false)) return@forEach
                // Consume before the host call, including exceptions or reentrant callbacks. Never retry a write.
                pending.remove(address)
                runCatching {
                    host.apply(address, requireNotNull(attempt.preset.percent), attempt.volume) {
                        valid(address, attempt, allowUnknown = false)
                    }
                }
            }
            if (pending.isEmpty()) {
                if (listening) { listening = false; runCatching { host.stop() } }
            } else host.schedule(deadline, (pending.values.minOf { it.deadline } - host.now()).coerceAtLeast(1))
        } finally { reconciling = false }
    }

    companion object {
        const val WINDOW_MS = 10_000L
        private const val MAX_ADDRESSES = 128
        private val MAC = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    }
}
