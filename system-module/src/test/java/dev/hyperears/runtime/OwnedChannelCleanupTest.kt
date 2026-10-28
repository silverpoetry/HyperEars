package dev.hyperears.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class OwnedChannelCleanupTest {
    @Test fun protocolWorkflowCancellationIsDrainedOnceBeforePhysicalClose() = runBlocking {
        val events = mutableListOf<String>()
        val adapter = object : dev.hyperears.integration.StandardEarbudAdapter() {
            var owned = true
            override fun prepareProtocolRelease(): List<ByteArray> =
                if (owned) { owned = false; listOf(byteArrayOf(0)) } else emptyList()
        }
        closeOwnedChannel(Channel(events), Mutex(), adapter::prepareProtocolRelease,
            { events.add("reset"); adapter.resetProtocolSession() })
        assertEquals(listOf("write:0", "close", "reset"), events)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
    }
    private class Channel(private val events: MutableList<String>) : EarbudChannel {
        override val endpointId = "fixture"
        var blocking = false
        val released = CompletableDeferred<Unit>()
        override suspend fun connect() = Unit
        override suspend fun read(buffer: ByteArray): Int = -1
        override suspend fun write(bytes: ByteArray) {
            events.add("write:${bytes.single()}")
            if (blocking) released.await()
        }
        override fun close() { if (!released.isCompleted) events.add("close"); released.complete(Unit) }
    }

    @Test fun cancelsProducersThenRestoresBeforeCloseAndAcknowledgement() = runBlocking {
        val events = mutableListOf<String>()
        val channel = Channel(events)
        events.add("cancel producers")
        closeOwnedChannel(channel, Mutex(), prepare = {
            events.add("stop hosts")
            listOf(byteArrayOf(8), byteArrayOf(6))
        }, finished = { events.add("reset") })
        events.add("acknowledge yield")
        assertEquals(listOf("cancel producers", "stop hosts", "write:8", "write:6", "close", "reset", "acknowledge yield"), events)
    }

    @Test fun failedRestorationStillClosesAndInvalidatesOldSession() = runBlocking {
        val events = mutableListOf<String>()
        var generation = 1
        val old = generation
        runCatching {
            closeOwnedChannel(Channel(events), Mutex(), prepare = { error("host failed") },
                finished = { generation++; events.add("reset") })
        }
        assertEquals(listOf("close", "reset"), events)
        assertNotEquals(old, generation)
    }

    @Test fun stalledWriteHasPhysicalDeadlineAndDoesNotKeepChannelOpen() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val channel = Channel(events).also { it.blocking = true }
        runCatching { closeOwnedChannel(channel, Mutex(), { listOf(byteArrayOf(8)) },
            { events.add("reset") }, timeoutMs = 50) }
        assertTrue(channel.released.isCompleted)
        assertTrue(events.indexOf("close") > events.indexOf("write:8"))
        assertEquals("reset", events.last())
    }

    @Test fun cleanupWaitsForInFlightTransactionAndNeverStartsOldQueuedControl() = runBlocking {
        val events = mutableListOf<String>(); val mutex = Mutex()
        var stopping = false
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val normal = launch { mutex.withLock { entered.complete(Unit); finish.await(); events.add("inflight done") } }
        entered.await()
        stopping = true
        val oldQueued = launch { mutex.withLock { if (!stopping) events.add("stale control") } }
        val cleanup = launch { closeOwnedChannel(Channel(events), mutex, { listOf(byteArrayOf(8)) }, { events.add("reset") }) }
        yield(); assertTrue(events.isEmpty())
        finish.complete(Unit)
        joinAll(normal, oldQueued, cleanup)
        assertEquals(listOf("inflight done", "write:8", "close", "reset"), events)
    }
}
