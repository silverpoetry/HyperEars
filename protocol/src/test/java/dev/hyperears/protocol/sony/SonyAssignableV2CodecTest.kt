package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyAssignableV2CodecTest {
    private fun b(vararg p:Int)=p.map(Int::toByte).toByteArray()
    private fun cap(type:Int)=(listOf(0xF1,type)+(if(type==14)listOf(1)else emptyList())+listOf(1,0,0,32,1,32,1,1,1,33,0,32,2,32,35)).map(Int::toByte).toByteArray()
    @Test fun distinctFixedSelectableGrammarAndLimitationHeader() {
        for(type in listOf(3,14)) {
            val bytes=cap(type)
            val parsed=SonyAssignableV2Codec.capability(bytes,type)!!
            val actions=parsed.keys.single().actions.getValue(32)
            assertEquals(listOf(33),actions[0].functions)
            assertEquals(listOf(32,35),actions[1].functions)
            for(n in 0 until bytes.size)assertNull(SonyAssignableV2Codec.capability(bytes.copyOf(n),type))
            assertNull(SonyAssignableV2Codec.capability(bytes+byteArrayOf(0),type))
            assertNull(SonyAssignableV2Codec.capability(bytes,if(type==3)14 else 3))
            assertNull(SonyAssignableCodec.capability(bytes))
        }
    }
    @Test fun strictDiscoveryMappingAndWrongInquiryIsolation() {
        assertEquals(setOf(3,14),SonyAssignableV2Codec.discovery(b(7,0,2,0xF3,0,0xFE,1)))
        assertNull(SonyAssignableV2Codec.discovery(b(7,0,1,0xF3)))
        val report=b(0xFB,3,2,32,1,0,32,16,1,0,35)
        val mappings=SonyAssignableV2Codec.mappings(report,3)!!
        assertEquals(listOf(32,16),mappings.map{it.preset})
        assertNull(SonyAssignableV2Codec.mappings(report,14))
        assertNull(SonyAssignableV2Codec.mappings(report.copyOf().apply{this[0]=0xFC.toByte()},3))
        for(n in 0 until report.size)assertNull(SonyAssignableV2Codec.mappings(report.copyOf(n),3))
        assertNull(SonyAssignableV2Codec.mappings(b(0xFB,3,1,32,2,0,32,0,33),3))
        assertNull(SonyAssignableV2Codec.vector(b(0xF3,3,1,2),3,true))
    }
}
