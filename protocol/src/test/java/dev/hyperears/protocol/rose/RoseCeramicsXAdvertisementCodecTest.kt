package dev.hyperears.protocol.rose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoseCeramicsXAdvertisementCodecTest {
    @Test
    fun parsesCapturedAudioDeviceAddressSuffix() {
        val advertisement = RoseCeramicsXAdvertisementCodec.parse(
            hex("01 09 00 01 02 03 04 D7 84 04 64 64 00"),
        )

        assertEquals(0xD784, advertisement?.audioDeviceAddressSuffix)
    }

    @Test
    fun rejectsTruncatedOrStructurallyDifferentPayloads() {
        assertNull(RoseCeramicsXAdvertisementCodec.parse(hex("01 09 00 D7 84")))
        assertNull(
            RoseCeramicsXAdvertisementCodec.parse(
                hex("02 09 00 01 02 03 04 D7 84 04 64 64 00"),
            ),
        )
        assertNull(
            RoseCeramicsXAdvertisementCodec.parse(
                hex("01 08 00 01 02 03 04 D7 84 04 64 64 00"),
            ),
        )
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
