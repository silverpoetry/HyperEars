package dev.hyperears.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A detached cleanup owner keeps restoration ahead of physical close, with a real I/O deadline. */
internal suspend fun closeOwnedChannel(
    channel: EarbudChannel?,
    mutex: Mutex,
    prepare: suspend () -> List<ByteArray>,
    finished: () -> Unit,
    timeoutMs: Long = 1_500,
) {
    try {
        if (channel == null) {
            withTimeout(timeoutMs) { mutex.withLock { prepare() } }
        } else {
            // The physical-close watchdog must survive a socket write that ignores interrupts.
            // A surrounding withTimeout would cancel that watchdog before it can close the socket.
            channel.withIoDeadline(timeoutMs, "host ownership restoration") {
                mutex.withLock {
                    prepare().forEach { write(it) }
                }
            }
        }
    } finally {
        channel?.close()
        finished()
    }
}
