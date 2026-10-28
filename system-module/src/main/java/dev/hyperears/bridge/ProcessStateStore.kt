package dev.hyperears.bridge

import android.content.Intent
import dev.hyperears.integration.EarbudState

object ProcessStateStore {
    private val registry = DeviceStateRegistry()
    private val observers = linkedMapOf<Any, Pair<String, (EarbudState) -> Unit>>()

    fun observe(address: String, callback: (EarbudState) -> Unit): () -> Unit {
        val key = Any()
        synchronized(observers) { observers[key] = address to callback }
        return { synchronized(observers) { observers.remove(key) }; Unit }
    }

    fun snapshot(): EarbudState = registry.primaryState()

    fun snapshot(address: String): EarbudState =
        registry.state(address) ?: EarbudState()

    fun find(address: String): EarbudState? = registry.state(address)

    fun sessionToken(address: String): String? = registry.token(address)

    fun knownSnapshot(address: String): EarbudState =
        registry.knownState(address) ?: EarbudState()

    fun accept(intent: Intent): EarbudState? {
        val incoming = with(ModuleContract) { intent.readState() } ?: return null
        val token = with(ModuleContract) { intent.readSessionToken() } ?: return null
        val accepted = registry.accept(incoming, token) ?: return null
        synchronized(observers) { observers.values.toList() }.forEach { (address, callback) ->
            if (accepted.address.equals(address, ignoreCase = true)) runCatching { callback(accepted) }
        }
        return accepted
    }

    fun contains(address: String): Boolean = registry.contains(address)

    fun containsKnown(address: String): Boolean = registry.containsKnown(address)

    fun snapshots(): List<EarbudState> = registry.states()

    fun clear() = registry.clear()
}
