package dev.hyperears.protocol.bose
import org.junit.Assert.*
import org.junit.Test
class BoseLiveAudioNameCodecTest {
    private fun frame(vararg p:Int)=BoseBmapWireCodec.Decoder().offer(p.map(Int::toByte).toByteArray()).single()
    @Test fun liveAudioWritesPreserveEveryUnownedFieldIncludingAutoCnc() {
        val raw=BoseDailySettingsCodec.parseAudioSettings(frame(31,10,3,5,7,1,0,1,0))!!
        assertArrayEquals(byteArrayOf(31,10,2,5,7,1,2,1,0),BoseDailySettingsCodec.setAudioSettings(raw,2,2))
        assertArrayEquals(byteArrayOf(31,10,2,5,7,1,0,0,0),BoseDailySettingsCodec.setAudioSettings(raw,3,0))
        assertArrayEquals(byteArrayOf(31,10,1,0),BoseDailySettingsCodec.queryAudioSettings())
        assertNull(BoseDailySettingsCodec.parseAudioSettings(frame(31,10,2,5,7,1,0,1,0)))
        assertNull(BoseDailySettingsCodec.parseAudioSettings(frame(1,10,3,5,7,1,0,1,0)))
        assertNull(BoseDailySettingsCodec.parseAudioSettings(frame(31,10,3,6,7,1,0,1,0,0)))
        assertNull(BoseDailySettingsCodec.parseAudioSettings(frame(31,10,3,5,7,1,3,1,0)))
        assertNull(BoseDailySettingsCodec.parseAudioSettings(frame(31,10,3,5,7,2,0,1,0)))
    }
    @Test fun nameUsesStrictUtf8ByteLimitAndFlagIsReadOnlyHeader() {
        val original=BoseDailySettingsCodec.parseDeviceName(frame(1,2,3,6,0,70,97,114,103,111))!!
        assertEquals("Fargo",original.text);assertEquals(0,original.flag)
        assertArrayEquals(byteArrayOf(1,2,2,4,67,97,102,101),BoseDailySettingsCodec.setDeviceName("Cafe"))
        assertEquals(31,BoseDailySettingsCodec.encodeDeviceName("x".repeat(31))!!.size)
        assertNull(BoseDailySettingsCodec.encodeDeviceName("x".repeat(32)))
        assertEquals(30,BoseDailySettingsCodec.encodeDeviceName("é".repeat(15))!!.size)
        assertNull(BoseDailySettingsCodec.encodeDeviceName("é".repeat(16)))
        assertNull(BoseDailySettingsCodec.encodeDeviceName(0xd800.toChar().toString()))
        assertNull(BoseDailySettingsCodec.encodeDeviceName("bad"+0.toChar()))
        assertNull(BoseDailySettingsCodec.parseDeviceName(frame(1,2,3,3,0,0xc3,0x28)))
        assertNull(BoseDailySettingsCodec.parseDeviceName(frame(1,2,2,6,0,70,97,114,103,111)))
        assertEquals("",BoseDailySettingsCodec.parseDeviceName(frame(1,2,3,1,0))!!.text)
    }
}
