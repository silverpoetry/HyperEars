package dev.hyperears.presentation

/** Serialized host lifecycle; the scheduler replaces one deadline, never starts a poll. */
internal class AppleWearCoordinator(
    private val host: Host, private val now: () -> Long, private val schedule: (Long?) -> Unit,
) {
    data class Media(val player: AppleWearPolicy.Player, val playing: Boolean)
    interface Host {
        fun start(changed: () -> Unit): Boolean
        fun stop()
        fun media(address: String): Media?
        fun execute(player: AppleWearPolicy.Player, action: AppleWearPolicy.Action): Boolean
    }
    private data class Entry(var session: String, var count: Int, val policy: AppleWearPolicy)
    private val entries = linkedMapOf<String, Entry>()
    private var started = false
    private var generation = 0

    fun update(address: String, session: String, count: Int) {
        entries.getOrPut(address) { Entry(session, count, AppleWearPolicy()) }.also {
            it.session = session; it.count = count
        }
        if (!started) {
            val epoch = ++generation
            started = host.start { if (epoch == generation) refresh() }
        }
        refresh()
    }

    fun refresh() {
        if (!started) return
        val time = now()
        entries.forEach { (address, entry) ->
            val media = host.media(address)
            val action = entry.policy.update(AppleWearPolicy.Sample(entry.session, entry.count,
                media?.player, media?.playing == true, media != null), time)
            if (action != null && media != null) {
                val success = host.execute(media.player, action)
                if (action == AppleWearPolicy.Action.PAUSE) entry.policy.issued(success, time)
            }
        }
        schedule(entries.values.mapNotNull { it.policy.deadline }.minOrNull())
    }

    fun remove(address: String) {
        entries.remove(address)?.policy?.clear()
        if (entries.isEmpty()) clear() else refresh()
    }

    fun clear() {
        generation++
        entries.clear()
        schedule(null)
        if (started) host.stop()
        started = false
    }
}
