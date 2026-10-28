package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class SamsungConversationTimeoutTest {
    private fun report(value:Int,size:Int=50)=Wire.Frame(0x61,ByteArray(size).apply { this[0]=13;this[9]=3;this[11]=0x22;if(size>27)this[27]=value.toByte() })
    @Test fun durationRequiresBuds2ProReportAndAckDoesNotFabricateCapability() {
        val session=SamsungDailySettingsSession(Wire.Model.BUDS2_PRO)
        val request=SamsungDailyControlRequest.SetConversationTimeout(15)
        assertTrue(session.encode(request).isEmpty())
        assertNull(session.offer(Wire.Frame(0x42,byteArrayOf(0x7B,2))))
        session.offer(report(0))
        assertEquals(5,session.state!!.conversationTimeoutSeconds)
        assertArrayEquals(Wire.packet(0x7B,byteArrayOf(2)),session.encode(request).single())
        assertEquals(5,session.state!!.conversationTimeoutSeconds)
        session.offer(Wire.Frame(0x7B,byteArrayOf(2)))
        assertEquals(5,session.state!!.conversationTimeoutSeconds)
        session.offer(Wire.Frame(0x42,byteArrayOf(0x7B,2)))
        assertEquals(15,session.state!!.conversationTimeoutSeconds)
        assertTrue(session.encode(request.copy(seconds=7)).isEmpty())
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
    }
    @Test fun unknownMissingAndOtherModelsCannotExposeTimeout() {
        val session=SamsungDailySettingsSession(Wire.Model.BUDS2_PRO)
        session.offer(report(1));assertEquals(10,session.state!!.conversationTimeoutSeconds)
        session.offer(report(255));assertNull(session.state!!.conversationTimeoutSeconds)
        session.offer(Wire.Frame(0x42,byteArrayOf(0x7B,0)));assertNull(session.state!!.conversationTimeoutSeconds)
        session.offer(report(1,27));assertNull(session.state!!.conversationTimeoutSeconds)
        for(model in listOf(Wire.Model.BUDS_PRO,Wire.Model.BUDS3_PRO,Wire.Model.BUDS4_PRO)) {
            val other=SamsungDailySettingsSession(model);other.offer(report(1))
            assertNull(other.state!!.conversationTimeoutSeconds)
            assertTrue(other.encode(SamsungDailyControlRequest.SetConversationTimeout(5)).isEmpty())
        }
    }
}
