package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyAssignableCodecTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    private val cap=b(0xF1,6,2,1,0,32,2,32,1,0,32,16,1,0,17,0,1,0,1,0,1,0,1)
    @Test fun strictNestedGrammarPreservesWireOrderAndV1FunctionBytes() {
        val parsed=SonyAssignableCodec.capability(cap)!!
        assertEquals(listOf(1,0),parsed.keys.map{it.id})
        assertEquals(listOf(32,16),parsed.keys[0].presets)
        for(n in 0 until cap.size)assertNull(SonyAssignableCodec.capability(cap.copyOf(n)))
        assertNull(SonyAssignableCodec.capability(cap+byteArrayOf(0)))
        assertNull(SonyAssignableCodec.capability(cap.copyOf().apply{this[1]=3}))
        assertNull(SonyAssignableCodec.capability(cap.copyOf().apply{this[13]=255.toByte()}))
        assertTrue(SonyAssignableCodec.capability(b(0xF1,6,0))!!.keys.isEmpty())
    }
    @Test fun discoveryAndVectorsRejectEchoesAndV2() {
        assertEquals(true,SonyAssignableCodec.discovery(false,b(7,0,1,0xF6)))
        assertNull(SonyAssignableCodec.discovery(true,b(7,0,1,0xF6)))
        assertNull(SonyAssignableCodec.discovery(false,b(7,0,1,0xF6,0)))
        assertNull(SonyAssignableCodec.vector(b(0xF8,6,1,0),false))
        assertNull(SonyAssignableCodec.vector(b(0xF3,6,1,2),true))
        assertNull(SonyAssignableCodec.vector(b(0xF7,6,2,0),false))
        assertEquals(listOf(0,255),SonyAssignableCodec.vector(b(0xF7,6,2,0,255),false))
    }
}
