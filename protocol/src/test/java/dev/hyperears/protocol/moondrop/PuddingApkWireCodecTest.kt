package dev.hyperears.protocol.moondrop

import org.junit.Assert.*
import org.junit.Test

class PuddingApkWireCodecTest {
    @Test fun releaseApkGeneratedRequestsCountTheWholeApplicationPayload() {
        assertArrayEquals(byteArrayOf(-1,1,0,4,0,10,3,0), PuddingApkWireCodec.handshake)
        assertArrayEquals(byteArrayOf(-1,4,0,5,0,29,64,4,4), PuddingApkWireCodec.write(64,4))
        assertArrayEquals(byteArrayOf(-1,4,0,4,0,29,30,1), PuddingApkWireCodec.query(30))
    }
    @Test fun oversizedFrameRecoversAndLargeJunkDoesNotAccumulate() {
        val decoder = PuddingApkWireCodec.Decoder()
        val gain = byteArrayOf(-1,4,0,5,0,29,31,1,2)
        assertEquals(2,PuddingApkWireCodec.scalar(decoder.offer(byteArrayOf(-1,4,2,1,0)+gain).single())!!.value)
        assertTrue(decoder.offer(ByteArray(100_000) { 0 }).isEmpty())
        assertEquals(0,decoder.bufferedBytes)
        assertTrue(decoder.offer(byteArrayOf(-1)).isEmpty())
        assertEquals(1,decoder.bufferedBytes)
        assertEquals(1,decoder.offer(gain.drop(1).toByteArray()).size)
        assertEquals(0,decoder.bufferedBytes)
    }
    @Test fun allModesAndOnlyUniqueCompleteBatteryPairsAreValid() {
        (0..4).forEach { assertEquals(it,PuddingApkWireCodec.scalar(byteArrayOf(0,29,65,3,it.toByte()))!!.value) }
        assertEquals(mapOf(1 to 0),PuddingApkWireCodec.battery(byteArrayOf(0,29,27,1,1,0)))
        assertNull(PuddingApkWireCodec.battery(byteArrayOf(0,29,27,1,1,20,1,30)))
        assertNull(PuddingApkWireCodec.battery(byteArrayOf(0,29,27,1,1,20,2)))
        assertNull(PuddingApkWireCodec.battery(byteArrayOf(0,29,27,1,2,-1)))
        val flagged = byteArrayOf(-1,4,1,5,0,29,65,3,4,0)
        assertTrue(PuddingApkWireCodec.Decoder().offer(flagged).isEmpty())
        val valid = byteArrayOf(-1,4,0,5,0,29,65,3,4)
        assertEquals(1,PuddingApkWireCodec.Decoder().offer(flagged+valid).size)
    }
    @Test fun scalarShapeAndFlagsAreStrictAndFragmentsAreRetained() {
        val bytes = byteArrayOf(-1,4,0,5,0,29,31,1,2)
        val decoder = PuddingApkWireCodec.Decoder()
        assertTrue(decoder.offer(bytes.copyOfRange(0,7)).isEmpty())
        assertEquals(2, PuddingApkWireCodec.scalar(decoder.offer(bytes.copyOfRange(7,9)).single())!!.value)
        assertNull(PuddingApkWireCodec.scalar(byteArrayOf(0,29,31,1,3)))
        assertNull(PuddingApkWireCodec.scalar(byteArrayOf(0,29,31,1,2,0)))
        assertTrue(PuddingApkWireCodec.Decoder().offer(bytes.copyOf().also { it[2]=1 }).isEmpty())
    }
}
