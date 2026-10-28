package dev.hyperears.protocol.samsung

import org.junit.Assert.*
import org.junit.Test

class SamsungDailySettingsCodecTest {
    @Test fun originalHeaderIsDistinctAndStreamsFragmentedWithCrcChecks() {
        val model = SamsungBudsCodec.Model.BUDS
        val packet = SamsungDailySettingsCodec.equalizer(model, true, 2)
        assertEquals(0xFE, packet[0].toInt() and 255)
        assertEquals(0, packet[1].toInt()); assertEquals(5, packet[2].toInt())
        assertEquals(0xEE, packet.last().toInt() and 255)
        assertNull(SamsungBudsCodec.parseFrame(packet))
        val decoder = SamsungBudsCodec.Decoder(model)
        assertTrue(decoder.offer(packet.take(3).toByteArray()).isEmpty())
        assertArrayEquals(packet, decoder.offer(packet.drop(3).toByteArray()).single())
        assertArrayEquals(byteArrayOf(1, 7), SamsungBudsCodec.parseFrame(packet, model)!!.payload)
        assertNull(SamsungBudsCodec.parseFrame(packet.copyOf().apply { this[4] = 2 }, model))
        val plus = SamsungDailySettingsCodec.equalizer(SamsungBudsCodec.Model.BUDS_PLUS, true, 2)
        assertArrayEquals(byteArrayOf(3), SamsungBudsCodec.parseFrame(plus)!!.payload)
        assertNull(SamsungBudsCodec.parseFrame(plus, model))
    }
    @Test fun originalAndPlusStatusUseOwnFieldsWithoutInventingCaseBatteryOrAnc() {
        val original = report(SamsungBudsCodec.Model.BUDS)
        val state = SamsungDailySettingsCodec.parse(original, SamsungBudsCodec.Model.BUDS)!!
        assertEquals(2, state.eqPreset); assertEquals(true, state.eqEnabled)
        assertEquals(2, state.ambient); assertEquals(4, state.ambientMaximum)
        assertEquals(0, state.leftHold); assertEquals(1, state.rightHold)
        assertNull(SamsungBudsCodec.parseBattery(original, SamsungBudsCodec.Model.BUDS)!!.case)
        assertEquals(SamsungBudsCodec.NoiseMode.AMBIENT, SamsungBudsCodec.parseNoiseMode(original, SamsungBudsCodec.Model.BUDS))
        val plus = SamsungDailySettingsCodec.parse(report(SamsungBudsCodec.Model.BUDS_PLUS), SamsungBudsCodec.Model.BUDS_PLUS)!!
        assertEquals(2, plus.eqPreset); assertEquals(3, plus.ambientMaximum)
    }
    @Test fun customEqNineSignedGainsAndModeSevenAreRestrictedToExactModels() {
        val supported = setOf(SamsungBudsCodec.Model.BUDS3, SamsungBudsCodec.Model.BUDS3_PRO,
            SamsungBudsCodec.Model.BUDS3_FE, SamsungBudsCodec.Model.BUDS4, SamsungBudsCodec.Model.BUDS4_PRO)
        SamsungBudsCodec.Model.entries.forEach { assertEquals(it.name, it in supported, SamsungDailySettingsCodec.customEqSupported(it)) }
        val frames = SamsungDailySettingsCodec.customEqualizer(SamsungBudsCodec.Model.BUDS4_PRO, listOf(-10,10,0,1,-1,2,-2,3,-3)).map { SamsungBudsCodec.parseFrame(it)!! }
        assertEquals(listOf(0x89, 0x86), frames.map { it.id })
        assertArrayEquals(byteArrayOf(9,-10,10,0,1,-1,2,-2,3,-3), frames[0].payload)
        assertArrayEquals(byteArrayOf(7), frames[1].payload)
        listOf(List(8) { 0 }, List(9) { 11 }).forEach { gains ->
            assertTrue(runCatching { SamsungDailySettingsCodec.customEqualizer(SamsungBudsCodec.Model.BUDS3, gains) }.isFailure)
        }
        assertTrue(runCatching { SamsungDailySettingsCodec.customEqualizer(SamsungBudsCodec.Model.BUDS, List(9) { 0 }) }.isFailure)
        val curve = SamsungBudsCodec.Frame(0x69, byteArrayOf(2,9) + ByteArray(18))
        assertEquals(List(9) { 0 }, SamsungDailySettingsCodec.curve(curve)!!.bands)
        assertNull(SamsungDailySettingsCodec.curve(curve.copy(payload = curve.payload.dropLast(1).toByteArray())))
    }
    @Test fun independentFieldsRespectRevisionAndInvalidFieldDoesNotEnableFeature() {
        val model = SamsungBudsCodec.Model.BUDS_PRO
        val p = report(model).payload
        assertNotNull(SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,p),model)!!.customAmbient)
        assertNull(SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,p.copyOf().apply { this[0] = 7 }),model)!!.customAmbient)
        val invalid = SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,p.copyOf().apply { this[9] = 99 }),model)!!
        assertNull(invalid.eqEnabled); assertNotNull(invalid.ambient)
        assertNull(SamsungDailySettingsCodec.parse(report(SamsungBudsCodec.Model.BUDS_CORE),SamsungBudsCodec.Model.BUDS_CORE)!!.customAmbient)
    }
    @Test fun extraAmbientRevisionOffsetsAndShortOriginalDoNotBorrowTouch() {
        val pro = report(SamsungBudsCodec.Model.BUDS_PRO).payload.copyOf().apply { this[0] = 2; this[22] = 1; this[23] = 4 }
        assertEquals(4, SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,pro),SamsungBudsCodec.Model.BUDS_PRO)!!.ambient)
        val buds2 = report(SamsungBudsCodec.Model.BUDS2).payload.copyOf().apply { this[26] = 1; this[23] = 3 }
        assertEquals(3, SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,buds2),SamsungBudsCodec.Model.BUDS2)!!.ambient)
        val short = report(SamsungBudsCodec.Model.BUDS).payload.copyOf(12)
        val state = SamsungDailySettingsCodec.parse(SamsungBudsCodec.Frame(0x61,short),SamsungBudsCodec.Model.BUDS)!!
        assertEquals(2,state.eqPreset); assertNull(state.locked); assertNull(state.leftHold)
    }
    private fun report(model: SamsungBudsCodec.Model): SamsungBudsCodec.Frame {
        val p = ByteArray(50); p[0] = 13; p[2] = 80; p[3] = 70
        if (model.ambientOnly) { p[7] = 1; p[8] = 1; p[9] = 2; p[10] = 1; p[11] = if (model.original) 7 else 3; p[13] = if (model.original) 1 else 0x22; p[20] = 1 }
        else { p[9] = 3; p[11] = 0x22; p[23] = 2; p[33] = 1; p[34] = 0x12; p[35] = 2 }
        return SamsungBudsCodec.Frame(0x61,p)
    }
}
