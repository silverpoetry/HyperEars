package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyPowerCodecTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun discoveryAndCommandsAreVersionSpecific() {
        assertEquals(true, SonyPowerCodec.available(false, b(7, 0, 1, 0x21)))
        assertEquals(false, SonyPowerCodec.available(false, b(7, 0, 1, 0x23)))
        assertEquals(true, SonyPowerCodec.available(true, b(7, 0, 1, 0x23, 0)))
        assertEquals(false, SonyPowerCodec.available(true, b(7, 0, 0)))
        assertNull(SonyPowerCodec.available(true, b(7, 0, 1, 0x23)))
        assertNull(SonyPowerCodec.available(true, b(7, 0, 2, 0x23, 0, 0x23, 1)))
        assertArrayEquals(b(0x22, 0, 1), SonyPowerCodec.powerOff(false))
        assertArrayEquals(b(0x24, 3, 1), SonyPowerCodec.powerOff(true))
    }
}
