package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class SonyAdaptiveAmbientTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    @Test fun adaptiveWritesUseConfirmedCarrierAndPreserveVoiceWhileSelectingAmbientMode() {
        val s=SonyDailyControlsSession(true)
        s.accept(b(7,0,2,0x6B,0,0x6D,1))
        s.accept(b(0x67,0x17,0,1,0,0,8))
        s.accept(b(0x67,0x19,0,1,0,1,6,0,2))
        val state=s.ambientState()!!
        assertEquals(2,state.adaptiveSensitivity)
        val request=SonyControlRequest.SetAdaptiveAmbient(state.parameterSnapshot,true,1)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertArrayEquals(b(0x68,0x19,1,1,1,1,6,1,1),s.encode(request).first())
        assertEquals(false,s.ambientState()!!.adaptive)
        s.accept(b(0x69,0x19,0,1,0,1,7,0,2));assertTrue(s.encode(request).isEmpty())
        val current=s.ambientState()!!
        assertTrue(s.encode(request.copy(expected=current.parameterSnapshot,sensitivity=3)).isEmpty())
        s.accept(b(7,0,0));assertNull(s.ambientState())
        assertTrue(s.encode(request).isEmpty())
    }
    @Test fun automaticLevelChangesDoNotInvalidateAnOtherwiseCurrentToggle() {
        val s=SonyDailyControlsSession(true);s.accept(b(7,0,1,0x6D,0))
        s.accept(b(0x67,0x19,0,1,1,1,6,1,2))
        val request=SonyControlRequest.SetAdaptiveAmbient(s.ambientState()!!.parameterSnapshot,false,2)
        s.accept(b(0x69,0x19,0,1,1,1,9,1,2))
        assertArrayEquals(b(0x68,0x19,1,1,1,1,9,0,2),s.encode(request).first())
        s.accept(b(0x69,0x19,0,1,1,0,9,1,2))
        assertTrue(s.encode(request).isEmpty())
    }
    @Test fun senseCapabilitiesDoNotImplyNoiseAdaptiveCarrier() {
        val s=SonyDailyControlsSession(true)
        s.accept(b(7,0,2,0x70,0,0x71,1));s.accept(b(0x67,0x19,0,1,0,1,6,0,2))
        assertNull(s.ambientState())
    }
}
