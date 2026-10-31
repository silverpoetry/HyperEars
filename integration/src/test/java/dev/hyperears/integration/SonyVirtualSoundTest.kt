package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyVirtualSoundTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    @Test fun fullAdvertisedPairRequiredAndOnlySuccessfulWriteArmsSerialization() {
        val s=SonyVirtualSoundSession(false)
        assertNull(s.accept(b(0x47,1,0)))
        assertEquals(2,s.accept(b(7,0,2,0x41,0x42))!!.queries.size)
        s.accept(b(0x47,1,0));assertFalse(s.state.hasSettings)
        s.accept(b(0x47,2,3));assertTrue(s.state.hasSettings)
        val request=SonyControlRequest.SetVirtualSound(SonyVirtualSoundDefinition(types=listOf(1,2)),mapOf(1 to 0,2 to 3),1,4)
        val encoded=s.encode(request);assertEquals(3,encoded.size);assertFalse(s.state.awaitingReadback)
        assertArrayEquals(b(0x48,1,4),encoded[0]);assertArrayEquals(b(0x46,1),encoded[1]);assertArrayEquals(b(0x46,2),encoded[2])
        s.written(request);assertTrue(s.state.awaitingReadback);assertTrue(s.encode(request).isEmpty())
        s.accept(b(0x49,1,4));assertTrue(s.state.awaitingReadback)
        assertNull(s.accept(b(0x49,2,9)));assertTrue(s.state.awaitingReadback)
        s.accept(b(0x47,2,0));assertFalse(s.state.awaitingReadback)
        assertTrue(s.encode(request).isEmpty()) // stale sibling/current pair
        assertFalse(s.encode(request.copy(expectedValues=mapOf(1 to 4,2 to 0),type=2,value=18)).isEmpty())
        s.accept(b(7,0,1,0x41));assertFalse(s.state.hasSettings);assertFalse(s.state.awaitingReadback)
        assertNull(s.accept(b(0x47,2,0)));s.accept(b(0x47,1,0));assertTrue(s.state.hasSettings)
        assertTrue(s.encode(request).isEmpty())
        val v2=SonyVirtualSoundSession(true);assertNull(v2.accept(b(7,0,2,0x41,0x42)));assertNull(v2.accept(b(0x47,1,0)))
    }
    @Test fun actualAdapterTableIsolationBusyQueuePendingPublicationAndReset() {
        val a=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM4",standardHeadset=true)))
        a.beginHandshake()
        fun report(table2:Boolean=false,vararg p:Int)=a.receive(Wire.encode(if(table2)Wire.MessageType.COMMAND_2 else Wire.MessageType.COMMAND_1,0,b(*p)))
        report(p=intArrayOf(1,0,1,0))
        report(true,7,0,2,0x41,0x42);report(true,0x47,1,0);assertNull(a.runtimeState().features.get<SonyVirtualSoundFeatureState>())
        report(p=intArrayOf(7,0,2,0x41,0x42));report(true,0x47,1,0);assertFalse(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.hasSettings)
        report(p=intArrayOf(0x47,1,0));report(p=intArrayOf(0x47,2,0))
        val request=SonyControlRequest.SetVirtualSound(SonyVirtualSoundDefinition(types=listOf(1,2)),mapOf(1 to 0,2 to 0),2,17)
        assertTrue(a.supportsControl(request));assertEquals(SonyMiLinkPresentationIds.SETTINGS,a.miLinkCardPresentationId)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertFalse(a.executeControl(request).accepted)
        var ack=1
        fun drain()=buildList<Wire.Frame>{repeat(30){val r=a.receive(Wire.encode(Wire.MessageType.ACK,ack));ack=1-ack;r.commands.forEach{addAll(Wire.Decoder().offer(it))}}}
        assertFalse(drain().any{it.payload.firstOrNull()==0x48.toByte()})
        val write=a.executeControl(request);assertTrue(write.accepted)
        assertFalse(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.awaitingReadback)
        a.controlWritten(request);assertTrue(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.awaitingReadback)
        assertFalse(a.supportsControl(request));assertEquals(mapOf(1 to 0,2 to 0),a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.values)
        val sent=Wire.Decoder().offer(write.commands.single()).single();assertEquals(Wire.MessageType.COMMAND_1,sent.type);assertArrayEquals(b(0x48,2,17),sent.payload)
        val readback=drain();assertTrue(readback.any{it.payload.contentEquals(b(0x46,1))});assertTrue(readback.any{it.payload.contentEquals(b(0x46,2))})
        report(p=intArrayOf(0x47,2,17));assertTrue(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.awaitingReadback)
        a.executeControl(StandardControlRequest.Refresh);drain()
        report(p=intArrayOf(0x47,1,0));assertFalse(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.awaitingReadback)
        report(p=intArrayOf(7,0,0));assertFalse(a.runtimeState().features.get<SonyVirtualSoundFeatureState>()!!.hasSettings)
        a.resetProtocolSession();assertNull(a.runtimeState().features.get<SonyVirtualSoundFeatureState>())
    }
}
