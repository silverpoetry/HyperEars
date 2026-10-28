package dev.hyperears.protocol.nicehck

import org.junit.Assert.*
import org.junit.Test

class NiceHckSettingsCodecTest {
    @Test fun independentToggleReportsDoNotReproduceSourceGameFallthrough() {
        NiceHckSettingsCodec.Toggle.entries.forEach { setting ->
            for (value in 0..1) {
                assertEquals(setting to (value == 1), NiceHckSettingsCodec.parseToggle(
                    NiceHckWireCodec.Frame(setting.query, byteArrayOf(value.toByte()))))
            }
            assertNull(NiceHckSettingsCodec.parseToggle(NiceHckWireCodec.Frame(setting.write, byteArrayOf(1))))
            for (bytes in listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(-1), byteArrayOf(1, 0))) {
                assertNull(NiceHckSettingsCodec.parseToggle(NiceHckWireCodec.Frame(setting.query, bytes)))
            }
        }
        assertArrayEquals(byteArrayOf(0x4E, 4, 0, 0, 0xE1.toByte(), 2, 1),
            NiceHckSettingsCodec.setToggle(NiceHckSettingsCodec.Toggle.WIND_SUPPRESSION, true))
    }

    @Test fun firmwareOrderAndEqualizerValuesMatchActualPinnedSource() {
        assertEquals(NiceHckSettingsCodec.Firmware(4, 8), NiceHckSettingsCodec.parseFirmware(
            NiceHckWireCodec.Frame(3, byteArrayOf(8, 4))))
        assertNull(NiceHckSettingsCodec.parseFirmware(NiceHckWireCodec.Frame(3, byteArrayOf(8))))
        assertNull(NiceHckSettingsCodec.parseFirmware(NiceHckWireCodec.Frame(3, byteArrayOf(8, 4, 0))))
        for (preset in 0..6) {
            assertEquals(preset, NiceHckSettingsCodec.parseEqualizer(
                NiceHckWireCodec.Frame(0x0107, byteArrayOf(preset.toByte()))))
            assertArrayEquals(byteArrayOf(0x4E, 4, 0, 0, 7, 2, preset.toByte()),
                NiceHckSettingsCodec.setEqualizer(preset))
        }
        assertNull(NiceHckSettingsCodec.parseEqualizer(NiceHckWireCodec.Frame(0x0107, byteArrayOf(7))))
        assertNull(NiceHckSettingsCodec.parseEqualizer(NiceHckWireCodec.Frame(0x0207, byteArrayOf(1))))
    }
}
