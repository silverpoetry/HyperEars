package dev.hyperears.protocol.qcy

import org.junit.Assert.*
import org.junit.Test

class QcyAdvancedWireTest {
    @Test fun optionalReadCommandsContainAllSourceCommands() {
        val commands = QcyWireCodec.advancedQueries.flatMap { QcyWireCodec.Decoder().offer(it) }
        assertEquals(setOf(9, 16, 35, 36, 39, 64, 32, 34, 45), commands.map { it.parameters.single().toInt() }.toSet())
        assertTrue(commands.all { it.command == 254 })
    }
    @Test fun toggleValuesAreStrictAndCommandSpecific() {
        QcyWireCodec.Toggle.entries.forEach { toggle ->
            for (value in 0..255) {
                val frame = QcyWireCodec.Frame(toggle.command, byteArrayOf(value.toByte()))
                val expected = when (value) { 1 -> true; toggle.off -> false; else -> null }
                assertEquals("${toggle.name} $value", expected, QcyWireCodec.parseToggle(frame)?.second)
            }
            assertNull(QcyWireCodec.parseToggle(QcyWireCodec.Frame(toggle.command, byteArrayOf(1, 2))))
            val off = QcyWireCodec.Decoder().offer(QcyWireCodec.setToggle(toggle, false)).single()
            assertEquals(toggle.off, off.parameters.single().toInt())
        }
    }
    @Test fun unknownSpatialAndPresetValuesNeverBecomeDefaults() {
        for (value in 0..255) {
            assertEquals(value.takeIf { it in 0..2 }, QcyWireCodec.parseSpatialMode(QcyWireCodec.Frame(45, byteArrayOf(value.toByte()))))
            assertEquals(value.takeIf { it in QcyWireCodec.presetIds }, QcyWireCodec.parseEqPreset(QcyWireCodec.Frame(32, byteArrayOf(value.toByte()))))
        }
    }
    @Test fun customPeqUsesSignedHundredthsLittleEndianAndExactSourceShape() {
        val gains = listOf(-8, 8, -1, 1, 0, 2, 3, -4, 5, 6)
        val packet = QcyWireCodec.setCustomEq(gains)
        assertEquals(77, packet.size)
        assertEquals(listOf(31, 0, 224, 252, 100, 0, 2), packet.drop(7).take(7).map { it.toInt() and 255 })
        val frame = QcyWireCodec.Decoder().offer(packet).single()
        val eq = requireNotNull(QcyWireCodec.parseEqualizer(frame))
        assertEquals(0, eq.masterGainHundredths)
        assertEquals(QcyWireCodec.customFrequencies, eq.bands.map { it.frequency })
        assertEquals(gains.map { it * 100 }, eq.bands.map { it.gainHundredths })
        assertTrue(eq.bands.all { it.qHundredths == 100 && it.type == 2 })
        assertNull(QcyWireCodec.parseEqualizer(frame.copy(parameters = frame.parameters.dropLast(1).toByteArray())))
        assertNull(QcyWireCodec.parseEqualizer(frame.copy(parameters = frame.parameters.dropLast(7).toByteArray())))
        assertNull(QcyWireCodec.parseEqPreset(frame.copy(parameters = frame.parameters.dropLast(1).toByteArray())))
    }
    @Test fun completeOuterFrameIsRequiredBeforeAnyAdvancedReportEscapes() {
        val stream = QcyWireCodec.Decoder()
        assertTrue(stream.offer(byteArrayOf(-1, 6, 9, 1, 1)).isEmpty())
        assertEquals(2, stream.offer(byteArrayOf(16, 1, 2)).size)
        assertTrue(stream.offer(byteArrayOf(-1, 6, 9, 1, 1, 16, 2, 2)).isEmpty())
        stream.offer(byteArrayOf(-1, 3, 9)); stream.reset()
        assertTrue(stream.offer(byteArrayOf(1, 1)).isEmpty())
    }
}
