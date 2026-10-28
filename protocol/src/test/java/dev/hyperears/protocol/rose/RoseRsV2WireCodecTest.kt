package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseRsV2WireCodecTest {
    private val models=RoseRsV2IdentityCodec.Model.entries
    @Test fun gainAndProtectionStreamResetCannotFinishAnOldPartialReport() {
        val model=RoseRsV2IdentityCodec.Model.OPENFEEL_L
        val report=frame(0x45,2)+frame(0x46,6)
        for(split in 1 until report.size) {
            val decoder=RoseRsV2WireCodec.Decoder(model)
            val values=decoder.offer(report.take(split).toByteArray())+decoder.offer(report.drop(split).toByteArray())
            assertEquals(listOf(RoseRsV2WireCodec.State.Value(0x45,2),RoseRsV2WireCodec.State.Value(0x46,6)),values)
        }
        val decoder=RoseRsV2WireCodec.Decoder(model)
        val gain=frame(0x45,2)
        assertTrue(decoder.offer(gain.take(4).toByteArray()).isEmpty())
        decoder.reset()
        assertTrue(decoder.offer(gain.drop(4).toByteArray()).isEmpty())
        assertEquals(RoseRsV2WireCodec.State.Value(0x46,6),decoder.offer(frame(0x46,6)).single())
    }
    @Test fun gainAndProtectionHaveExactSingleByteBoundsAndPerProfileQueries() {
        for (m in models) {
            val payload = RoseRsV2WireCodec.query(0,m).drop(3).dropLast(2).map { it.toInt() and 255 }
            assertEquals(m.gainAndProtection, 0x45 in payload)
            assertEquals(m.gainAndProtection, 0x46 in payload)
            for ((type, maximum) in listOf(0x45 to 2, 0x46 to 6)) {
                for (value in 0..maximum) assertEquals(m.gainAndProtection,
                    RoseRsV2WireCodec.Decoder(m).offer(frame(type,value)).isNotEmpty())
                assertTrue(RoseRsV2WireCodec.Decoder(m).offer(frame(type,maximum+1)).isEmpty())
                assertTrue(RoseRsV2WireCodec.Decoder(m).offer(frame(type)).isEmpty())
                assertTrue(RoseRsV2WireCodec.Decoder(m).offer(frame(type,0,1)).isEmpty())
            }
        }
    }
    @Test fun sixExactProfilesDoNotBorrowNoiseAudioEqOrCaseSupport() {
        val expected = mapOf("0b0a0a13" to RoseRsV2IdentityCodec.Model.MIRROR,
            "5999064c" to RoseRsV2IdentityCodec.Model.SWIFT2,
            "0b0a34fd" to RoseRsV2IdentityCodec.Model.LUMINOUS_AGUMON,
            "0b0a2aed" to RoseRsV2IdentityCodec.Model.LUMINOUS_GABUMON,
            "0b0ae916" to RoseRsV2IdentityCodec.Model.LUMINOUS_PATAMON,
            "0b0a941f" to RoseRsV2IdentityCodec.Model.LUMINOUS_TAILMON)
        for ((prefix,m) in expected) {
            assertEquals(prefix,m.prefix)
            val payload = RoseRsV2WireCodec.query(0,m).drop(3).dropLast(2).map { it.toInt() and 255 }
            assertFalse(9 in payload);assertFalse(0x2B in payload);assertFalse(0x45 in payload)
            assertEquals(m == RoseRsV2IdentityCodec.Model.MIRROR,0x2A in payload)
            assertTrue(RoseRsV2WireCodec.Decoder(m).offer(frame(9,1)+frame(0x2B,0)).isEmpty())
            assertEquals(m == RoseRsV2IdentityCodec.Model.MIRROR,
                RoseRsV2WireCodec.Decoder(m).offer(frame(0x2A,1)).isNotEmpty())
        }
    }
    @Test fun manufacturerPrefixesIdentifyQualifiedVariantsWithoutNamesOrSharedUuid() {
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
