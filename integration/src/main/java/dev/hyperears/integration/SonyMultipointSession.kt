package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyMultipointCodec as Wire

/** GS evidence and a bounded, locally initiated confirmation transaction; no timer or polling. */
internal class SonyMultipointSession(private val v2: Boolean, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var slots = emptySet<Int>()
    private val capabilities = mutableMapOf<Int, Wire.Capability>()
    private var serial = 0L
    private data class Pending(val id: Long, val definition: SonyMultipointDefinition, val target: Boolean, val deadline: Long)
    private var pending: Pending? = null
    var state = SonyMultipointFeatureState()
        private set
    private fun expire() {
        if (pending?.let { now() >= it.deadline } == true) { pending = null; state = state.copy(alert = null, pendingTransaction = null, pendingExpiresAtMillis = null) }
    }
    fun accept(payload: ByteArray): SonyDailyControlsSession.Update? {
        if (!v2) return null
        expire()
        Wire.slots(v2, payload)?.let { next ->
            if (next == slots) return null
            slots = next; capabilities.keys.retainAll(next)
            val old = state.definition
            reselect()
            if (old != state.definition) pending = null
            return SonyDailyControlsSession.Update(listOf(state), emptyList())
        }
        Wire.capability(payload)?.let { capability ->
            if (capability.slot !in slots) return null
            val old = state.definition
            capabilities[capability.slot] = capability
            reselect()
            val queries = if (state.definition != old) {
                pending = null
                state.definition?.slot?.let { listOf(Wire.bytes(0xD2, it), Wire.bytes(0xD6, it)) }.orEmpty()
            } else emptyList()
            return SonyDailyControlsSession.Update(listOf(state), queries)
        }
        val definition = state.definition ?: return null
        // Publish association metadata alongside reports, never as the confirmed setting value.
        state = state.copy(pendingTransaction = pending?.id, pendingExpiresAtMillis = pending?.deadline)
        Wire.status(payload)?.let { (slot, available) ->
            if (slot != definition.slot) return null
            state = state.copy(available = available)
            return SonyDailyControlsSession.Update(listOf(state), emptyList())
        }
        Wire.value(payload)?.let { (slot, enabled) ->
            if (slot != definition.slot) return null
            // SonyPods documents early target reports before a later confirmation alert.
            // Publish the report, but retain the bounded association until reply/expiry.
            state = state.copy(enabled = enabled)
            return SonyDailyControlsSession.Update(listOf(state), emptyList())
        }
        Wire.alert(payload)?.let { message ->
            val local = pending?.takeIf { it.definition == definition } ?: return null
            // LDAC-disable and enabling-with-LDAC belong only to an enable operation.
            if (message in setOf(6, 112) && !local.target) return null
            state = state.copy(alert = SonyMultipointAlert(local.id, message, local.target, local.deadline))
            return SonyDailyControlsSession.Update(listOf(state), emptyList())
        }
        return null
    }
    private fun reselect() {
        // Do not choose arbitrarily if two slots claim the same semantic setting.
        val capability = capabilities.values.filter { it.multipoint }.singleOrNull()
            ?.takeIf { capabilities.keys.containsAll(slots) }
        val definition = capability?.let { SonyMultipointDefinition(it.slot, it.title) }
        if (definition != state.definition) state = SonyMultipointFeatureState(definition)
    }
    fun reads(): List<ByteArray> = slots.sorted().map { Wire.bytes(0xD0, it, 1) } +
        state.definition?.slot?.let { listOf(Wire.bytes(0xD2, it), Wire.bytes(0xD6, it)) }.orEmpty()
    fun encode(request: SonyControlRequest): List<ByteArray> {
        expire()
        if (!state.supports(request)) return emptyList()
        val slot = state.definition!!.slot
        return when (request) {
            is SonyControlRequest.SetMultipoint -> {
                if (pending != null || state.enabled == request.enabled || state.alert != null) return emptyList()
                pending = Pending(++serial, request.expected, request.enabled, now() + 30_000)
                listOf(Wire.bytes(0x94, 0, 0), Wire.bytes(0xD8, slot, 0, if (request.enabled) 0 else 1),
                    Wire.bytes(0xD6, slot), Wire.bytes(0xD2, slot))
            }
            is SonyControlRequest.ReplyMultipointAlert -> {
                val local = pending ?: return emptyList()
                if (local.id != request.expectedAlert.transaction || local.definition != request.expected) return emptyList()
                pending = null
                state = state.copy(alert = null, pendingTransaction = null, pendingExpiresAtMillis = null)
                listOf(Wire.bytes(0x98, 0, request.expectedAlert.message, if (request.confirm) 1 else 0),
                    Wire.bytes(0xD6, slot), Wire.bytes(0xD2, slot))
            }
            else -> emptyList()
        }
    }
}
