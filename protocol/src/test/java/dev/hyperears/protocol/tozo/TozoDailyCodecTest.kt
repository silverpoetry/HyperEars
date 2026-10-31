package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoDailyCodecTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun capturedReadsAreSettingsButWriteAcknowledgementsAreNot() {
        val samples=listOf("0006010000","0016010000","0025010101","0029010000")
        assertEquals(listOf(false,false,true,false),samples.map{TozoDailyCodec.parse(hex(it))!!.enabled})
        for(setting in TozoDailyCodec.Setting.entries) {
            assertNull(TozoDailyCodec.parse(TozoWireCodec.encode(setting.command+0x1000,byteArrayOf(0))))
            assertNull(TozoDailyCodec.parse(TozoWireCodec.encode(setting.command,byteArrayOf(2))))
            val notification=TozoWireCodec.encode(setting.notification,byteArrayOf(1))
            assertEquals(TozoDailyCodec.Report(setting,true),TozoDailyCodec.parse(notification))
            assertArrayEquals(byteArrayOf(1),TozoWireCodec.decode(TozoDailyCodec.write(setting,true))!!.payload)
        }
    }
    @Test fun liveBatteryNotificationUsesTheSameDecoderAsTheRead() {
        assertEquals(TozoHeadsetCodec.Report.Battery(71,67),TozoHeadsetCodec.parse(TozoWireCodec.encode(0x200e,byteArrayOf(71,67))))
    }
}
