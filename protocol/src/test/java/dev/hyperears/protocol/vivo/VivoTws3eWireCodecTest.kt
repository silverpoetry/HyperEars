package dev.hyperears.protocol.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoTws3eWireCodecTest {
    @Test fun exactSourceCommandsPreserveSideAndParameterBytes() {
        assertArrayEquals(hex("FF 03 00 01 00 1B 01 02 16"), VivoTws3eWireCodec.setDoubleTap(true, 6))
        assertArrayEquals(hex("FF 03 00 01 00 1B 01 02 02"), VivoTws3eWireCodec.setDoubleTap(false, 2))
        assertArrayEquals(hex("FF 03 00 03 00 1B 01 31 05 08 FF"), VivoTws3eWireCodec.setHold(8, 255))
        assertArrayEquals(hex("FF 03 00 01 00 1B 01 18 05"), VivoTws3eWireCodec.setDeepX(5))
        assertArrayEquals(hex("FF 03 00 01 00 1B 01 03 01"), VivoTws3eWireCodec.setWearDetection(true))
        assertArrayEquals(hex("FF 03 00 02 00 1B 01 50 03 02"), VivoTws3eWireCodec.setCallGestures(true, false))
    }
    @Test fun rejectsUnsupportedWireValues() {
        assertThrows(IllegalArgumentException::class.java) { VivoTws3eWireCodec.setDeepX(4) }
        assertThrows(IllegalArgumentException::class.java) { VivoTws3eWireCodec.setDoubleTap(false, 255) }
        assertThrows(IllegalArgumentException::class.java) { VivoTws3eWireCodec.setHold(7, 8) }
    }
    @Test fun fragmentedCoalescedEscapedAndOversizedJson() {
        val decoder = VivoTws3eWireCodec.StatusDecoder()
        val one = """{"params":{"x":"a}\\\"{"},"eventId":"A102|10084"}"""
        val two = """{"params":{}}"""
        val stream = ("noise" + one + two).toByteArray()
        val reports = stream.flatMap { decoder.offer(byteArrayOf(it)) }
        assertEquals(listOf(one, two), reports)
        decoder.offer("{\"x\":\"${"a".repeat(9000)}".toByteArray())
        assertEquals(listOf(two), decoder.offer(two.toByteArray()))
        decoder.offer("{\"params\":".toByteArray()); decoder.reset()
        assertEquals(listOf(two), decoder.offer(two.toByteArray()))
    }
    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
