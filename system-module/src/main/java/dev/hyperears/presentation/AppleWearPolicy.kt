package dev.hyperears.presentation

/** Host-only ownership policy. Protocol telemetry never directly dispatches media commands. */
internal class AppleWearPolicy {
    data class Player(val token: Any, val uid: Int, val playerId: Int)
    data class Sample(val session: String, val count: Int, val player: Player?,
        val playing: Boolean, val eligible: Boolean)
    enum class Action { PAUSE, PLAY }
    private var previous: Sample? = null
    private var owner: Player? = null
    private var acknowledged = false
    private var awaitingIssue = false
    var deadline: Long? = null
        private set
    private var resumeDeadline = 0L

    fun clear() {
        previous = null
        release()
    }

    private fun release() {
        owner = null; acknowledged = false; awaitingIssue = false; deadline = null
    }

    fun issued(success: Boolean, now: Long) {
        if (!awaitingIssue) return
        awaitingIssue = false
        if (!success) { release(); return }
        deadline = now + 1_500
        resumeDeadline = now + 300_000
    }

    fun update(sample: Sample, now: Long): Action? {
        val old = previous
        previous = sample
        if (!sample.eligible || sample.player == null || sample.count !in 0..2) {
            release(); previous = null; return null
        }
        if (old == null || old.session != sample.session) { release(); return null }
        if (owner != null && (owner != sample.player || deadline?.let { now >= it } == true)) release()
        if (owner != null && !awaitingIssue) {
            if (!acknowledged && !sample.playing) {
                acknowledged = true
                deadline = resumeDeadline
            } else if (acknowledged && sample.playing) release()
        }
        if (sample.count > old.count) {
            val resume = owner == sample.player && acknowledged && !sample.playing
            release()
            return if (resume) Action.PLAY else null
        }
        if (sample.count < old.count && sample.playing && owner == null) {
            owner = sample.player; awaitingIssue = true
            return Action.PAUSE
        }
        return null
    }
}
