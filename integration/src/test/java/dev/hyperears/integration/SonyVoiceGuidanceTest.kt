package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyVoiceGuidanceTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    private class Fixture(v2:Boolean=false) {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM4",standardHeadset=true)))
        var ack=1
        init {adapter.beginHandshake();report(false,*(if(v2)intArrayOf(1,0,3,0,0,0,0,0)else intArrayOf(1,0,1,0)))}
        fun report(table2:Boolean,vararg p:Int)=adapter.receive(Wire.encode(if(table2)Wire.MessageType.COMMAND_2 else Wire.MessageType.COMMAND_1,0,p.map(Int::toByte).toByteArray()))
        fun drain()=buildList<Wire.Frame> {repeat(30){
            val result=adapter.receive(Wire.encode(Wire.MessageType.ACK,ack));ack=1-ack
            result.commands.forEach{addAll(Wire.Decoder().offer(it))}
        }}
        fun state()=adapter.runtimeState().features.get<SonyVoiceGuidanceFeatureState>()
    }
    @Test fun actualAdapterTableFunctionCurrentAndReadbackAreIndependentGates() {
        val f=Fixture();f.report(true,0x47,1,1,0);assertNull(f.state())
        f.report(true,7,0,1,0x39);f.report(true,0x47,1,1,0);assertNull(f.state())
        f.report(false,7,0,1,0x39);assertFalse(f.state()!!.hasSettings)
        f.report(false,0x47,1,1,0);assertFalse(f.state()!!.hasSettings)
        f.report(true,0x47,1,1,0)
        val request=SonyControlRequest.SetVoiceGuidance(SonyVoiceGuidanceDefinition(),false,true)
        assertTrue(f.adapter.supportsControl(request));assertEquals(SonyMiLinkPresentationIds.SETTINGS,f.adapter.miLinkCardPresentationId)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertFalse(f.adapter.executeControl(request).accepted) // busy must not enqueue deferred write
        val reads=f.drain();assertTrue(reads.any{it.type==Wire.MessageType.COMMAND_2 && it.payload.contentEquals(b(0x46,1,1))})
        assertFalse(reads.any{it.payload.firstOrNull()==0x48.toByte()})
        val sent=f.adapter.executeControl(request);assertTrue(sent.accepted)
        val write=Wire.Decoder().offer(sent.commands.single()).single()
        assertEquals(Wire.MessageType.COMMAND_2,write.type);assertArrayEquals(b(0x48,1,1,1),write.payload)
        assertEquals(false,f.state()!!.enabled)
        assertTrue(f.drain().any{it.type==Wire.MessageType.COMMAND_2 && it.payload.contentEquals(b(0x46,1,1))})
        f.report(true,0x49,1,1,1);assertFalse(f.adapter.supportsControl(request))
        assertFalse(f.adapter.supportsControl(request.copy(expected=SonyVoiceGuidanceDefinition(version=2))))
        val off=request.copy(expectedCurrent=true,enabled=false);assertTrue(f.adapter.supportsControl(off))
        f.report(false,7,0,0);assertFalse(f.state()!!.hasSettings);assertFalse(f.adapter.supportsControl(off))
        f.report(true,0x47,1,1,1);assertFalse(f.state()!!.hasSettings)
        f.report(false,7,0,1,0x39);assertFalse(f.state()!!.hasSettings)
        f.report(true,0x47,1,1,1);assertTrue(f.adapter.supportsControl(off))
        f.adapter.resetProtocolSession();assertNull(f.state());assertFalse(f.adapter.supportsControl(off))
    }
    @Test fun v2AndMalformedReportsCannotGrantOrReplaceConfirmedValue() {
        val f=Fixture(true);f.report(false,7,0,1,0x39,0);f.report(true,0x47,1,1,1);assertNull(f.state())
        val s=SonyVoiceGuidanceSession(false);s.functions(b(7,0,1,0x39));s.accept(b(0x47,1,1,0))
        assertNull(s.accept(b(0x47,1,1,8)));assertEquals(false,s.state.enabled)
        assertFalse(s.functions(b(7,0,2,0x39)));assertTrue(s.state.hasSettings)
    }
}
