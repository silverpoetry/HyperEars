package dev.hyperears.protocol.sony

import org.junit.Assert.*
import org.junit.Test

class SonyAudioSettingsCodecTest {
    private fun b(vararg p: Int) = p.map(Int::toByte).toByteArray()
    @Test fun sourceFixturesPreserveTypesPolarityAndStrictShapes() {
        // SonyPods 3f701ff8 V2 Table1 tests: inner DATA_MDR prefix removed.
        assertEquals(1, SonyAudioSettingsCodec.audio(b(0xE7, 1, 1))!!.value)
        assertEquals(3, SonyAudioSettingsCodec.audio(b(0xE1, 11, 3))!!.value)
        assertEquals(1, SonyAudioSettingsCodec.speak(true, b(0xF7, 2, 0, 1))!!.value)
        assertEquals(0, SonyAudioSettingsCodec.speak(true, b(0xF7, 12, 1, 1))!!.value)
        assertEquals(1, SonyAudioSettingsCodec.speak(false, b(0xF7, 5, 0, 1))!!.value)
        assertEquals(0, SonyAudioSettingsCodec.speak(false, b(0xF9, 5, 1, 0))!!.value)
        for (bad in listOf(b(0xE7, 1), b(0xE7, 1, 2), b(0xE7, 1, 1, 0), b(0xE1, 1, 4),
            b(0xE3, 5, 2), b(0xE2, 5, 0), b(0xE8, 1, 1), b(0xE7, 0, 2))) assertNull(SonyAudioSettingsCodec.audio(bad))
        for (bad in listOf(b(0xF7, 2, 0), b(0xF7, 2, 0, 0), b(0xF7, 2, 2, 1), b(0xF3, 2, 0, 2)))
            assertNull(SonyAudioSettingsCodec.speak(true, bad))
    }
    @Test fun discoveryUsesOnlyExactFunctionListAndSourcePreference() {
        assertEquals(SonyAudioSettingsCodec.Selection(1, 5, 12), SonyAudioSettingsCodec.functions(true,
            b(7, 0, 7, 0xED, 0, 0xE2, 1, 0xE1, 2, 0xE7, 3, 0xE3, 4, 0xF2, 7, 0xFC, 6)))
        assertEquals(SonyAudioSettingsCodec.Selection(null, null, 5), SonyAudioSettingsCodec.functions(false, b(7, 0, 3, 0xE1, 0xE2, 0xF5)))
        assertNull(SonyAudioSettingsCodec.functions(true, b(7, 0, 2, 0xE2, 0)))
    }
}
