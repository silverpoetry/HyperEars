package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseZtWireCodecTest {
    private val wire = RoseZtWireCodec
    private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun response(group: Int, command: Int, vararg payload: Int): ByteArray {
        val body = listOf(9, 255, 0, 0, 1, group, command, 10 + payload.size, 0) + payload.toList()
        return (body + (body.sum() and 255)).map(Int::toByte).toByteArray()
    }
    @Test fun exactSourceWritesKeepModelVariantsAndInvertedGame() {
        assertArrayEquals(hex("08 EE 00 00 00 06 82 0D 00 00 01 00 8C"), wire.writeNoise(RoseZtWireCodec.Model.I3, 3))
        assertArrayEquals(hex("08 EE 00 00 00 06 82 0E 00 00 00 00 01 8D"), wire.writeNoise(RoseZtWireCodec.Model.HEADFEEL, 3))
        assertNull(wire.writeNoise(RoseZtWireCodec.Model.I3, 2))
        assertArrayEquals(hex("08 EE 00 00 00 06 83 0B 00 00 8A"), wire.write(RoseZtWireCodec.Model.I3, RoseZtWireCodec.Setting.GAME, 0))
        assertArrayEquals(hex("08 EE 00 00 00 08 82 0B 00 01 8C"), wire.write(RoseZtWireCodec.Model.HEADFEEL, RoseZtWireCodec.Setting.MULTIPOINT, 1))
        assertNull(wire.write(RoseZtWireCodec.Model.I3, RoseZtWireCodec.Setting.MULTIPOINT, 1))
        assertArrayEquals(hex("08 EE 00 00 00 02 83 0B 00 03 89"), wire.write(RoseZtWireCodec.Model.HEADFEEL, RoseZtWireCodec.Setting.GAIN, 3))
        assertNull(wire.write(RoseZtWireCodec.Model.HEADFEEL, RoseZtWireCodec.Setting.GAIN, 0))
    }
    @Test fun prefixesAreVariableWidthExactAndConflictsReject() {
        assertEquals(RoseZtWireCodec.Model.I3, RoseZtIdentityCodec.identify(mapOf(0x02F1 to listOf(3))))
        assertEquals(RoseZtWireCodec.Model.I3, RoseZtIdentityCodec.identify(mapOf(0x0201 to listOf(3))))
        assertEquals(RoseZtWireCodec.Model.HEADFEEL, RoseZtIdentityCodec.identify(mapOf(0x5352 to listOf(0, 2, 0, 2))))
        assertNull(RoseZtIdentityCodec.identify(mapOf(0x5352 to listOf(0, 2, 0))))
        assertNull(RoseZtIdentityCodec.identify(mapOf(0x02F1 to listOf(3), 0x5352 to listOf(0, 2, 0, 2))))
        assertNull(RoseZtIdentityCodec.named("EARFREE i5"))
        assertNull(RoseZtIdentityCodec.named("HeadFeel Pro"))
    }
    @Test fun fragmentedReportsResynchronizeAndRequireExactIndependentFieldShape() {
        val bytes = response(2, 3, 2)
        for (split in 0..bytes.size) {
            val decoder = RoseZtWireCodec.Decoder()
            val frames = decoder.offer(bytes.copyOfRange(0, split)) + decoder.offer(bytes.copyOfRange(split, bytes.size))
            assertEquals(RoseZtWireCodec.Setting.GAIN to 2, wire.readSetting(RoseZtWireCodec.Model.HEADFEEL, frames.single()))
            assertNull(wire.readSetting(RoseZtWireCodec.Model.I3, frames.single()))
        }
        val decoder = RoseZtWireCodec.Decoder()
        val bad = bytes.copyOf().also { it[it.lastIndex] = 0 }
        assertEquals(1, decoder.offer(ByteArray(10000) + bad + bytes).size)
        assertTrue(decoder.offer(wire.command(2, 0x83, listOf(2))).isEmpty())
        val wide = decoder.offer(response(2, 3, 2, 0)).single()
        assertNull(wire.readSetting(RoseZtWireCodec.Model.HEADFEEL, wide))
        decoder.offer(bytes.copyOf(5)); decoder.reset()
        assertTrue(decoder.offer(bytes.copyOfRange(5, bytes.size)).isEmpty())
    }
}
