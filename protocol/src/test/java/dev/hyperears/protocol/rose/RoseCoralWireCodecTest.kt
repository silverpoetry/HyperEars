package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

/** Fixtures are independent source reconstructions, not captured Coral hardware exchanges. */
class RoseCoralWireCodecTest {
    @Test fun transmitGoldensAndSequenceWrap() {
        assertArrayEquals(hex("FF 00 02 09 01 0B AA"), RoseCoralWireCodec.command(0, hex("09 01")))
        assertArrayEquals(hex("FF FF 02 09 02 0B AA"), RoseCoralWireCodec.command(255, hex("09 02")))
        assertArrayEquals(hex("FF 00 03 2A 05 02 33 AA"), RoseCoralWireCodec.command(0, hex("2A 05 02")))
    }

    @Test fun everyFragmentBoundaryAndCoalescedLtvChain() {
        val frame = report(4, 12, 0xDD, 92, 0xE4, 2, 9, 1, 2, 14, 0)
        val expected = listOf(
            RoseCoralWireCodec.State.Battery(0xDD, 92, 0xE4),
            RoseCoralWireCodec.State.Value(9, 1), RoseCoralWireCodec.State.Value(14, 0),
        )
        for (split in 0..frame.size) {
            val decoder = RoseCoralWireCodec.Decoder()
            assertEquals(expected, decoder.offer(frame.copyOfRange(0, split)) + decoder.offer(frame.copyOfRange(split, frame.size)))
        }
        assertEquals(expected + expected, RoseCoralWireCodec.Decoder().offer(frame + frame))
    }

    @Test fun unknownOpaqueValuesIncludingCompleteFramesNeverProduceKnownStates() {
        val inner = report(2, 9, 1).map { it.toInt() and 255 }
        val frame = report(*(listOf(inner.size + 2, 0x77) + inner + 0xAA).toIntArray())
        val decoder = RoseCoralWireCodec.Decoder()
        for (byte in frame) assertTrue(decoder.offer(byteArrayOf(byte)).isEmpty())
        assertEquals(listOf(RoseCoralWireCodec.State.Value(9, 2)), decoder.offer(report(2, 9, 2)))
    }

    @Test fun strictDomainShapeAndMalformedReportsDoNotInventState() {
        listOf(
            intArrayOf(2, 9, 3), intArrayOf(2, 9, 7), intArrayOf(3, 9, 1, 0),
            intArrayOf(3, 12, 20, 30),
            intArrayOf(2, 14, 2), intArrayOf(2, 0x32, 2), intArrayOf(2, 7, 3),
            intArrayOf(2, 0x2A, 2), intArrayOf(3, 0x2A, 4, 2), intArrayOf(3, 0x2A, 5, 3),
            intArrayOf(2, 0x2E, 0), intArrayOf(2, 0x2E, 6), intArrayOf(2, 0x2F, 7),
        ).forEach { assertTrue(RoseCoralWireCodec.Decoder().offer(report(*it)).isEmpty()) }
        val bad = report(2, 9, 1).also { it[it.lastIndex - 1] = 0 }
        assertTrue(RoseCoralWireCodec.Decoder().offer(bad).isEmpty())
    }

    @Test fun corruptAndOversizedInputRecoversWithinBoundAndResetDiscardsFragments() {
        val decoder = RoseCoralWireCodec.Decoder()
        val good = report(2, 9, 1)
        val bad = report(2, 9, 2).also { it[it.lastIndex - 1] = 0 }
        assertEquals(listOf(RoseCoralWireCodec.State.Value(9, 1)),
            decoder.offer(bad + ByteArray(2048) + good))
        decoder.offer(good.copyOfRange(0, 4))
        decoder.reset()
        assertTrue(decoder.offer(good.copyOfRange(4, good.size)).isEmpty())
        assertEquals(listOf(RoseCoralWireCodec.State.Value(9, 1)), decoder.offer(good))
    }

    @Test fun touchRequiresAllEightUniqueValidPairs() {
        val pairs = intArrayOf(1, 0, 2, 1, 3, 2, 4, 3, 0x11, 4, 0x12, 5, 0x13, 6, 0x14, 8)
        assertEquals(listOf(RoseCoralWireCodec.State.Touch(listOf(0, 1, 2, 3, 4, 5, 6, 8))),
            RoseCoralWireCodec.Decoder().offer(report(17, 1, *pairs)))
        assertTrue(RoseCoralWireCodec.Decoder().offer(report(17, 1, *pairs.copyOf().also { it[14] = 1 })).isEmpty())
        assertTrue(RoseCoralWireCodec.Decoder().offer(report(17, 1, *pairs.copyOf().also { it[15] = 9 })).isEmpty())
        assertTrue(RoseCoralWireCodec.Decoder().offer(report(15, 1, *pairs.copyOf(14))).isEmpty())
        assertTrue(RoseCoralWireCodec.Decoder().offer(report(9, 1, 0, 1, 2, 3, 4, 5, 6, 8)).isEmpty())
    }

    private fun report(vararg body: Int): ByteArray {
        val head = byteArrayOf(0xDD.toByte(), 0) + body.map(Int::toByte).toByteArray()
        return head + byteArrayOf(head.sumOf { it.toInt() and 255 }.toByte(), 0xAA.toByte())
    }
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
