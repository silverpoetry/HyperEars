package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyVoiceGuidanceCodecTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    @Test fun exactHexFunctionAndSettingShapes() {
        val w=SonyVoiceGuidanceCodec
        assertEquals(true,w.function(b(7,0,1,0x39)))
        assertEquals(false,w.function(b(7,0,1,39))) // decimal39 is NOT hex39.
        assertNull(w.function(b(7,0,2,0x39)));assertNull(w.function(b(7,0,1,0x39,0)))
        assertEquals(false,w.report(b(0x47,1,1,0)));assertEquals(true,w.report(b(0x49,1,1,1)))
        for(p in listOf(b(0x48,1,1,1),b(0x47,1,0,1),b(0x47,1,1,2),b(0x47,1,1),b(0x47,1,1,1,0)))assertNull(w.report(p))
        assertArrayEquals(b(0x46,1,1),w.read());assertArrayEquals(b(0x48,1,1,0),w.write(false))
        // Independent complete SPP frame: command2, seq0, length4, additivechecksum0x5C.
        val expected=b(0x3E,0x0E,0,0,0,0,4,0x48,1,1,0,0x5C,0x3C)
        assertArrayEquals(expected,SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.COMMAND_2,0,w.write(false)))
    }
}
