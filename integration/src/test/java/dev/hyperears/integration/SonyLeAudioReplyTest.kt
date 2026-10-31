package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioReplyTest {
    private fun b(vararg v:Int)=v.map(Int::toByte).toByteArray()
    @Test fun exactAlertDeadlineQueueAndOneShotReplyAreEnforced() {
        val adapter=requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName="WH-1000XM6",standardHeadset=true)))
        fun send(payload:ByteArray)=adapter.receive(Wire.encode(Wire.MessageType.COMMAND_1,0,payload))
        fun state()=requireNotNull(adapter.runtimeState().features.get<SonyLeAudioFeatureState>())
        adapter.beginHandshake();send(b(1,0,3,0,0,0,0,0));send(b(7,0,2,0x41,0,0x42,1));send(b(0x99,6,13,1,7,0))
        val feature=state()
        val request=SonyControlRequest.ReplyLeAudioAlert(feature.alertSequence,requireNotNull(feature.alert),true)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertTrue(feature.supports(request))
        assertFalse(feature.supports(request,feature.alertExpiresAtMillis))
        assertFalse(feature.supports(request.copy(expectedSequence=request.expectedSequence+1)))
        assertFalse(adapter.executeControl(request).accepted) // busy must not defer a confirmation
        repeat(80) { i ->
            adapter.receive(Wire.encode(Wire.MessageType.ACK,(i+1)%2)).commands.forEach { bytes ->
                Wire.Decoder().offer(bytes).forEach { assertFalse(it.payload.firstOrNull()==0x98.toByte()) }
            }
        }
        val sent=adapter.executeControl(request)
        assertTrue(sent.accepted)
        assertArrayEquals(b(0x98,6,13,1),Wire.Decoder().offer(sent.commands.single()).single().payload)
        adapter.receive(Wire.encode(Wire.MessageType.ACK,1))
        adapter.receive(Wire.encode(Wire.MessageType.ACK,0))
        assertFalse(adapter.executeControl(request).accepted)
        send(b(7,0,0));send(b(7,0,2,0x41,0,0x42,1));send(b(0x99,6,13,1,7,0))
        assertTrue(state().alertSequence>request.expectedSequence)
        assertFalse(state().supports(request))
    }
}
