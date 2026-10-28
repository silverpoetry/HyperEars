package dev.hyperears.protocol.bose

import org.junit.Assert.*
import org.junit.Test

class BoseDailySettingsCodecTest {
    private fun frame(function: Int, vararg payload: Int) = BoseBmapWireCodec.Decoder().offer(
        BoseBmapWireCodec.packet(1, function, BoseBmapWireCodec.Operator.STATUS, payload.map(Int::toByte).toByteArray())).single()
    @Test fun equalizerUsesSignedBoundsAndWritesValueBeforeBandId() {
        val eq = BoseDailySettingsCodec.parseEq(frame(7, -10, 10, -3, 0, -10, 10, 0, 1, -10, 10, 4, 2))!!
        assertEquals(-3, eq.first().current)
        assertArrayEquals(byteArrayOf(1, 7, 2, 2, -5, 2), BoseDailySettingsCodec.setEq(2, -5))
        assertNull(BoseDailySettingsCodec.parseEq(frame(7, -10, 10, 0)))
        assertNull(BoseDailySettingsCodec.parseEq(frame(7, -10, 10, 11, 0)))
        assertNull(BoseDailySettingsCodec.parseEq(frame(7, -10, 10, 0, 0, -10, 10, 0, 0)))
        assertEquals(emptyList<BoseDailySettingsCodec.EqBand>(), BoseDailySettingsCodec.parseEq(frame(7)))
    }
    @Test fun multipointReadMaskDiffersFromWriteAndPromptTogglePreservesLanguageAndOpaqueBits() {
        assertEquals(true, BoseDailySettingsCodec.parseMultipoint(frame(10, 7)))
        assertEquals(false, BoseDailySettingsCodec.parseMultipoint(frame(10, 1)))
        assertArrayEquals(byteArrayOf(1, 10, 2, 1, 1), BoseDailySettingsCodec.setToggle(10, true))
        assertArrayEquals(byteArrayOf(1, 3, 2, 1, 0xC8.toByte()), BoseDailySettingsCodec.setPrompts(0xE8, false))
        assertEquals(0xE8, BoseDailySettingsCodec.parsePrompts(frame(3, 0xE8)))
        assertNull(BoseDailySettingsCodec.parseAutoPause(frame(24, 2)))
    }
    @Test fun strictCncAndAnrNeverTreatAcksErrorsOrMalformedBytesAsReports() {
        assertEquals(BoseDailySettingsCodec.Cnc(7, false), BoseDailySettingsCodec.parseCnc(frame(5, 11, 7, 0)))
        assertNull(BoseDailySettingsCodec.parseCnc(frame(5, 11, 11, 1)))
        assertNull(BoseDailySettingsCodec.parseCnc(frame(5, 12, 7, 1)))
        assertNull(BoseDailySettingsCodec.parseCnc(frame(5, 11, 7, 2)))
        assertEquals(3, BoseDailySettingsCodec.parseAnr(frame(6, 3, 11)))
        for (operator in BoseBmapWireCodec.Operator.entries.filter { it != BoseBmapWireCodec.Operator.STATUS }) {
            assertNull(BoseDailySettingsCodec.parsePrompts(frame(3, 0x28).copy(flags = operator.value)))
        }
    }
}
