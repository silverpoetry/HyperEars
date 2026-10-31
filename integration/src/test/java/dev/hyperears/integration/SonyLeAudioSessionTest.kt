package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class SonyLeAudioSessionTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun discoveryReadbacksRevocationAndResetAreSessionScoped() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM6", standardHeadset = true)))
        fun send(payload: ByteArray, table: Wire.MessageType = Wire.MessageType.COMMAND_1) = adapter.receive(Wire.encode(table, 0, payload))
        fun state() = adapter.runtimeState().features.get<SonyLeAudioFeatureState>()
        adapter.beginHandshake()
        send(b(1,0,3,0,0,0,0,0))
        send(b(0x49,12,0))
        assertNull(state()) // unsolicited settings do not grant a capability
        send(b(7,0,2,0x41,0,0x42,1), Wire.MessageType.COMMAND_2)
        assertNull(state())
        send(b(7,0,2,0x41,0,0x42,1))
        assertTrue(state()!!.supported)
        assertNull(state()!!.enabled)
        send(b(0x41,1) + "AA:BB:CC:DD:EE:01AA:BB:CC:DD:EE:02".encodeToByteArray())
        send(b(0x43,12,0))
        send(b(0x47,12,1))
        assertEquals(false, state()!!.enabled)
        assertEquals(0L, state()!!.notificationSequence)
        send(b(0x49,12,0))
        assertEquals(true, state()!!.enabled)
        assertEquals(1L, state()!!.notificationSequence)
        send(b(0x47,12,1)) // later query must not rewrite last notification evidence
        assertEquals(true, state()!!.notifiedEnabled)
        assertEquals(1L, state()!!.notificationSequence)
        send(b(0x47,1,0))
        val value = state()!!
        assertEquals(listOf("AA:BB:CC:DD:EE:02"), value.leAddresses)
        assertEquals(0, value.pairedHistory)
        assertEquals(value, FeatureStateTransport.decode(FeatureStateTransport.encode(DeviceFeatureSnapshot(listOf(value))))?.get<SonyLeAudioFeatureState>())
        send(b(7,0,0))
        assertFalse(state()!!.supported)
        assertNull(state()!!.classicAddress)
        send(b(0x49,12,0))
        assertNull(state()!!.enabled)
        adapter.resetProtocolSession()
        assertNull(state())
        adapter.beginHandshake()
        send(b(1,0,3,0,0,0,0,0))
        send(b(7,0,2,0x41,0,0x42,1))
        assertNull(state()!!.classicAddress)
        assertEquals(0L, state()!!.notificationSequence)
    }
    @Test fun alertsStayInTheirSessionAndDoNotAutoConfirm() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM6", standardHeadset = true)))
        fun send(payload: ByteArray) = adapter.receive(Wire.encode(Wire.MessageType.COMMAND_1,0,payload))
        fun state() = adapter.runtimeState().features.get<SonyLeAudioFeatureState>()
        adapter.beginHandshake()
        send(b(1,0,3,0,0,0,0,0))
        send(b(7,0,2,0x41,0,0x42,1))
        val result = send(b(0x99,6,13,2,1,7,0))
        assertEquals(listOf(1,7),state()!!.alert!!.items)
        assertEquals(1L,state()!!.alertSequence)
        result.commands.forEach { bytes -> Wire.Decoder().offer(bytes).forEach {
            assertFalse(it.payload.firstOrNull() == 0x98.toByte())
        } }
        val value = state()!!
        assertEquals(value,FeatureStateTransport.decode(FeatureStateTransport.encode(DeviceFeatureSnapshot(listOf(value))))?.get<SonyLeAudioFeatureState>())
        send(b(0x47,12,0))
        assertNotNull(state()!!.alert)
        send(b(0x49,12,255))
        assertNotNull(state()!!.alert)
        send(b(0x49,12,0))
        assertNotNull(state()!!.alert) // settings alone do not acknowledge a device alert
        send(b(0x99,0,45,0))
        assertEquals(2L,state()!!.alertSequence)
        send(b(7,0,0))
        assertNull(state()!!.alert)
        adapter.resetProtocolSession()
        assertNull(state())
    }
    @Test fun discoveryQueriesAreQueuedOnceAndNeverChangeConnection() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "WH-1000XM6", standardHeadset = true)))
        val payloads = mutableListOf<List<Int>>()
        fun receive(type: Wire.MessageType, payload: ByteArray, sequence: Int = 0) {
            adapter.receive(Wire.encode(type, sequence, payload)).commands.forEach { bytes ->
                Wire.Decoder().offer(bytes).filter { it.type == Wire.MessageType.COMMAND_1 }.forEach { frame ->
                    payloads += frame.payload.map { it.toInt() and 255 }
                }
            }
        }
        adapter.beginHandshake()
        receive(Wire.MessageType.COMMAND_1, b(1,0,3,0,0,0,0,0))
        repeat(2) { receive(Wire.MessageType.COMMAND_1, b(7,0,2,0x41,0,0x42,1)) }
        repeat(80) { receive(Wire.MessageType.ACK, byteArrayOf(), (it + 1) % 2) }
        for (query in listOf(listOf(0x40,1),listOf(0x42,12),listOf(0x46,12),listOf(0x46,1))) {
            assertEquals("query $query emitted once", 1, payloads.count { it == query })
        }
        assertFalse(payloads.any { it.firstOrNull() == 0x48 })
    }
    @Test fun otherModelsAndV1DoNotAdmitHbsV2Reports() {
        for ((name,v2) in listOf("WH-1000XM4" to true, "WH-1000XM6" to false)) {
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = name, standardHeadset = true)))
            adapter.beginHandshake()
            adapter.receive(Wire.encode(Wire.MessageType.COMMAND_1,0,if(v2)b(1,0,3,0,0,0,0,0)else b(1,0,1,0)))
            adapter.receive(Wire.encode(Wire.MessageType.COMMAND_1,0,b(7,0,2,0x41,0,0x42,1)))
            assertNull(adapter.runtimeState().features.get<SonyLeAudioFeatureState>())
        }
    }
}
