package dev.hyperears.protocol.oppo

import org.junit.Assert.*
import org.junit.Test

class OppoNoiseCodecTest {
    private fun frame(command: Int, vararg p: Int) = OppoWireCodec.Decoder().offer(OppoWireCodec.packet(command,payload=p.map(Int::toByte).toByteArray())).single()
    @Test fun currentReportsAreExactSingleBitsNotGestureMasks() {
        assertEquals(5,OppoNoiseCodec.reportIndex(frame(0x810C,0,1,1,32,0)))
        assertEquals(8,OppoNoiseCodec.reportIndex(frame(0x0204,3,1,1,0,1)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x810C,1,1,4)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x810C,1,1,1)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x0204,3,2,1,7,8)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x810C,1,1,1,32,0)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x810C,0,1,1,3)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x0204,4,1,1,32)))
        assertNull(OppoNoiseCodec.reportIndex(frame(0x810C,0,1,1,0)))
    }
    @Test fun setUsesMinimalLittleEndianBitmap() {
        assertArrayEquals(byteArrayOf(1,1,1),OppoWireCodec.Decoder().offer(OppoNoiseCodec.setIndex(0)).single().payload)
        assertArrayEquals(byteArrayOf(1,1,0,8),OppoWireCodec.Decoder().offer(OppoNoiseCodec.setIndex(11)).single().payload)
    }
}
