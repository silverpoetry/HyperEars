package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioModeTest {
    private fun b(vararg v:Int)=v.map(Int::toByte).toByteArray()
    @Test fun modeAndHistoryRequireExactDiscoveredIdentityAndIdleTransport() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)))
        fun send(payload:ByteArray)=adapter.receive(Wire.encode(Wire.MessageType.COMMAND_1,0,payload))
        fun drain(){repeat(80){adapter.receive(Wire.encode(Wire.MessageType.ACK,(it+1)%2))}}
        adapter.beginHandshake();send(b(1,0,3,0,0,0,0,0));send(b(7,0,2,0x41,0,0x42,1))
        send(b(0x41,1)+"AA:BB:CC:DD:EE:01AA:BB:CC:DD:EE:02".encodeToByteArray())
        send(b(0x43,12,0));send(b(0x47,12,1))
        val feature=adapter.runtimeState().features.get<SonyLeAudioFeatureState>()!!
        val identity=requireNotNull(feature.identity)
        val request=SonyControlRequest.SetLeAudio(identity,false,true)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.supportsControl(request.copy(expectedEnabled=true)))
        assertFalse(adapter.supportsControl(request.copy(expectedIdentity=identity.copy(classic="AA:BB:CC:DD:EE:03"))))
        assertFalse(adapter.executeControl(request).accepted)
        drain()
        val history=adapter.executeControl(SonyControlRequest.ReadLeAudioHistory(identity))
        assertArrayEquals(b(0x46,1),Wire.Decoder().offer(history.commands.single()).single().payload)
        send(b(0x47,1,0));send(b(0x47,1,0))
        assertEquals(2L,adapter.runtimeState().features.get<SonyLeAudioFeatureState>()!!.historySequence)
        drain()
        val result=adapter.executeControl(request)
        assertTrue(result.accepted)
        val writes=mutableListOf<List<Int>>()
        fun collect(commands:List<ByteArray>) {commands.forEach { bytes->Wire.Decoder().offer(bytes).filter {it.type==Wire.MessageType.COMMAND_1}.forEach {writes+=it.payload.map {v->v.toInt() and 255}} }}
        collect(result.commands)
        repeat(12){collect(adapter.receive(Wire.encode(Wire.MessageType.ACK,(it+1)%2)).commands)}
        assertEquals(listOf(listOf(0x94,0,0),listOf(0x94,5,0),listOf(0x48,12,0,0)),writes)
        send(b(0x43,12,1));assertFalse(adapter.supportsControl(request))
    }
}
