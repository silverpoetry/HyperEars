package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseLegacyWireCodecTest {
    private fun packet(payload:ByteArray):ByteArray {
        val body=byteArrayOf(0xdd.toByte(),0,payload.size.toByte())+payload
        return body+byteArrayOf((body.sumOf {it.toInt() and 255} and 255).toByte(),0xaa.toByte())
    }
    @Test fun separateFramingRejectsEchoMalformedBankAndChecksum() {
        val rs=packet(byteArrayOf(14,1))
        for(i in 1 until rs.size){val d=RoseLegacyWireCodec.Decoder(false);assertTrue(d.offer(rs.take(i).toByteArray()).isEmpty());assertEquals(listOf(RoseLegacyWireCodec.Field(14,listOf(1))),d.offer(rs.drop(i).toByteArray()))}
        assertTrue(RoseLegacyWireCodec.Decoder(false).offer(RoseLegacyWireCodec.rsWrite(0,14,1)).isEmpty())
        val bad=rs.copyOf().also {it[it.size-2]=0};assertTrue(RoseLegacyWireCodec.Decoder(false).offer(bad).isEmpty())
        val f8=byteArrayOf(0,0x27,2,0,4,4,2,0,1)
        assertEquals(listOf(RoseLegacyWireCodec.Field(4,listOf(0,1))),RoseLegacyWireCodec.Decoder(true).offer(f8))
        assertTrue(RoseLegacyWireCodec.Decoder(true).offer(RoseLegacyWireCodec.f8Write(0x20,1,true)).isEmpty())
    }
    @Test fun unknownDirectTypeIsNeverReinterpretedAsAnAggregateGameReport() {
        assertTrue(RoseLegacyWireCodec.Decoder(false).offer(packet(byteArrayOf(2,14,1))).isEmpty())
        assertEquals(listOf(RoseLegacyWireCodec.Field(14,listOf(1))),
            RoseLegacyWireCodec.Decoder(false).offer(packet(byteArrayOf(14,1))))
    }
    @Test fun exactManufacturerPrefixesRejectGenericAndAmbiguousEvidence() {
        assertEquals(RoseLegacyIdentityCodec.Model.EXPLORER,RoseLegacyIdentityCodec.identify(mapOf(0x8418 to listOf(1,7))))
        assertNull(RoseLegacyIdentityCodec.identify(mapOf(0x0b0a to listOf(10,11))))
        assertNull(RoseLegacyIdentityCodec.identify(mapOf(0x8418 to listOf(1,7),0x0c0b to listOf(10,12))))
    }
}
