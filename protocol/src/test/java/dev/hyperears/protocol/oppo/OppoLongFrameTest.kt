package dev.hyperears.protocol.oppo

import org.junit.Assert.*
import org.junit.Test

class OppoLongFrameTest {
    @Test fun captureBackedLengthHeaderDecodesWithoutMinusOneAdjustment() {
        // QuickBuds 6e069325 testdata frame-over127-01: captured header; synthetic body here.
        val bytes = byteArrayOf(0xAA.toByte(), 0xB5.toByte(), 1, 0, 0, 0x1F, 1, 0xD5.toByte(), 0xAE.toByte(), 0) + ByteArray(174)
        val frames = OppoWireCodec.Decoder().offer(bytes)
        assertEquals("Canonical long length 181 must match inner payload 174", 1, frames.size)
        assertEquals(174, frames.single().payload.size)
    }

    @Test fun legacyAndCanonicalLengthsRemainDistinctAcrossBoundary() {
        for (n in listOf(0, 1, 119, 120, 121, 122, 124, 174, 248)) {
            val payload = ByteArray(n) { (it * 7).toByte() }
            val legacy = OppoWireCodec.packet(0x8122, 3, payload)
            val canonical = OppoWireCodec.canonicalPackets(0x8122, 3, payload, 660).single()
            for (raw in listOf(legacy, canonical)) {
                val decoder = OppoWireCodec.Decoder()
                val frames = raw.flatMap { decoder.offer(byteArrayOf(it)) }
                assertEquals("size=$n", 1, frames.size)
                assertArrayEquals(payload, frames.single().payload)
                assertEquals(n >= 121 && raw === canonical, frames.single().canonicalLongLength)
            }
            if (n <= 120) assertArrayEquals(legacy, canonical)
        }
    }
    @Test fun canonicalGoldenAndNoMinusOneVariant() {
        val packet = OppoWireCodec.canonicalPackets(0x0418, 3, ByteArray(124) { it.toByte() }, 660).single()
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x83.toByte(), 1, 0, 0, 0x18, 4, 3, 124, 0), packet.copyOf(10))
        val wrong = packet.clone().apply { this[1] = 0x82.toByte() }
        val valid = OppoWireCodec.packet(0x8106, payload = byteArrayOf(1, 50))
        assertEquals(listOf(0x8106), OppoWireCodec.Decoder().offer(wrong + valid).map { it.command })
    }
    @Test fun sourceLiteralFragmentLayoutReassemblesExactly() {
        // QuickBuds FramerTest / frame-slice-01 TEST-LITERAL, not a hardware capture.
        val inner = byteArrayOf(0x15, 0x81.toByte(), 4, 0x2C, 1) + ByteArray(300) { it.toByte() }
        val packets = listOf(slice(1, 0, inner.copyOfRange(0, 100)),
            slice(2, 1, inner.copyOfRange(100, 200)), slice(3, 2, inner.copyOfRange(200, 305)))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x67, 1, 0, 0), packets.first().copyOf(5))
        val decoder = OppoWireCodec.Decoder()
        assertTrue(decoder.offer(packets[0]).isEmpty())
        assertTrue(decoder.offer(packets[1]).isEmpty())
        val frame = decoder.offer(packets[2]).single()
        assertEquals(0x8115, frame.command)
        assertEquals(4, frame.sequence)
        assertTrue(frame.fragmented)
        assertFalse(frame.canonicalLongLength)
        assertArrayEquals(inner.copyOfRange(5, inner.size), frame.payload)
    }
    @Test fun encoderHonorsBudgetAcrossVarintAndFragmentBoundaries() {
        for ((length, budget) in listOf(1500 to 660, 600 to 100, 5000 to 129, 700 to 660, 357 to 10, 65535 to 660)) {
            val payload = ByteArray(length) { (it * 3).toByte() }
            val packets = OppoWireCodec.canonicalPackets(0x8122, 0xD5, payload, budget)
            assertTrue(packets.all { it.size <= budget })
            val decoder = OppoWireCodec.Decoder()
            val frames = packets.flatMap { decoder.offer(it) }
            assertEquals(1, frames.size)
            assertArrayEquals(payload, frames.single().payload)
        }
    }
    @Test fun gapsDuplicatesResetAndWholeFrameAbortPendingAssembly() {
        val packets = OppoWireCodec.canonicalPackets(0x8122, payload = ByteArray(300), frameBudget = 110)
        val tail = OppoWireCodec.packet(0x8106, payload = byteArrayOf(1, 55))
        for (bad in listOf(listOf(packets[0], packets[2]),
            listOf(packets[0], packets[1], packets[1], packets[2]), listOf(packets[1], packets[2]))) {
            val decoder = OppoWireCodec.Decoder()
            assertTrue(bad.flatMap { decoder.offer(it) }.isEmpty())
            assertEquals(0x8106, decoder.offer(tail).single().command)
            assertEquals(1, packets.flatMap { decoder.offer(it) }.size)
        }
        val decoder = OppoWireCodec.Decoder()
        decoder.offer(packets[0]); decoder.reset()
        assertTrue(packets.drop(1).flatMap { decoder.offer(it) }.isEmpty())
        decoder.offer(packets[0])
        assertEquals(0x8106, decoder.offer(tail).single().command)
        assertTrue(packets.drop(1).flatMap { decoder.offer(it) }.isEmpty())
    }
    @Test fun sequenceDoesNotWrapAndOversizedStreamsRecover() {
        val decoder = OppoWireCodec.Decoder(1)
        val innerHeader = byteArrayOf(0x22, 0x81.toByte(), 0, 0xFF.toByte(), 0xFF.toByte())
        assertTrue(decoder.offer(slice(1, 0, innerHeader)).isEmpty())
        for (fsn in 1..255) assertTrue(decoder.offer(slice(2, fsn, byteArrayOf(0))).isEmpty())
        assertTrue(decoder.offer(slice(3, 0, byteArrayOf(0))).isEmpty())
        // Noncanonical/oversized length and a huge noise chunk must not retain unbounded input.
        assertTrue(decoder.offer(byteArrayOf(0xAA.toByte(), 0xFF.toByte(), 0x7F) + ByteArray(200_000)).isEmpty())
        assertEquals(1, decoder.offer(OppoWireCodec.queryBattery).size)
        try {
            OppoWireCodec.canonicalPackets(0x8122, payload = ByteArray(65535), frameBudget = 10)
            fail("Sequence wrap must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun mismatchedInnerLengthAndUnknownLinkFieldsNeverPublish() {
        val valid = OppoWireCodec.canonicalPackets(0x8122, payload = ByteArray(124), frameBudget = 660).single()
        for (index in listOf(3, 4, 8)) {
            val bad = valid.clone().apply { this[index] = 4 }
            assertTrue(OppoWireCodec.Decoder().offer(bad).isEmpty())
        }
        val inner = byteArrayOf(0x22, 0x81.toByte(), 0, 3, 0, 0, 0)
        assertTrue(OppoWireCodec.Decoder().offer(slice(1, 0, inner.copyOf(5)) + slice(3, 1, inner.copyOfRange(5, 7))).isEmpty())
    }
    @Test fun frameBudgetRequiresExactSuccessfulBoundedReply() {
        fun parse(vararg payload: Int) = OppoWireCodec.parseFrameBudget(OppoWireCodec.Decoder().offer(
            OppoWireCodec.packet(0x8107, payload = payload.map(Int::toByte).toByteArray())).single())
        assertEquals(100, parse(0, 100, 0))
        for (p in listOf(intArrayOf(0), intArrayOf(1, 100, 0), intArrayOf(0, 9, 0), intArrayOf(0, 255, 255), intArrayOf(0, 100, 0, 0))) {
            assertNull(parse(*p))
        }
    }
    private fun slice(control: Int, fsn: Int, data: ByteArray): ByteArray =
        byteArrayOf(0xAA.toByte(), (3 + data.size).toByte(), control.toByte(), 0, fsn.toByte()) + data
}
