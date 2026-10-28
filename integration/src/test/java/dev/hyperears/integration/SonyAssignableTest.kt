package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyAssignableTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    private val cap=b(0xF1,6,2,1,0,32,2,32,1,0,32,16,1,0,17,0,1,0,1,0,1,0,1)
    private fun ready():SonyAssignableSession {
        val s=SonyAssignableSession(false)
        s.accept(b(7,0,1,0xF6));s.accept(cap);s.accept(b(0xF7,6,2,32,0));s.accept(b(0xF3,6,2,0,1))
        return s
    }
    @Test fun writesFullOrderedVectorWithReadbacksAndNoOptimism() {
        val s=ready();val initial=s.state
        val request=SonyControlRequest.SetAssignablePreset(initial.definition!!,initial.current!!,0,16)
        assertTrue(initial.supports(request))
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertEquals(listOf(0xF8,6,2,16,0),s.encode(request).first().map{it.toInt()and 255})
        assertEquals(initial,s.state)
        assertEquals(listOf(0xF2,6),s.encode(request)[1].map{it.toInt()and 255})
        s.accept(b(0xF5,6,2,1,1));assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF5,6,2,0,1));assertFalse(s.encode(request).isEmpty())
        s.accept(b(0xF9,6,2,16,0));assertTrue(s.encode(request).isEmpty())
    }
    @Test fun malformedPreservesButDefinitionStatusAndUnknownCurrentRevoke() {
        val s=ready();val old=s.state
        val request=SonyControlRequest.SetAssignablePreset(old.definition!!,old.current!!,0,16)
        s.accept(cap.copyOf(cap.size-1));assertEquals(old,s.state)
        s.accept(b(0xF7,6,2,254,0));assertFalse(s.state.hasSettings);assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF7,6,2,32,0));assertFalse(s.encode(request).isEmpty())
        s.accept(cap.copyOf().apply{this[14]=18});assertNull(s.state.current);assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF7,6,2,32,0));s.accept(b(0xF3,6,2,0,1));assertTrue(s.encode(request).isEmpty())
        s.accept(b(0xF1,6,0));assertFalse(s.state.hasSettings)
    }
    @Test fun functionRemovalAndV2RejectAllOldAssignableEvidence() {
        val s=ready();s.accept(b(7,0,0));assertNull(s.state.definition)
        assertNull(s.accept(cap))
        val v2=SonyAssignableSession(true)
        assertNull(v2.accept(b(7,0,1,0xF6,0)));assertNull(v2.accept(cap));assertFalse(v2.state.hasSettings)
        val daily=SonyDailyControlsSession(false)
        assertTrue(daily.accept(b(7,0,1,0xF6))!!.queries.any{it.contentEquals(b(0xF0,6))})
    }
    @Test fun actualAdapterIsolatesTablesAndRevokesOnReset() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM4",standardHeadset=true)))
        adapter.beginHandshake()
        fun report(type:dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType,p:ByteArray)=adapter.receive(
            dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.encode(type,0,p))
        val t1=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_1
        val t2=dev.hyperears.protocol.sony.SonyHeadphonesWireCodec.MessageType.COMMAND_2
        report(t1,b(1,0,1,0));report(t2,b(7,0,1,0xF6));report(t2,cap)
        assertNull(adapter.runtimeState().features.get<SonyAssignableFeatureState>())
        report(t1,b(7,0,1,0xF6));report(t1,cap);report(t1,b(0xF7,6,2,32,0));report(t1,b(0xF3,6,2,0,1))
        val state=adapter.runtimeState().features.get<SonyAssignableFeatureState>()!!
        val request=SonyControlRequest.SetAssignablePreset(state.definition!!,state.current!!,0,16)
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.executeControl(request).accepted) // existing ACK queue busy; no latent write.
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<SonyAssignableFeatureState>())
        assertFalse(adapter.supportsControl(request))
    }

}
