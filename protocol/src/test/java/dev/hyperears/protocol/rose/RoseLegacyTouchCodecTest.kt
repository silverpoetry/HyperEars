package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseLegacyTouchCodecTest {
    private fun frame(payload:List<Int>):ByteArray {
        val body=(listOf(0xdd,0,payload.size)+payload).map(Int::toByte).toByteArray()
        return body+byteArrayOf(body.sumOf{it.toInt()and 255}.toByte(),0xaa.toByte())
    }
    @Test fun directMappingIsFragmentedAndChecksumQualifiedWithoutLtvSearch() {
        val data=List(16){it}
        val raw=frame(listOf(1)+data)
        val decoder=RoseLegacyWireCodec.Decoder(false)
        assertTrue(decoder.offer(raw.take(8).toByteArray()).isEmpty())
        assertEquals(listOf(RoseLegacyWireCodec.Field(1,data)),decoder.offer(raw.drop(8).toByteArray()))
        assertTrue(decoder.offer(raw.copyOf().apply{this[lastIndex-1]=0}).isEmpty())
        assertTrue(decoder.offer(frame(listOf(2,1)+data)).isEmpty())
        assertTrue(RoseLegacyWireCodec.Decoder(true).offer(raw).isEmpty())
        assertEquals(listOf(0xff,7,3,1,18,6,34,0xaa),RoseLegacyWireCodec.rsTouchWrite(7,18,6).map{it.toInt()and 255})
    }
}
