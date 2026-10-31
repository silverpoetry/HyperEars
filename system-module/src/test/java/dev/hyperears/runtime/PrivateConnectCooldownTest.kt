package dev.hyperears.runtime

import dev.hyperears.integration.RfcommEndpointSpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PrivateConnectCooldownTest {
    private class Candidate : EarbudChannel {
        override val endpointId = "test"
        var connections = 0
        override suspend fun connect() { connections++ }
        override suspend fun read(buffer: ByteArray) = -1
        override suspend fun write(bytes: ByteArray) = Unit
        override fun close() = Unit
    }
    @Test fun cancellingSessionDuringCooldownNeverConnects() = runBlocking {
        val channel = Candidate()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.connectAfterCooldown(RfcommEndpointSpec.Channel(16, connectDelayMs = 2000)) { true }
        }
        assertEquals(0, channel.connections)
        job.cancelAndJoin()
        assertEquals(0, channel.connections)
    }
    @Test fun ownershipIsRecheckedAfterCooldown() = runBlocking {
        val channel = Candidate()
        var owned = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.connectAfterCooldown(RfcommEndpointSpec.Channel(16, connectDelayMs = 25)) { owned }
        }
        owned = false
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, channel.connections)
    }
    @Test fun defaultCandidatesConnectWithoutCooldown() = runBlocking {
        val channel = Candidate()
        channel.connectAfterCooldown(RfcommEndpointSpec.Channel(1)) { true }
        assertEquals(1, channel.connections)
    }
}
