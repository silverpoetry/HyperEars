package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyQuickAccessCodecTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    @Test fun capabilityRejectsEveryTruncationAndKeepsRawServiceIds() {
        val valid=b(0xF1,13,1,1,1,1,200,2,0,200)
        assertEquals(listOf(0,200),SonyQuickAccessCodec.capability(valid)!!.actions.single().services)
        for(size in 0 until valid.size)assertNull(SonyQuickAccessCodec.capability(valid.copyOf(size)))
        assertNull(SonyQuickAccessCodec.capability(valid+b(0)))
        assertNull(SonyQuickAccessCodec.capability(b(0xF1,13,1,1,1,1,200,2,200,200)))
        assertNull(SonyQuickAccessCodec.capability(b(0xF1,13,1,1,2,1,0,1,0,1,0,1,0)))
    }
    @Test fun statusAndValuesCannotBeConfusedWithWritesOrPartialFrames() {
        assertEquals(true,SonyQuickAccessCodec.availability(b(0xF3,13,0)))
        assertEquals(false,SonyQuickAccessCodec.availability(b(0xF5,13,1)))
        assertNull(SonyQuickAccessCodec.availability(b(0xF3,13,2)))
        assertNull(SonyQuickAccessCodec.values(b(0xF8,13,1,1)))
        assertNull(SonyQuickAccessCodec.values(b(0xF7,13,2,1)))
        assertEquals(listOf(200),SonyQuickAccessCodec.values(b(0xF9,13,1,200)))
    }
}
