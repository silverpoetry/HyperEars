package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseRsV2WireCodecTest {
    private val models=RoseRsV2IdentityCodec.Model.entries
    @Test fun manufacturerPrefixesIdentifyNineVariantsWithoutNamesOrSharedUuid() {
        models.forEach { model ->
            val bytes=model.prefix.chunked(2).map {it.toInt(16)}
            assertEquals(model,RoseRsV2IdentityCodec.identify(mapOf((bytes[0] or (bytes[1] shl 8)) to bytes.drop(2))))
        }
        assertNull(RoseRsV2IdentityCodec.identify(emptyMap()))
        assertNull(RoseRsV2IdentityCodec.identify(mapOf(0x0E0B to listOf(10,12),0xD745 to listOf(0xCB,0xED))))
        assertNull(RoseRsV2IdentityCodec.identify(mapOf(0x0E0B to listOf(10,256))))
    }
    @Test fun boundedQueriesAndReportsKeepModelControlsSeparate() {
        models.forEach { m ->
            val p=RoseRsV2WireCodec.query(0,m).drop(3).dropLast(2).map{it.toInt() and 255}
            assertEquals(m.noise,9 in p);assertEquals(m.touch,1 in p);assertEquals(m.touchMaster,7 in p)
            assertEquals(m.audio,0x2B in p)
            assertEquals(m.noise,RoseRsV2WireCodec.Decoder(m).offer(frame(9,3)).isNotEmpty())
            assertEquals(m.touchMaster,RoseRsV2WireCodec.Decoder(m).offer(frame(7,1)).isNotEmpty())
        }
        assertTrue(RoseRsV2WireCodec.Decoder(RoseRsV2IdentityCodec.Model.OPENFEEL_L).offer(frame(0x2B,2)).isEmpty())
        assertTrue(RoseRsV2WireCodec.Decoder(RoseRsV2IdentityCodec.Model.SPEAKFEEL2).offer(frame(0x2B,3)).isEmpty())
        assertEquals(RoseRsV2WireCodec.State.Value(0x2B,6),RoseRsV2WireCodec.Decoder(RoseRsV2IdentityCodec.Model.SPEAKFEEL).offer(frame(0x2B,6)).single())
    }
    @Test fun equalizerSwapsWirePopHifiButKeepsAstroLabelIndicesAndBassVariantRanges() {
        for(m in models.filter {it.eqPresets.isNotEmpty()}) {
            assertEquals(RoseRsV2WireCodec.State.Value(0x2A,1),RoseRsV2WireCodec.Decoder(m).offer(frame(0x2A,0)).single())
            assertEquals(RoseRsV2WireCodec.State.Value(0x2A,0),RoseRsV2WireCodec.Decoder(m).offer(frame(0x2A,1)).single())
        }
        val speak=RoseRsV2WireCodec.Decoder(RoseRsV2IdentityCodec.Model.SPEAKFEEL2)
        assertTrue(speak.offer(frame(0x2A,5,2)).isEmpty());assertEquals(RoseRsV2WireCodec.State.Value(0x105,1),speak.offer(frame(0x2A,5,1)).single())
        val open=RoseRsV2WireCodec.Decoder(RoseRsV2IdentityCodec.Model.OPENFEEL_MK2)
        assertTrue(open.offer(frame(0x2A,5,2)).isEmpty());assertEquals(RoseRsV2WireCodec.State.Value(0x105,3),open.offer(frame(0x2A,5,3)).single())
    }
    @Test fun corruptOpaqueAndFragmentedFramesNeverBorrowNestedCapabilities() {
        val m=RoseRsV2IdentityCodec.Model.CERAMICS_MK2
        val d=RoseRsV2WireCodec.Decoder(m);val f=frame(9,1)
        assertTrue(d.offer(f.take(3).toByteArray()).isEmpty());assertEquals(1,d.offer(f.drop(3).toByteArray()).size)
        assertTrue(d.offer(f.copyOf().apply{this[lastIndex-1]=0}).isEmpty())
        val nested=frame(0x70,*f.map{it.toInt() and 255}.toIntArray())
        assertTrue(RoseRsV2WireCodec.Decoder(m).offer(nested).isEmpty())
    }
    private fun frame(type:Int,vararg values:Int):ByteArray {
        val p=byteArrayOf(0xDD.toByte(),0,(values.size+1).toByte(),type.toByte())+values.map(Int::toByte)
        return p+byteArrayOf(p.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte())
    }
}
