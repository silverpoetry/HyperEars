package dev.hyperears.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class ProtocolCleanupDispatcherTest {
    @Test fun replacementAndCancellationNeverExpireOlderLeases() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = ProtocolCleanupDispatcher(scope)
        val completed = CompletableDeferred<Int>()
        try {
            dispatcher.schedule("fit", 60_000, { completed.complete(1) }, { completed.completeExceptionally(it) })
            dispatcher.schedule("fit", 1, { completed.complete(2) }, { completed.completeExceptionally(it) })
            assertEquals(2, withTimeout(2_000) { completed.await() })
            dispatcher.schedule("fit", 60_000, { error("cancelled deadline ran") }, { throw AssertionError(it) })
            dispatcher.cancel("fit")
            dispatcher.schedule("other", 60_000, { error("closed deadline ran") }, { throw AssertionError(it) })
            dispatcher.close()
        } finally { dispatcher.close(); scope.cancel() }
    }

    @Test fun cancellationWhileWaitingForTransactionDoesNotTouchReplacementChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = ProtocolCleanupDispatcher(scope)
        val mutex = Mutex(locked = true)
        val waiting = CompletableDeferred<Unit>()
        var sent = false
        try {
            dispatcher.schedule("fit", 1, {
                waiting.complete(Unit)
                mutex.withLock { currentCoroutineContext().ensureActive(); sent = true }
            }, { throw AssertionError(it) })
            withTimeout(2_000) { waiting.await() }
            dispatcher.cancelAll()
            mutex.unlock()
            val drained = CompletableDeferred<Unit>()
            dispatcher.schedule("barrier", 1, { mutex.withLock { drained.complete(Unit) } }, { drained.completeExceptionally(it) })
            withTimeout(2_000) { drained.await() }
            assertFalse(sent)
        } finally { dispatcher.close(); scope.cancel() }
    }

    @Test fun cleanupFailureIsReportedOnceAndDoesNotKillOtherDeadline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = ProtocolCleanupDispatcher(scope)
        val failed = CompletableDeferred<Throwable>()
        val other = CompletableDeferred<Unit>()
        try {
            dispatcher.schedule("bad", 1, { error("wire failure") }, { failed.complete(it) })
            dispatcher.schedule("other", 1, { other.complete(Unit) }, { other.completeExceptionally(it) })
            assertEquals("wire failure", withTimeout(2_000) { failed.await() }.message)
            withTimeout(2_000) { other.await() }
        } finally { dispatcher.close(); scope.cancel() }
    }
}
