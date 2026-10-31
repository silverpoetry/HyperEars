package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyLeAudioCodecTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun requiresCompleteHbsAndSettingDiscovery() {
        assertEquals(SonyLeAudioCodec.Report.Capability(true), SonyLeAudioCodec.parse(b(7,0,2,0x41,0,0x42,1)))
        assertEquals(SonyLeAudioCodec.Report.Capability(false), SonyLeAudioCodec.parse(b(7,0,1,0x42,1)))
        assertNull(SonyLeAudioCodec.parse(b(7,0,2,0x41,0,0x42)))
        assertNull(SonyLeAudioCodec.parse(b(7,0,2,0x42,0,0x42,1)))
    }
    @Test fun identityRejectsPartialExtraMalformedAndDuplicateAddresses() {
        val classic = "00:11:22:33:44:55"
        val lea = "00:11:22:33:44:66"
        val bytes = b(0x41,1) + (classic + lea).encodeToByteArray()
        assertEquals(SonyLeAudioCodec.Report.Identity(classic,listOf(lea)), SonyLeAudioCodec.parse(bytes))
        assertNull(SonyLeAudioCodec.parse(bytes + b(0)))
        assertNull(SonyLeAudioCodec.parse(bytes.copyOf(bytes.size-1)))
        assertNull(SonyLeAudioCodec.parse(b(0x41,1)+(classic+classic).encodeToByteArray()))
        assertNull(SonyLeAudioCodec.parse(b(0x41,1)+(classic+"ZZ:11:22:33:44:66").encodeToByteArray()))
    }
    @Test fun notificationIsDistinctFromReadbackAndUnknownDoesNotBecomeEnabled() {
        assertEquals(SonyLeAudioCodec.Report.Setting(true,false),SonyLeAudioCodec.parse(b(0x47,12,0)))
        assertEquals(SonyLeAudioCodec.Report.Setting(true,true),SonyLeAudioCodec.parse(b(0x49,12,0)))
        assertEquals(SonyLeAudioCodec.Report.Setting(null,true),SonyLeAudioCodec.parse(b(0x49,12,255)))
        assertNull(SonyLeAudioCodec.parse(b(0x49,12,2)))
        assertEquals(SonyLeAudioCodec.Report.Availability(false),SonyLeAudioCodec.parse(b(0x43,12,1)))
        assertArrayEquals(b(0x48,12,0,0),SonyLeAudioCodec.setting(true,true))
        assertArrayEquals(b(0x48,12,1,1),SonyLeAudioCodec.setting(false,false))
    }
}
