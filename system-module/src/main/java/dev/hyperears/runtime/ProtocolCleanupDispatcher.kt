package dev.hyperears.runtime

import java.io.Closeable
import kotlinx.coroutines.*

/** One-shot cancellation deadlines; transport ownership is rechecked by the caller under its lock. */
internal class ProtocolCleanupDispatcher(private val scope: CoroutineScope) : Closeable {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun schedule(key: String, delayMs: Long, expire: suspend () -> Unit, failed: (Throwable) -> Unit) {
        require(key.isNotBlank() && delayMs in 1..60_000)
        val job = scope.launch(start = CoroutineStart.LAZY) { delay(delayMs); expire() }
        job.invokeOnCompletion { error ->
            synchronized(lock) { if (jobs[key] === job) jobs.remove(key) }
            if (error != null && error !is CancellationException) failed(error)
        }
        synchronized(lock) { jobs.put(key, job) }?.cancel()
        job.start()
    }
    fun cancel(key: String) { synchronized(lock) { jobs.remove(key) }?.cancel() }
    fun cancelAll() {
        val pending = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach { it.cancel() }
    }
    override fun close() = cancelAll()
}
