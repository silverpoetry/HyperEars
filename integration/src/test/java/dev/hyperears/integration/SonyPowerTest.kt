package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyPowerTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun powerOffRequiresDiscoveryIdleQueueAndCurrentVersion() {
        for (v2 in listOf(false, true)) {
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM6", standardHeadset = true)))
            val request = SonyControlRequest.PowerOff(if (v2) 2 else 1)
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            assertFalse(adapter.supportsControl(request))
            adapter.beginHandshake()
            fun report(table: Wire.MessageType, payload: ByteArray) = adapter.receive(Wire.encode(table, 0, payload))
            report(Wire.MessageType.COMMAND_1, if (v2) b(1,0,3,0,0,0,0,0) else b(1,0,1,0))
            val capability = if (v2) b(7,0,1,0x23,0) else b(7,0,1,0x21)
            report(Wire.MessageType.COMMAND_2, capability)
            assertFalse(adapter.supportsControl(request))
            report(Wire.MessageType.COMMAND_1, capability)
            assertTrue(adapter.supportsControl(request))
            assertFalse(adapter.executeControl(request).accepted)
            var ack = 1
            repeat(60) {
                val drained = adapter.receive(Wire.encode(Wire.MessageType.ACK, ack)); ack = 1 - ack
                drained.commands.forEach { bytes -> Wire.Decoder().offer(bytes).forEach { frame ->
                    assertFalse(frame.payload.contentEquals(if (v2) b(0x24,3,1) else b(0x22,0,1)))
                } }
            }
            assertFalse(adapter.supportsControl(request.copy(expectedVersion = if (v2) 1 else 2)))
            val sent = adapter.executeControl(request)
            assertTrue(sent.accepted)
            val frame = Wire.Decoder().offer(sent.commands.single()).single()
            assertEquals(Wire.MessageType.COMMAND_1, frame.type)
            assertArrayEquals(if (v2) b(0x24,3,1) else b(0x22,0,1), frame.payload)
            report(Wire.MessageType.COMMAND_1, b(7,0,0))
            assertFalse(adapter.supportsControl(request))
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<SonyPowerFeatureState>())
        }
    }
}
