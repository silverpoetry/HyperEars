package dev.hyperears.protocol.oppo
import org.junit.Assert.*
import org.junit.Test
class OppoFitCodecTest {
    private fun frame(hex:String)=OppoWireCodec.Decoder().offer(hex.split(" ").map{it.toInt(16).toByte()}.toByteArray()).single()
    @Test fun sourceResultAndWearLiteralFramesAreExact() {
        val result=frame("AA 0C 00 00 04 02 F0 05 00 04 01 01 02 06")
        assertEquals(1 to 6,OppoFitCodec.result(result))
        val wear=frame("AA 0D 00 00 09 81 F0 06 00 00 02 01 02 02 02")
        assertEquals(true to true,OppoFitCodec.wear(wear))
        assertNull(OppoFitCodec.wear(wear.copy(payload=byteArrayOf(0,2,1,2,1,2))))
        assertNull(OppoFitCodec.result(result.copy(payload=byteArrayOf(4,1,2,2,1))))
        assertNull(OppoFitCodec.result(result.copy(command=0x8202)))
        assertEquals(1 to 6,OppoFitCodec.result(result.copy(command=0x8202,payload=byteArrayOf(0,4,1,1,2,6))))
        assertArrayEquals(byteArrayOf(0xaa.toByte(),7,0,0,9,1,0xf0.toByte(),0,0),OppoFitCodec.queryWear)
    }
}
