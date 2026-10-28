package dev.hyperears.protocol.oppo

import org.junit.Assert.*
import org.junit.Test

class OppoSettingsCodecTest {
    private fun frame(command: Int, vararg p: Int) = OppoWireCodec.Decoder().offer(
        OppoWireCodec.packet(command, payload = p.map(Int::toByte).toByteArray())).single()
    @Test fun switchesRequireSuccessfulCompleteUniqueBooleanPairs() {
        assertEquals(mapOf(4 to true, 6 to false, 17 to true, 40 to false),
            OppoSettingsCodec.parseSwitches(frame(0x810D, 0, 4, 4, 1, 6, 0, 17, 1, 40, 0)))
        listOf(frame(0x810D, 1, 1, 4, 1), frame(0x810D, 0, 2, 4, 1),
            frame(0x810D, 0, 1, 4, 2), frame(0x810D, 0, 2, 4, 1, 4, 0),
            frame(0x810D, 0, 1, 4, 1, 0), frame(0x8403, 0, 4, 1)).forEach {
            assertNull(OppoSettingsCodec.parseSwitches(it))
        }
        assertArrayEquals(OppoWireCodec.packet(0x0403, payload = byteArrayOf(0x11, 0)),
            OppoSettingsCodec.setSwitch(0x11, false))
    }
    @Test fun eqListParsesDeviceRangesAndRejectsPartialOrDuplicateEntries() {
        val entry = intArrayOf(1, 250, 6, 9, 1, 65, 2, 62, 0, 255, 232, 3, 2)
        val parsed = OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 1, *entry))!!.single()
        assertEquals(listOf(62, 1000), parsed.frequencies)
        assertEquals(listOf(-1, 2), parsed.gains)
        assertEquals(-6, parsed.minimum)
        assertNull(OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 2, *entry)))
        assertNull(OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 2, *entry, *entry)))
        assertNull(OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 1, *entry, 0)))
        assertEquals(emptyList<OppoSettingsCodec.Equalizer>(), OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 0)))
        assertEquals(9, OppoSettingsCodec.parseCurrentEq(frame(0x0504, 0, 9)))
        assertNull(OppoSettingsCodec.parseCurrentEq(frame(0x810F, 1, 9)))
        assertNull(OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 1,
            1, 250, 6, 9, 2, 0xC0, 0x80, 0)))
        assertNull(OppoSettingsCodec.parseEqualizers(frame(0x8122, 0, 1,
            1, 250, 6, 9, 1, 65, 1, 62, 0, 7)))
    }
    @Test fun editingPreservesDeviceIdentityMetadataAndSignedGainBytes() {
        val entry = OppoSettingsCodec.Equalizer(9, "A", true, -6, 6, listOf(62, 1000), listOf(0, 0))
        assertArrayEquals(OppoWireCodec.packet(0x0418,
            payload = byteArrayOf(2, -6, 6, 9, 1, 65, 2, 62, 0, -3, -24, 3, 6)),
            OppoSettingsCodec.updateEqualizer(entry, listOf(-3, 6)))
        assertFalse(OppoSettingsCodec.canUpdate(entry, listOf(0)))
        assertFalse(OppoSettingsCodec.canUpdate(entry, listOf(-7, 0)))
        assertFalse(OppoSettingsCodec.canUpdate(entry.copy(name = "x".repeat(120)), listOf(0, 0)))
    }
}
