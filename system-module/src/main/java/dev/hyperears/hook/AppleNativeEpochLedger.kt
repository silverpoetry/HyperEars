package dev.hyperears.hook

import java.util.UUID

/** Native HFP lifetime, deliberately independent from A2DP registration and presentation tokens. */
internal class AppleNativeEpochLedger {
    data class Epoch(val id: String, val owner: Any)
    data class Work(val address: String, val epoch: Epoch, val queuedAt: Long)
    data class Decision(val work: Work, val accepted: Boolean)
    private val epochs = mutableMapOf<String, Epoch>()
    private val decisions = mutableMapOf<String, Decision>()
    private val latestQueued = mutableMapOf<String, Long>()
    private var producerStartedAt = 0L
    fun queued(address: String, owner: Any, queuedAt: Long = 0): Work {
        if (epochs[address]?.owner?.let { it !== owner } == true) invalidate(address)
        latestQueued[address] = maxOf(latestQueued[address] ?: 0, queuedAt)
        return Work(address, epochs.getOrPut(address) { Epoch(UUID.randomUUID().toString(), owner) }, queuedAt)
    }
    fun current(work: Work): Boolean = epochs[work.address] == work.epoch && work.queuedAt >= producerStartedAt
    fun observe(work: Work, accepted: Boolean): Decision? {
        if (!current(work)) return null
        return Decision(work, accepted || decisions[work.address]?.accepted == true)
            .also { decisions[work.address] = it }
    }
    fun decision(address: String): Decision? = decisions[address]
    fun invalidate(address: String) { epochs.remove(address); decisions.remove(address); latestQueued.remove(address) }
    fun replaceProducer(startedAt: Long, live: (Work) -> Boolean) {
        producerStartedAt = startedAt
        epochs.toMap().forEach { (address, epoch) ->
            val work = Work(address, epoch, latestQueued[address] ?: 0)
            if (work.queuedAt < startedAt || !live(work)) invalidate(address)
        }
        decisions.entries.removeAll { !current(it.value.work) }
    }
    fun clear() { epochs.clear(); decisions.clear(); latestQueued.clear(); producerStartedAt = 0 }
}
