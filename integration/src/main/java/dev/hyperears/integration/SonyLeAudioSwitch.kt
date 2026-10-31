package dev.hyperears.integration

/** Session-bound switch choreography. The Android owner schedules one deadline and performs effects. */
class SonyLeAudioSwitch(private val timeoutMs: Long = 15_000) {
    data class Ticket(val session: String, val sequence: Long)
    sealed interface Effect {
        val ticket: Ticket
        data class Profile(override val ticket: Ticket) : Effect
        data class History(override val ticket: Ticket) : Effect
        data class Write(override val ticket: Ticket, val enabled: Boolean) : Effect
        data class Complete(override val ticket: Ticket, val enabled: Boolean, val pairingRequired: Boolean) : Effect
        data class Failed(override val ticket: Ticket, val reason: String) : Effect
    }
    private enum class Stage { PROFILE, HISTORY, NOTIFICATION }
    private data class Pending(val ticket: Ticket, val enabled: Boolean, val deadline: Long,
        var stage: Stage = Stage.PROFILE, var alreadyPaired: Boolean = false)
    private var sequence = 0L
    private var pending: Pending? = null
    fun begin(session: String, enabled: Boolean, now: Long): Effect.Profile? {
        if (session.isBlank() || pending != null) return null
        val ticket = Ticket(session, ++sequence)
        pending = Pending(ticket, enabled, now + timeoutMs)
        return Effect.Profile(ticket)
    }
    fun profile(ticket: Ticket, supported: Boolean, alreadyPaired: Boolean, now: Long): Effect? {
        val current = current(ticket, Stage.PROFILE) ?: return null
        expired(current, now)?.let { return it }
        if (!supported) return fail(current, "LE Audio profile unavailable")
        current.alreadyPaired = alreadyPaired
        current.stage = if (current.enabled) Stage.HISTORY else Stage.NOTIFICATION
        return if (current.enabled) Effect.History(ticket) else Effect.Write(ticket, false)
    }
    fun history(ticket: Ticket, history: Int?, now: Long): Effect? {
        val current = current(ticket, Stage.HISTORY) ?: return null
        expired(current, now)?.let { return it }
        if (history !in 0..2) return null
        current.stage = Stage.NOTIFICATION
        return Effect.Write(ticket, true)
    }
    fun setting(ticket: Ticket, enabled: Boolean?, notification: Boolean, now: Long): Effect? {
        val current = current(ticket, Stage.NOTIFICATION) ?: return null
        expired(current, now)?.let { return it }
        if (!notification || enabled != current.enabled) return null
        pending = null
        return Effect.Complete(ticket, current.enabled, current.enabled && !current.alreadyPaired)
    }
    fun failed(ticket: Ticket, reason: String): Effect? {
        val current = pending?.takeIf { it.ticket == ticket } ?: return null
        return fail(current, reason)
    }
    fun timeout(ticket: Ticket, now: Long): Effect? = pending?.takeIf { it.ticket == ticket }?.let { expired(it, now) }
    fun cancel(session: String) { if (pending?.ticket?.session == session) pending = null }
    private fun current(ticket: Ticket, stage: Stage) = pending?.takeIf { it.ticket == ticket && it.stage == stage }
    private fun expired(current: Pending, now: Long): Effect.Failed? = if (now >= current.deadline) fail(current, "Timed out waiting for device confirmation") else null
    private fun fail(current: Pending, reason: String): Effect.Failed { pending = null; return Effect.Failed(current.ticket, reason) }
}
