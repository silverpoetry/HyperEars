package dev.hyperears.runtime

import dev.hyperears.integration.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PuddingHandshakeEffectsTest {
    @Test fun failedCandidateCancelsStartupFallbackBeforeTheNextCandidate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = StateRequestDispatcher(scope)
        val commands = AtomicInteger()
        val finished = CountDownLatch(1)
        val adapter = MoondropPuddingAdapter()
        val effect = adapter.beginHandshake().effects.single() as AdapterEffect.RequestState
        try {
            dispatcher.request(effect.featureId,effect.delayMs,
                { commands.addAndGet(adapter.queryState(effect.featureId).size) }, { throw AssertionError(it) })
            dispatcher.cancelAll("handshake candidate failed")
            dispatcher.request("next-candidate",effect.delayMs+50,{ finished.countDown() },{ throw AssertionError(it) })
            assertTrue(finished.await(2,TimeUnit.SECONDS))
            assertEquals(0,commands.get())
        } finally { dispatcher.close(); scope.cancel() }
    }
}
