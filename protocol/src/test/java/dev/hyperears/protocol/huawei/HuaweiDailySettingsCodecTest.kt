package dev.hyperears.protocol.huawei

import org.junit.Assert.*
import org.junit.Test

class HuaweiDailySettingsCodecTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun frame(command: Int, vararg fields: Pair<Int, ByteArray>) =
        HuaweiFreebudsSppCodec.parseFrame(HuaweiFreebudsSppCodec.packet(command, fields.toList()))!!
    @Test fun queriesAndWritesMatchPublicCapturedTraffic() {
        assertArrayEquals(hex("5A0005002B110100772A"), HuaweiDailySettingsCodec.query(0x2B11, 1))
        assertArrayEquals(hex("5A0006002B10010101A956"), HuaweiDailySettingsCodec.setBoolean(0x2B10, true))
        assertArrayEquals(hex("5A0005002B2F0100A98E"), HuaweiDailySettingsCodec.query(0x2B2F, 1))
        assertArrayEquals(hex("5A0006002B2E01010037C4"), HuaweiDailySettingsCodec.setBoolean(0x2B2E, false))
        assertArrayEquals(hex("5a000700012001000200e897"), HuaweiDailySettingsCodec.queryGesture(HuaweiDailySettingsCodec.Gesture.DOUBLE))
        assertArrayEquals(hex("5a000600011f01010203c1"), HuaweiDailySettingsCodec.setGesture(HuaweiDailySettingsCodec.Gesture.DOUBLE, 1, 2))
        assertArrayEquals(hex("5a0009002b1e0101ff0201ff9d9b"), HuaweiDailySettingsCodec.setGesture(HuaweiDailySettingsCodec.Gesture.SWIPE, 1, -1))
        assertArrayEquals(hex("5A0006002B490101012F1A"), HuaweiDailySettingsCodec.setPreset(1))
    }
    @Test fun onlyReadReportsAndStrictBooleanAreAuthoritative() {
        assertEquals(true, HuaweiDailySettingsCodec.parseBoolean(frame(0x2B11, 1 to byteArrayOf(1)), 0x2B11))
        assertNull(HuaweiDailySettingsCodec.parseBoolean(frame(0x2B10, 1 to byteArrayOf(1)), 0x2B11))
        assertNull(HuaweiDailySettingsCodec.parseBoolean(frame(0x2B11, 1 to byteArrayOf(2)), 0x2B11))
        assertNull(HuaweiDailySettingsCodec.parseBoolean(frame(0x2B11, 1 to byteArrayOf(1), 127 to byteArrayOf(1)), 0x2B11))
    }
    @Test fun gestureFixtureRetainsSignedActionsAndAdvertisedChoices() {
        val parsed = HuaweiFreebudsSppCodec.parseFrame(hex("5a0017000120010101020102030501070200ff0401ff060200ffcf60"))!!
        val state = HuaweiDailySettingsCodec.parseGesture(parsed, HuaweiDailySettingsCodec.Gesture.DOUBLE)!!
        assertEquals(1, state.left); assertEquals(2, state.right); assertEquals(-1, state.call)
        assertEquals(listOf(1, 7, 2, 0, -1), state.advertised)
        assertNull(HuaweiDailySettingsCodec.parseGesture(frame(0x0120, 1 to byteArrayOf(1, 2)), HuaweiDailySettingsCodec.Gesture.DOUBLE))
    }
    @Test fun customEqualizerValidatesRecordsAndNeverInventsSlots() {
        val record = byteArrayOf(100, 10) + ByteArray(10) + "Daily".toByteArray().copyOf(24)
        val report = HuaweiDailySettingsCodec.parseEqualizer(frame(0x2B4A,
            1 to byteArrayOf(1), 2 to byteArrayOf(100), 3 to byteArrayOf(1, 2, 3), 8 to record))!!
        assertEquals("Daily", report.custom.single().name)
        val write = HuaweiFreebudsSppCodec.parseFrame(HuaweiDailySettingsCodec.setCustom(report.custom.single()))!!
        assertEquals(0x2B49, write.command); assertEquals(1, write.params[5]!!.single().toInt())
        assertNull(HuaweiDailySettingsCodec.parseEqualizer(frame(0x2B4A, 2 to byteArrayOf(100), 8 to record.copyOf(35))))
        assertNull(HuaweiDailySettingsCodec.parseEqualizer(frame(0x2B4A, 2 to byteArrayOf(100), 8 to (record + record))))
        assertThrows(IllegalArgumentException::class.java) {
            HuaweiDailySettingsCodec.setCustom(report.custom.single().copy(gains = List(10) { 61 }))
        }
    }
    @Test fun crcAndTruncatedTlvAreRejectedWithoutThrowing() {
        val good = HuaweiFreebudsSppCodec.packet(0x2B11, listOf(1 to byteArrayOf(1)))
        assertNull(HuaweiFreebudsSppCodec.parseFrame(good.copyOf().apply { this[8] = 2 }))
        val malformed = byteArrayOf(0x5A, 0, 4, 0, 0x2B, 0x11, 1, 0, 0)
        val crc = HuaweiFreebudsSppCodec.crc16Xmodem(malformed, 0, malformed.size - 2)
        malformed[7] = (crc ushr 8).toByte(); malformed[8] = crc.toByte()
        assertNull(HuaweiFreebudsSppCodec.parseFrame(malformed))
    }
}
