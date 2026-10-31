package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyDevicesCodecTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    @Test fun classAndUtf8NameHaveExactByteBoundaries() {
        val name="耳机".toByteArray(Charsets.UTF_8)
        val valid=b(0x37,2,1)+"00:11:22:33:44:55".toByteArray()+b(1,1,2,3,name.size)+name+b(1)
        val parsed=SonyDevicesCodec.parse(valid) as SonyDevicesCodec.Devices
        assertEquals("耳机",parsed.devices.single().name);assertEquals(0x010203,parsed.devices.single().deviceClass)
        for(size in 0 until valid.size)assertNull(SonyDevicesCodec.parse(valid.copyOf(size)))
        assertNull(SonyDevicesCodec.parse(valid+b(0)))
        assertNull(SonyDevicesCodec.parse(valid.copyOf().apply{this[lastIndex]=2}))
    }
    @Test fun duplicateAddressesAndConnectionSlotsAreRejected() {
        val peer="00:11:22:33:44:55".toByteArray()+b(1,0)
        assertNull(SonyDevicesCodec.parse(b(0x37,0,2)+peer+peer+b(1)))
        val other="00:11:22:33:44:66".toByteArray()+b(1,0)
        assertNull(SonyDevicesCodec.parse(b(0x37,0,2)+peer+other+b(1)))
        assertNull(SonyDevicesCodec.parse(b(0x33,0,0,2)))
        assertNull(SonyDevicesCodec.parse(b(0x39,1,0,1)))
    }
}
