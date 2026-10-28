package dev.hyperears.protocol.oppo

import org.junit.Assert.*
import org.junit.Test

class OppoProductIdentityTest {
    private fun parse(payload: ByteArray, command: Int = 0x8103): Int? {
        val frame = OppoWireCodec.Decoder().offer(OppoWireCodec.packet(command, payload = payload)).single()
        return OppoWireCodec.parseProductId(frame)
    }
    @Test fun productIdIsExactlyThreeLittleEndianBytesAfterSuccess() {
        assertEquals(0x067410, parse(byteArrayOf(0, 0x10, 0x74, 6)))
        assertEquals(0xF85450, parse(byteArrayOf(0, 0x50, 0x54, 0xF8.toByte())))
        assertEquals(0x123456, parse(byteArrayOf(0, 0x56, 0x34, 0x12)))
        assertArrayEquals(OppoWireCodec.packet(0x0103), OppoWireCodec.queryProductId)
    }
    @Test fun malformedStatusCommandAndLengthDoNotIdentifyProduct() {
        for (size in 0..6) {
            if (size != 4) assertNull(parse(ByteArray(size)))
        }
        assertNull(parse(byteArrayOf(1, 0x10, 0x74, 6)))
        assertNull(parse(byteArrayOf(0, 0x10, 0x74, 6), 0x0103))
        assertNull(parse(byteArrayOf(0, 0x10, 0x74, 6), 0x8106))
    }
    @Test fun fragmentedAndCoalescedIdentityFramesKeepBoundaries() {
        val bytes = OppoWireCodec.packet(0x8103, payload = byteArrayOf(0, 0x10, 0x74, 6))
        val decoder = OppoWireCodec.Decoder()
        assertTrue(decoder.offer(bytes.copyOfRange(0, 8)).isEmpty())
        val frames = decoder.offer(bytes.copyOfRange(8, bytes.size) + bytes)
        assertEquals(listOf(0x067410, 0x067410), frames.map(OppoWireCodec::parseProductId))
        decoder.offer(bytes.copyOfRange(0, 8))
        decoder.reset()
        assertEquals(0x067410, OppoWireCodec.parseProductId(decoder.offer(bytes).single()))
    }
}
