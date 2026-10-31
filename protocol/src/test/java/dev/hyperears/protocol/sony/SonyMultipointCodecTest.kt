package dev.hyperears.protocol.sony

import org.junit.Assert.*
import org.junit.Test

class SonyMultipointCodecTest {
    private fun b(vararg p: Int) = p.map(Int::toByte).toByteArray()
    @Test fun sourceCapabilityFixtureSelectsSemanticSlotAndExactLength() {
        val title = "MULTIPOINT_SETTING".toByteArray()
        val p = b(0xD1, 0xD2, 0, 1, title.size) + title + b(0)
        assertTrue(SonyMultipointCodec.capability(p)!!.multipoint)
        assertEquals(0xD2, SonyMultipointCodec.capability(p)!!.slot)
        assertNull(SonyMultipointCodec.capability(p.dropLast(1).toByteArray()))
        assertNull(SonyMultipointCodec.capability(p + b(0)))
        assertFalse(SonyMultipointCodec.capability(p.clone().apply { this[3] = 0 })!!.multipoint)
        assertNull(SonyMultipointCodec.capability(p.clone().apply { this[5] = 0xFF.toByte() }))
    }
    @Test fun reportsHaveNoBooleanDefaultOrCrossDomainFallback() {
        assertEquals(0xD2 to true, SonyMultipointCodec.value(b(0xD7, 0xD2, 0, 0)))
        assertEquals(0xD2 to false, SonyMultipointCodec.value(b(0xD9, 0xD2, 0, 1)))
        assertEquals(0xD2 to true, SonyMultipointCodec.status(b(0xD3, 0xD2, 0)))
        assertNull(SonyMultipointCodec.value(b(0xD8, 0xD2, 0, 0)))
        assertNull(SonyMultipointCodec.value(b(0xD7, 0xD2, 1, 0)))
        assertNull(SonyMultipointCodec.status(b(0xD3, 0xD2, 3)))
        assertEquals(7, SonyMultipointCodec.alert(b(0x99, 0, 7, 0)))
        for (p in listOf(b(0x99, 4, 7, 0), b(0x99, 0, 7, 1), b(0x99, 0, 113, 0), b(0x99, 0, 7)))
            assertNull(SonyMultipointCodec.alert(p))
        assertNull(SonyMultipointCodec.slots(false, b(7, 0, 1, 0xD2)))
    }
}
