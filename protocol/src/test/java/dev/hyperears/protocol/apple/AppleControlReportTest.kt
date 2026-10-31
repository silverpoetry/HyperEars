package dev.hyperears.protocol.apple

import org.junit.Assert.*
import org.junit.Test

class AppleControlReportTest {
    @Test fun controlsFrameExactlyElevenBytesAcrossEverySplitIncludingEmbeddedPrefix() {
        val first = frame(255, listOf(4, 0, 4, 0))
        val second = frame(1, listOf(2, 128, 254, 255))
        for (split in 1 until (first + second).size) {
            val decoder = AppleAapWireCodec.Decoder()
            val stream = first + second
            val result = decoder.offer(stream.take(split).toByteArray()) + decoder.offer(stream.drop(split).toByteArray())
            assertEquals(listOf(
                AppleAapWireCodec.State.Control(255, listOf(4, 0, 4, 0)),
                AppleAapWireCodec.State.Control(1, listOf(2, 128, 254, 255)),
            ), result)
        }
    }

    @Test fun partialFramesDoNotConfirmAndNoiseRemainsOnExistingTypedPath() {
        val decoder = AppleAapWireCodec.Decoder()
        val packet = frame(0x28, listOf(1, 255, 128, 0))
        assertTrue(decoder.offer(packet.take(10).toByteArray()).isEmpty())
        assertEquals(listOf(AppleAapWireCodec.State.Control(0x28, listOf(1, 255, 128, 0))), decoder.offer(packet.takeLast(1).toByteArray()))
        assertEquals(listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ADAPTIVE)), decoder.offer(frame(0x0d, listOf(4, 0, 0, 0))))
        assertTrue(decoder.offer(frame(0x0d, listOf(255, 0, 0, 0))).isEmpty())
    }

    @Test fun encoderEnforcesExactFourUnsignedBytesAndIdentifier() {
        assertEquals(frame(0x24, listOf(255, 2, 128, 254)).toList(), AppleAapWireCodec.setControl(0x24, listOf(255, 2, 128, 254)).toList())
        listOf(emptyList(), listOf(1), listOf(1, 2, 3), listOf(1, 2, 3, 4, 5), listOf(-1, 0, 0, 0), listOf(256, 0, 0, 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppleAapWireCodec.setControl(1, it) }
        }
        listOf(-1, 256).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppleAapWireCodec.setControl(it, listOf(0, 0, 0, 0)) }
        }
    }

    private fun frame(id: Int, values: List<Int>) = byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte()) + values.map(Int::toByte).toByteArray()
}
