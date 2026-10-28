package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyVirtualSoundCodecTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    @Test fun exactFunctionsSelectorsAndLiteralTransport() {
        val w=SonyVirtualSoundCodec
        assertEquals(listOf(1,2),w.functions(b(7,0,2,0x41,0x42)))
        assertEquals(emptyList<Int>(),w.functions(b(7,0,2,41,42)))
        assertNull(w.functions(b(7,0,2,0x41)));assertNull(w.functions(b(7,0,1,0x41,0)))
        assertEquals(setOf(0,1,2,3,4),w.values(1));assertEquals(setOf(0,1,2,3,17,18),w.values(2))
        assertEquals(1 to 4,w.report(b(0x47,1,4)));assertEquals(2 to 18,w.report(b(0x49,2,18)))
        for(p in listOf(b(0x48,1,4),b(0x47,1,5),b(0x49,2,4),b(0x47,2,1,0),b(0x47,2)))assertNull(w.report(p))
        assertArrayEquals(b(0x46,1),w.read(1));assertArrayEquals(b(0x48,2,17),w.write(2,17))
        assertArrayEquals(b(0x3E,0x0C,0,0,0,0,3,0x48,2,17,0x6A,0x3C),
            SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.COMMAND_1,0,w.write(2,17)))
    }
}
