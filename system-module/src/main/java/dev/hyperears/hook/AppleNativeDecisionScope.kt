package dev.hyperears.hook

/** Thread-confined evidence; an arbitrary provider gate is never admission evidence. */
internal class AppleNativeDecisionScope {
    class Frame(val address: String, val previous: Frame?) {
        var decision: Boolean? = null
    }
    private val local = ThreadLocal<Frame>()
    fun current(): Frame? = local.get()
    fun <T> observe(address: String, original: () -> T, observer: (Boolean) -> Unit): T {
        val frame = enter(address)
        var completed = false
        try { return original().also { completed = true } }
        finally {
            val decision = leave(frame, completed)
            if (decision != null) runCatching { observer(decision) }
        }
    }
    fun enter(address: String): Frame = Frame(address, local.get()).also(local::set)
    fun gate(address: String, accepted: Boolean, healthy: Boolean) {
        val frame = local.get()?.takeIf { it.address == address } ?: return
        if (accepted) frame.decision = true
        else if (healthy && frame.decision != true) frame.decision = false
    }
    fun leave(frame: Frame, completed: Boolean): Boolean? {
        check(local.get() === frame)
        if (frame.previous == null) local.remove() else local.set(frame.previous)
        return frame.decision?.takeIf { it || completed }
    }
}
