package dev.hyperears.protocol.sony

import org.junit.Assert.*
import org.junit.Test

class SonyDailyControlsCodecTest {
    private fun b(vararg values: Int) = values.map(Int::toByte).toByteArray()
    @Test fun supportListUsesVersionSpecificIdsAndRejectsTruncatedCounts() {
        assertEquals(setOf(1, 2, 3), SonyDailyControlsCodec.functions(false, b(7, 0, 3, 0x51, 0x52, 0x53))!!.eqTypes)
        assertEquals(setOf(0, 1, 3), SonyDailyControlsCodec.functions(true, b(7, 0, 3, 0x50, 2, 0x51, 0, 0x53, 1))!!.eqTypes)
        assertNull(SonyDailyControlsCodec.functions(true, b(7, 0, 2, 0x50, 0)))
        assertNull(SonyDailyControlsCodec.functions(false, b(7, 0, 2, 0x51)))
        assertEquals(emptySet<Int>(), SonyDailyControlsCodec.functions(false, b(7, 0, 1, 0x50))!!.eqTypes)
    }
    @Test fun exactEqLayoutsSeparateCustomUltScalarAndBandArrays() {
        assertEquals(listOf(10, 12), SonyDailyControlsCodec.equalizer(false, b(0x57, 1, 0, 2, 10, 12))!!.steps)
        assertNull(SonyDailyControlsCodec.equalizer(false, b(0x57, 1, 0, 3, 10, 12)))
        assertNull(SonyDailyControlsCodec.equalizer(false, b(0x57, 1, 0, 2, 10, 255)))
        assertEquals(null, SonyDailyControlsCodec.equalizer(true, b(0x57, 0x31, 2, 6, 7))!!.preset)
        assertEquals(2, SonyDailyControlsCodec.equalizer(true, b(0x57, 3, 0x10, 2, 1, 10))!!.ult)
        assertEquals(-4, SonyDailyControlsCodec.equalizer(false, b(0x57, 2, 0xFC))!!.bass)
        assertEquals(listOf(12, 10), SonyDailyControlsCodec.equalizer(true, b(0x57, 1, 0, 2, 12, 10))!!.steps)
        assertArrayEquals(b(0x58, 3, 0x14, 2, 0), SonyDailyControlsCodec.setEq(true, 3, 0x14, emptyList(), 2))
        assertArrayEquals(b(0x58, 0x31, 2, 5, 6), SonyDailyControlsCodec.setEq(true, 0x31, 0xA0, listOf(5, 6)))
    }
    @Test fun geometryIsExactAndClearBassCannotBeMistakenForFrequency() {
        assertEquals(0x10, SonyDailyControlsCodec.geometry(b(0x5B, 0, 2, 0x10, 0, 0, 1, 1, 0x90))!!.second.first().kind)
        assertNull(SonyDailyControlsCodec.geometry(b(0x5B, 0, 2, 0x10, 0, 0)))
        assertNull(SonyDailyControlsCodec.geometry(b(0x5B, 0, 2, 1, 1, 0x90, 0x10, 0, 0)))
        assertNull(SonyDailyControlsCodec.geometry(b(0x5B, 0, 1, 9, 0, 0)))
    }
    @Test fun ambientEditsPreserveModeWindAdaptiveAndVersionLayout() {
        val v1 = SonyDailyControlsCodec.ambient(false, b(0x67, 2, 1, 2, 0, 1, 0, 10))!!
        assertArrayEquals(b(0x68, 2, 0x11, 2, 0, 1, 1, 7), SonyDailyControlsCodec.setAmbient(false, v1, 7, true))
        val v2 = SonyDailyControlsCodec.ambient(true, b(0x67, 0x19, 0, 1, 1, 0, 10, 0, 2))!!
        assertArrayEquals(b(0x68, 0x19, 1, 1, 1, 1, 7, 0, 2), SonyDailyControlsCodec.setAmbient(true, v2, 7, true))
        assertFalse(SonyDailyControlsCodec.ambient(true, b(0x67, 0x15, 1, 1, 1, 2, 0, 10))!!.voiceWritable)
        assertNull(SonyDailyControlsCodec.ambient(true, b(0x67, 0x17, 1, 1, 1, 2, 10)))
        assertNull(SonyDailyControlsCodec.ambient(true, b(0x67, 0x17, 1, 1, 1, 0, 21)))
    }
}
