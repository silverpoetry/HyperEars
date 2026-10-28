package dev.hyperears.protocol.samsung
import org.junit.Assert.*
import org.junit.Test
class SamsungFitTestCodecTest {
    @Test fun exactNineProfilesAndStrictResultDomain() {
        val expected=setOf("BUDS2","BUDS2_PRO","BUDS_FE","BUDS_CORE","BUDS3","BUDS3_PRO","BUDS3_FE","BUDS4","BUDS4_PRO")
        assertEquals(expected,SamsungBudsCodec.Model.entries.filter(SamsungFitTestCodec::supported).map{it.name}.toSet())
        fun result(id:Int,vararg data:Int)=SamsungFitTestCodec.result(SamsungBudsCodec.parseFrame(SamsungBudsCodec.packet(id,data.map(Int::toByte).toByteArray()))!!)
        assertEquals(listOf(0,1),result(158,0,1));assertEquals(listOf(2,2),result(158,2,2))
        assertNull(result(157,0,1));assertNull(result(158,1));assertNull(result(158,1,0,0));assertNull(result(158,3,1))
        // Independently generated CRC-CCITT/XMODEM bytes from source SppMessage framing.
        fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(hex("fd04009d01967edd"),SamsungFitTestCodec.command(SamsungBudsCodec.Model.BUDS2,true))
        assertArrayEquals(hex("fd04009d00b76edd"),SamsungFitTestCodec.command(SamsungBudsCodec.Model.BUDS2,false))
        assertEquals(listOf(0,1),SamsungFitTestCodec.result(SamsungBudsCodec.parseFrame(hex("fd05009e00011973dd"))!!))
        val frame=SamsungBudsCodec.parseFrame(SamsungFitTestCodec.command(SamsungBudsCodec.Model.BUDS2,true))!!
        assertEquals(157,frame.id);assertArrayEquals(byteArrayOf(1),frame.payload)
    }
}
