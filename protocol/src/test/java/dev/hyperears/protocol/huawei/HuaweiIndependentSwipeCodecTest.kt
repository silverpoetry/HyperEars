package dev.hyperears.protocol.huawei
import org.junit.Assert.*
import org.junit.Test
class HuaweiIndependentSwipeCodecTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun independentSidesAndQueryMatchLiteralSourcePackets() {
        assertArrayEquals(hex("5A0007002B1F01000200328A"),HuaweiDailySettingsCodec.queryGesture(HuaweiDailySettingsCodec.Gesture.SWIPE))
        assertArrayEquals(hex("5A0006002B1E0101001B2D"),HuaweiDailySettingsCodec.setGesture(HuaweiDailySettingsCodec.Gesture.SWIPE,1,0,independentSwipe=true))
        assertArrayEquals(hex("5A0006002B1E020101525C"),HuaweiDailySettingsCodec.setGesture(HuaweiDailySettingsCodec.Gesture.SWIPE,2,1,independentSwipe=true))
        val mirrored=HuaweiFreebudsSppCodec.parseFrame(HuaweiDailySettingsCodec.setGesture(HuaweiDailySettingsCodec.Gesture.SWIPE,1,0))!!
        assertEquals(setOf(1,2),mirrored.params.keys)
        val state=HuaweiDailySettingsCodec.parseGesture(HuaweiFreebudsSppCodec.parseFrame(hex("5A000E002B1F01010002010103030001FF617F"))!!,HuaweiDailySettingsCodec.Gesture.SWIPE)!!
        assertEquals(0,state.left);assertEquals(1,state.right)
    }
}
