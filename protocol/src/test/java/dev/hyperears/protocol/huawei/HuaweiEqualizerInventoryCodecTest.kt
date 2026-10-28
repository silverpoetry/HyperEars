package dev.hyperears.protocol.huawei
import org.junit.Assert.*
import org.junit.Test
class HuaweiEqualizerInventoryCodecTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun explicitEmptyInventoryAndLiteralWriteHaveSourceShape() {
        val empty=HuaweiFreebudsSppCodec.parseFrame(hex("5a0012002b4a0101010201010302010205010a080081e2"))!!
        val inventory=HuaweiEqualizerInventoryCodec.report(empty)!!
        assertTrue(inventory.complete);assertTrue(inventory.slots.isEmpty())
        assertArrayEquals(hex("5a001b002b4901016402010a030a000000000000000000000401410501018029"),HuaweiEqualizerInventoryCodec.write(0x64,"A",List(10){0},false))
        val partial=HuaweiFreebudsSppCodec.parseFrame(hex("5a0006002b4a020164d195"))!!
        assertFalse(HuaweiEqualizerInventoryCodec.report(partial)!!.complete)
        assertTrue(HuaweiEqualizerInventoryCodec.validName("中".repeat(8)))
        assertFalse(HuaweiEqualizerInventoryCodec.validName("中".repeat(9)))
        assertFalse(HuaweiEqualizerInventoryCodec.validName("x\u0000y"))
    }
}
