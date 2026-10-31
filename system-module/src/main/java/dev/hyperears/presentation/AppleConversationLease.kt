package dev.hyperears.presentation

import dev.hyperears.integration.AppleConversationActivity

/** One absolute, non-renewable conversation envelope per accepted activity episode. */
internal class AppleConversationLease {
    var deadline: Long? = null
        private set
    private var latched = false

    fun report(activity: AppleConversationActivity, now: Long, eligible: Boolean): Boolean {
        if (!eligible) {
            deadline = null
            // A cached START must not re-arm after a route/call change.
            latched = activity == AppleConversationActivity.START
            return false
        }
        when (activity) {
            AppleConversationActivity.START -> if (!latched) {
                latched = true
                deadline = now + MAX_DURATION_MS
            }
            AppleConversationActivity.RELEASE -> { deadline = null; latched = false }
            AppleConversationActivity.UNKNOWN -> Unit
        }
        return active(now)
    }

    fun active(now: Long): Boolean {
        if (deadline?.let { now >= it } == true) deadline = null
        return deadline != null
    }

    fun invalidate() { deadline = null; latched = true }
    companion object { const val MAX_DURATION_MS = 60_000L }
}

/** Consumed activity survives eligibility loss; replaying a state snapshot is not a new episode. */
internal class AppleConversationHistory {
    private val seen = linkedMapOf<String, Pair<String, AppleConversationActivity>>()
    fun consume(address: String, session: String, activity: AppleConversationActivity): Boolean {
        val previous = seen[address]
        // Unknown vendor statuses carry no new episode boundary.
        if (activity != AppleConversationActivity.UNKNOWN) {
            seen[address] = session to activity
            while (seen.size > 128) seen.remove(seen.keys.first())
        }
        return previous?.first == session && previous.second == AppleConversationActivity.START &&
            activity == AppleConversationActivity.START
    }
}
