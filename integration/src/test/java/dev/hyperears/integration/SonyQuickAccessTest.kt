package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyQuickAccessTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    @Test fun assignmentPreservesUnknownSiblingAndWaitsForReport() {
        val s=SonyDailyControlsSession(true)
        s.accept(b(7,0,1,0xFD,0))
        s.accept(b(0xF1,13,1,1,2,1,1,2,0,1,2,200,1,200))
        s.accept(b(0xF7,13,2,1,200));s.accept(b(0xF3,13,0))
        val state=s.quickAccessState()
        val request=SonyControlRequest.SetQuickAccess(state.definition!!,state.current!!,0,10)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertArrayEquals(b(0xF8,13,2,10,200),s.encode(request).first())
        assertEquals(listOf(1,200),s.quickAccessState().current)
        s.accept(b(0xF9,13,2,2,200));assertTrue(s.encode(request).isEmpty())
        s.accept(b(7,0,0));assertNull(s.quickAccessState().definition)
    }
    @Test fun adapterRequiresTableOneAndClearsFeatureOnReset() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)))
        adapter.beginHandshake()
        val t1=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_1
        val t2=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_2
        fun report(table:dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType,vararg p:Int)=
            adapter.receive(dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.encode(table,0,b(*p)))
        report(t1,1,0,3,0,0,0,0,0)
        report(t2,7,0,1,0xFD,0)
        assertNull(adapter.runtimeState().features.get<SonyQuickAccessFeatureState>())
        report(t1,7,0,1,0xFD,0)
        report(t1,0xF1,13,1,1,1,1,1,2,0,1)
        report(t1,0xF7,13,1,1);report(t1,0xF3,13,0)
        val state=adapter.runtimeState().features.get<SonyQuickAccessFeatureState>()!!
        val json=kotlinx.serialization.json.Json
        assertEquals(state,json.decodeFromString(DeviceFeatureState.serializer(),json.encodeToString(DeviceFeatureState.serializer(),state)))
        val request=SonyControlRequest.SetQuickAccess(state.definition!!,state.current!!,0,2)
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.executeControl(request).accepted) // existing ACK queue is still busy
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(request));assertNull(adapter.runtimeState().features.get<SonyQuickAccessFeatureState>())
    }
    @Test fun malformedUnadvertisedAndIncompleteStatesGrantNoWrites() {
        val s=SonyDailyControlsSession(true)
        assertNull(s.accept(b(0xF7,13,2,1,200)))
        s.accept(b(7,0,1,0xFD,0))
        s.accept(b(0xF1,13,1,1,2,1,1,2,0,1,2,200,1,200))
        s.accept(b(0xF7,13,2,1,200));s.accept(b(0xF3,13,0))
        val state=s.quickAccessState();val request=SonyControlRequest.SetQuickAccess(state.definition!!,state.current!!,0,10)
        s.accept(b(0xF3,13,1));assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF3,13,0));s.accept(b(0xF7,13,1,1))
        assertTrue(s.encode(request).isEmpty());assertNull(s.quickAccessState().current)
        assertFalse(state.supports(request.copy(service=199)))
    }
}
