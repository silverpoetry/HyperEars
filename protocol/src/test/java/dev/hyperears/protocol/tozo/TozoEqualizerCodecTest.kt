package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoEqualizerCodecTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val report = hex("001828140007003200010064000100c8000300900104002003070040060800800c0100001906000032090072")
    @Test fun capturedFractionalGainAndPersistedWriteRoundTripExactly() {
        val bands = requireNotNull(TozoEqualizerCodec.parse(report))
        assertEquals(10, bands.size)
        assertEquals(TozoEqualizerCodec.Band(20,7,0),bands.first())
        assertEquals(12800,bands.last().frequencyHz)
        assertArrayEquals(hex("10182901140007003200010064000100c8000300900104002003070040060800800c0100001906000032090073"),TozoEqualizerCodec.write(bands,true))
        assertEquals(bands,TozoEqualizerCodec.parse(TozoWireCodec.encode(0x201f,report.copyOfRange(3,43))))
    }
    @Test fun negativeGainAndQualityByteAreNotCombinedIntoASignedShort() {
        val bands=List(10) { TozoEqualizerCodec.Band(1000+it,-20,200) }
        val payload=TozoWireCodec.decode(TozoEqualizerCodec.write(bands,false))!!.payload
        assertEquals(0,payload[0].toInt())
        assertEquals(bands,TozoEqualizerCodec.parse(TozoWireCodec.encode(0x0018,payload.copyOfRange(1,payload.size))))
    }
    @Test fun acknowledgementsAndIncompleteReportsNeverBecomeSettings() {
        assertNull(TozoEqualizerCodec.parse(hex("1018010000")))
        assertNull(TozoEqualizerCodec.parse(TozoWireCodec.encode(0x0018,ByteArray(36))))
        assertNull(TozoEqualizerCodec.parse(report.copyOf().also { it[it.lastIndex]=0 }))
    }
    @Test fun invalidRequestsAreRejectedRatherThanClampedOrTruncated() {
        assertThrows(IllegalArgumentException::class.java) { TozoEqualizerCodec.write(emptyList(),true) }
        assertThrows(IllegalArgumentException::class.java) { TozoEqualizerCodec.write(List(10){TozoEqualizerCodec.Band(1000,128,0)},true) }
    }
}
