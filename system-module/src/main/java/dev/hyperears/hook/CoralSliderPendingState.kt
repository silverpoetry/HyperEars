package dev.hyperears.hook

/** Main-thread-owned state machine. No Android dependencies, timers, transport or optimistic reports. */
internal class CoralSliderPendingState<K>(private val timeoutMillis: Long = 2500L) {
    init { require(timeoutMillis > 0) }

    data class Lease<K>(val key: K, val id: Long, val token: String, val deadlineMillis: Long, val target: Int)
    data class Projection<K>(
        val value: Int,
        val enabled: Boolean,
        val pending: Lease<K>? = null,
        /** One-shot event: show a failure toast when non-null. Do not store and replay this projection. */
        val timedOut: Lease<K>? = null,
    )
    private data class StaleGuard(val oldValue: Int, val deadlineMillis: Long)
    private data class Entry<K>(var confirmed: Int, var pending: Lease<K>? = null, var staleGuard: StaleGuard? = null)
    private val entries = mutableMapOf<K, Entry<K>>()
    private var token: String? = null
    private var nextId = 0L

    /** Call on session loss, page close or token replacement. IDs deliberately never reset. */
    fun reset(token: String? = null) {
        this.token = token
        entries.clear()
    }

    private fun session(value: String) {
        require(value.isNotBlank())
        if (token != value) reset(value)
    }

    /** Submit only on a committed drag, never a preview/cancel. Cancel this lease if dispatch fails. */
    fun submit(key: K, oldValue: Int, targetValue: Int, token: String, now: Long): Lease<K>? {
        require(now >= 0)
        session(token)
        if (oldValue == targetValue) return null
        val entry = entries.getOrPut(key) { Entry(oldValue) }
        entry.staleGuard = null
        if (entry.pending == null) entry.confirmed = oldValue
        check(nextId < Long.MAX_VALUE)
        val deadline = if (now > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else now + timeoutMillis
        return Lease(key, ++nextId, token, deadline, targetValue).also { entry.pending = it }
    }

    /** Pass the actual device report. Non-target reports during the lease never replace confirmed/target. */
    fun project(key: K, reportedValue: Int, token: String, now: Long): Projection<K> {
        require(now >= 0)
        session(token)
        val entry = entries.getOrPut(key) { Entry(reportedValue) }
        val pending = entry.pending
        if (pending != null) {
            if (now >= pending.deadlineMillis) return timeout(entry, pending)
            if (reportedValue != pending.target) return Projection(pending.target, false, pending)
            // Some devices deliver an older snapshot after acknowledging the write. Retain
            // only this request's old value until its original deadline, without a new timer.
            entry.staleGuard = StaleGuard(entry.confirmed, pending.deadlineMillis)
            entry.pending = null
        }
        entry.staleGuard?.let { guard ->
            if (now >= guard.deadlineMillis) entry.staleGuard = null
            else if (reportedValue == guard.oldValue) return Projection(entry.confirmed, true)
        }
        entry.confirmed = reportedValue
        return Projection(entry.confirmed, true)
    }

    /** A callback may arrive after confirmation, replacement, cancellation or a different session. */
    fun expire(lease: Lease<K>, token: String, now: Long): Projection<K>? {
        if (this.token != token || lease.token != token || now < lease.deadlineMillis) return null
        val entry = entries[lease.key]?.takeIf { it.pending == lease } ?: return null
        return timeout(entry, lease)
    }

    /** Failed dispatch / explicitly abandoned submitted operation; caller owns any immediate error toast. */
    fun cancel(lease: Lease<K>, token: String): Projection<K>? {
        if (this.token != token || lease.token != token) return null
        val entry = entries[lease.key]?.takeIf { it.pending == lease } ?: return null
        entry.pending = null
        return Projection(entry.confirmed, true)
    }

    private fun timeout(entry: Entry<K>, lease: Lease<K>): Projection<K> {
        entry.pending = null
        return Projection(entry.confirmed, true, timedOut = lease)
    }
}
