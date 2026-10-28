package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseRfcommSettingsCodecTest {
    @Test fun opaqueTouchAndUnknownFieldsCannotForgeNoiseOrBatteryCapabilities() {
        val touch = ByteArray(21).also { it[0] = 1; it[3] = 2; it[4] = 9; it[5] = 1 }
        val extension = byteArrayOf(5, 85, 2, 9, 1, 0, 2, 14, 1)
        val states = RoseBudsFeelMk2WireCodec.Decoder(true).offer(frame(touch + extension, 21))
        assertTrue(states.none { it is RoseBudsFeelMk2WireCodec.State.Noise || it is RoseBudsFeelMk2WireCodec.State.Battery })
        assertEquals(mapOf(14 to 1), states.filterIsInstance<RoseBudsFeelMk2WireCodec.State.Settings>().single().values)
    }
    private fun frame(payload: ByteArray, declared: Int = payload.size): ByteArray {
        val body = byteArrayOf(0xDD.toByte(), 0, declared.toByte()) + payload
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), 0xAA.toByte())
    }
    private fun settings(bytes: ByteArray) = RoseBudsFeelMk2WireCodec.Decoder(true).offer(bytes)
        .filterIsInstance<RoseBudsFeelMk2WireCodec.State.Settings>().singleOrNull()?.values
    @Test fun touchPrefixIsOpaqueAndAllScalarRangesAreDecoded() {
        val touch = ByteArray(21).also { it[0] = 1; it[4] = 2; it[5] = 0x2A; it[6] = 3 }
        val fields = byteArrayOf(2, 9, 5, 2, 14, 1, 2, 42, 2, 2, 44, 3, 2, 45, 5, 2, 7, 1, 2, 46, 4)
        assertEquals(mapOf(9 to 5, 14 to 1, 42 to 2, 44 to 3, 45 to 5, 7 to 1, 46 to 4), settings(frame(touch + fields, 21)))
        assertNull(settings(frame(touch, 21)))
        assertArrayEquals(byteArrayOf(-1, 0, 2, 44, 3, 48, -86), RoseRfcommSettingsCodec.set(0, 44, 3))
    }
    @Test fun unknownBlocksAreSkippedNotRecursivelyScanned() {
        assertEquals(mapOf(9 to 4), settings(frame(byteArrayOf(2, 9, 4) + ByteArray(18), 21)))
        assertNull(settings(frame(byteArrayOf(2, 9, 4, 0, 2, 14, 1))))
        assertEquals(mapOf(14 to 1), settings(frame(byteArrayOf(5, 85, 2, 42, 3, 0, 2, 14, 1))))
        assertNull(settings(frame(byteArrayOf(2, 14, 1, 3, 42, 3))))
        assertNull(settings(frame(byteArrayOf(3, 42, 3, 0))))
        assertNull(settings(frame(byteArrayOf(2, 14, 1, 2, 14, 0))))
        assertNull(settings(frame(byteArrayOf(14, 2))))
    }
    @Test fun strictSettingsRemainStreamedAndChecksumProtected() {
        val packet = frame(byteArrayOf(14, 1))
        val decoder = RoseBudsFeelMk2WireCodec.Decoder(true)
        assertTrue(decoder.offer(packet.copyOfRange(0, 4)).isEmpty())
        assertEquals(1, decoder.offer(packet.copyOfRange(4, packet.size)).filterIsInstance<RoseBudsFeelMk2WireCodec.State.Settings>().size)
        assertTrue(RoseBudsFeelMk2WireCodec.Decoder().offer(packet).isEmpty())
        val invalid = packet.copyOf().also { it[it.size - 2] = 0 }
        assertNull(settings(invalid))
    }
}
