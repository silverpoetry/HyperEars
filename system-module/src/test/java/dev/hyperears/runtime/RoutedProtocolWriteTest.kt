package dev.hyperears.runtime

import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.ProtocolPacket
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.integration.StandardEarbudAdapter
import dev.hyperears.integration.SwarmooMo3ProAdapter
import dev.hyperears.integration.VivoTwsAir3ProAdapter
import dev.hyperears.protocol.swarmoo.SwarmooMo3WireCodec
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RoutedProtocolWriteTest {
    @Test
    fun routedQueriesRemainOrderedAndPacedWithoutChangingBytes() = runBlocking {
        val adapter = SwarmooMo3ProAdapter()
        val writes = adapter.routeCommands(adapter.beginHandshake().commands)
        val actions = mutableListOf<String>()
        writeCommandSequence(writes, adapter.controlPolicy(StandardControlRequest.Refresh).commandGapMs,
            wait = { actions += "wait:$it" },
            write = { actions += "write:${it.route}"; assertArrayEquals(SwarmooMo3WireCodec.query, it.bytes) })
        assertEquals(listOf("write:null", "wait:300", "write:battery"), actions)
    }

    @Test
    fun knownGattErrorRemainsUnconfirmedAndOtherFailuresPropagate() = runBlocking {
        val packet = ProtocolPacket(SwarmooMo3WireCodec.query, "battery")
        val adapter = SwarmooMo3ProAdapter()
        val channel = FakeChannel(GattWriteException(1, "battery"))
        assertEquals(1, writeAdapterCommand(adapter, channel, packet)?.status)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        for (error in listOf(IOException("not submitted"), GattWriteException(133, "battery"), GattWriteException(1, null))) {
            try {
                writeAdapterCommand(adapter, FakeChannel(error), packet)
                fail("Unexpected success for $error")
            } catch (actual: IOException) { assertSame(error, actual) }
        }
        try {
            writeAdapterCommand(StandardEarbudAdapter(), channel, packet)
            fail("Standard adapter must reject GATT errors")
        } catch (actual: GattWriteException) { assertEquals(1, actual.status) }
    }

    @Test
    fun legacyProtocolsStillUsePrimaryRouteAndReceiveUnchangedBytes() = runBlocking {
        val adapter = VivoTwsAir3ProAdapter()
        val commands = adapter.beginHandshake().commands
        val writes = adapter.routeCommands(commands)
        assertEquals(commands.size, writes.size)
        writes.zip(commands).forEach { (packet, command) ->
            assertNull(packet.route)
            assertArrayEquals(command, packet.bytes)
            assertNull(writeAdapterCommand(adapter, FakeChannel(), packet))
        }
    }

    private class FakeChannel(private val failure: IOException? = null) : EarbudChannel {
        override val endpointId = "test"
        override suspend fun connect() = Unit
        override suspend fun read(buffer: ByteArray): ProtocolPacket? = null
        override suspend fun write(bytes: ByteArray, route: String?) { failure?.let { throw it } }
        override fun close() = Unit
    }
}
