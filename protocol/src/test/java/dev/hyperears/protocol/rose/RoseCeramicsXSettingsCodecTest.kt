package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseCeramicsXSettingsCodecTest {
    @Test fun sourceCommandsAndIndependentReports() {
        val codec = RoseCeramicsXSettingsCodec
        assertArrayEquals(byteArrayOf(0, 0x27, 1, 0, 1, 4), codec.query(4))
        assertArrayEquals(byteArrayOf(0, 0x20, 1, 0, 2, 0, 2), codec.write(4, 2))
        assertArrayEquals(byteArrayOf(0, 0x2B, 1, 0, 1, 0), codec.write(11, 0))
        assertArrayEquals(byteArrayOf(0, 0x30, 1, 0, 1, 2), codec.write(17, 2))
        assertEquals(2, codec.parse(byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 0, 2))?.value)
        assertEquals(0, codec.parse(byteArrayOf(0, 0x28, 2, 0, 3, 8, 1, 0))?.value)
    }
    @Test fun malformedWidthsAndWriteEchoesNeverGrantState() {
        val codec = RoseCeramicsXSettingsCodec
        val report = byteArrayOf(0, 0x27, 2, 0, 3, 11, 1, 2)
        assertEquals(2, codec.parse(report)?.value)
        for (index in listOf(0, 2, 3, 4, 6)) {
            assertNull(codec.parse(report.copyOf().also { it[index] = 9 }))
        }
        assertNull(codec.parse(report + 0))
        assertNull(codec.parse(report.copyOf(7)))
        assertNull(codec.parse(report.copyOf().also { it[7] = 3 })) // Generic LHDC is not Ceramics X.
        assertNull(codec.parse(codec.write(11, 1)!!))
        assertNull(codec.parse(byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 1, 2)))
        assertNull(codec.write(17, 3))
    }
}
