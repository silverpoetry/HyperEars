package dev.hyperears.integration

/** Tracks only a locally written action, never a remotely observed ringing state. */
internal class OwnedProtocolCleanup(private val key: String, private val stop: () -> List<ByteArray>) {
    private var generation = 0L
    private var active: Long? = null
    fun written(start: Boolean, scope: AdapterEventScope) {
        if (start) {
            val lease = ++generation
            active = lease
            scope.scheduleProtocolCleanup(key, lease, 30_000)
        } else reportedStopped(scope)
    }
    fun reportedStopped(scope: AdapterEventScope) {
        active = null
        scope.cancelProtocolCleanup(key)
    }
    fun expire(expectedKey: String, lease: Long): List<ByteArray> =
        if (expectedKey == key && active == lease) release() else emptyList()
    fun release(): List<ByteArray> {
        if (active == null) return emptyList()
        active = null
        return stop()
    }
    fun reset() { active = null }
}
