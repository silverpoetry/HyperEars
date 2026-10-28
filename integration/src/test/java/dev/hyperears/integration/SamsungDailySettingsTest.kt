package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.*
import org.junit.Test

class SamsungDailySettingsTest {
    @Test fun allThirteenModelsRequireReportForDailyControlsAndResetRevokesIntent() {
        SamsungBudsCodec.Model.entries.filter { it != SamsungBudsCodec.Model.UNKNOWN }.forEach { model ->
            val adapter = SamsungBuds2ProAdapter(model)
            assertFalse(adapter.supportsControl(SamsungDailyControlRequest.SetEqualizer(true, 2)))
            adapter.receive(report(model))
            assertTrue(model.name, adapter.supportsControl(SamsungDailyControlRequest.SetEqualizer(true, 2)))
            assertTrue(adapter.executeControl(SamsungDailyControlRequest.SetEqualizer(true, 2)).accepted)
            assertEquals(SamsungDailyPresentationIds.SETTINGS, adapter.miLinkCardPresentationId)
            adapter.executeControl(SamsungDailyControlRequest.Find(true))
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>())
            assertNull(adapter.runtimeState().features.get<SamsungDailyIntentFeatureState>())
            assertFalse(adapter.executeControl(SamsungDailyControlRequest.Find(true)).accepted)
        }
    }
    @Test fun originalPlusIdentityTransportAndAmbientOnlyRemainSeparate() {
        val buds = SamsungBuds2ProAdapter(SamsungBudsCodec.Model.BUDS)
        val plus = SamsungBuds2ProAdapter(SamsungBudsCodec.Model.BUDS_PLUS)
        assertTrue(buds.matches(EarbudIdentity("Galaxy Buds (1234)", true)))
        assertFalse(buds.matches(EarbudIdentity("Galaxy Buds+ (1234)", true)))
        assertTrue(plus.matches(EarbudIdentity("Galaxy Buds+ (1234)", true)))
        assertTrue(plus.matches(EarbudIdentity("SM-R175", true)))
        for (model in listOf(SamsungBudsCodec.Model.BUDS, SamsungBudsCodec.Model.BUDS_PLUS)) {
            val adapter = SamsungBuds2ProAdapter(model); adapter.receive(report(model))
            assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
            assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)))
            val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
            val frame = SamsungBudsCodec.parseFrame(result.commands.single(), model)!!
            assertEquals(0x80, frame.id); assertArrayEquals(byteArrayOf(1), frame.payload)
            adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x80.toByte(),1),model))
            assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        }
    }
    @Test fun customDraftPinsModeAndSubmissionDoesNotClaimDeviceCurveOrReplay() {
        val model = SamsungBudsCodec.Model.BUDS4_PRO
        val adapter = SamsungBuds2ProAdapter(model); adapter.receive(report(model))
        val request = SamsungDailyControlRequest.SetCustomEqualizer(List(9) { it - 4 }, model.name, 13, 2, true)
        val decoded = ControlRequestTransport.decode(ControlRequestTransport.encode(request))
        assertEquals(request, decoded)
        val result = adapter.executeControl(request)
        assertTrue(result.accepted); assertEquals(2, result.commands.size)
        assertFalse(adapter.runtimeState().features.get<SamsungDailyIntentFeatureState>()!!.curveWritten)
        adapter.controlWritten(request)
        assertTrue(adapter.runtimeState().features.get<SamsungDailyIntentFeatureState>()!!.curveWritten)
        assertEquals(2, adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.equalizerPreset)
        adapter.receive(SamsungBudsCodec.packet(0x69, byteArrayOf(2,9) + ByteArray(18)))
        assertEquals(request.gains, adapter.runtimeState().features.get<SamsungDailyIntentFeatureState>()!!.submittedCurve)
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x86.toByte(),7)))
        assertEquals(6, adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.equalizerPreset)
        assertFalse(adapter.executeControl(request).accepted)
        val session = SamsungBuds2ProProtocolSession(model)
        session.offer(report(model)); session.reset()
        assertTrue(session.initialReadCommands().isEmpty()); assertTrue(session.encode(request).isEmpty())
    }
    @Test fun malformedAckCannotConfirmSettingsAndValidUnsupportedEqRevokesOnlyEq() {
        val model = SamsungBudsCodec.Model.BUDS_PRO
        val adapter = SamsungBuds2ProAdapter(model); adapter.receive(report(model))
        val before = adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x83.toByte(),2)))
        adapter.receive(SamsungBudsCodec.packet(0x42, byteArrayOf(0x82.toByte(),1,5,0,0)))
        assertEquals(before, adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>())
        val parsed = SamsungBudsCodec.parseFrame(report(model))!!
        adapter.receive(SamsungBudsCodec.packet(0x61, parsed.payload.copyOf().apply { this[9] = 99 }))
        assertFalse(adapter.supportsControl(SamsungDailyControlRequest.SetEqualizer(true,2)))
        assertTrue(adapter.supportsControl(SamsungDailyControlRequest.SetAmbientVolume(1)))
    }
    @Test fun ringNeverRunsOnHandshakeOrResetAndStopReportIsIndependentOfRequest() {
        val adapter = SamsungBuds2ProAdapter(SamsungBudsCodec.Model.BUDS2_PRO)
        adapter.receive(report(SamsungBudsCodec.Model.BUDS2_PRO))
        assertNull(adapter.runtimeState().features.get<SamsungDailyIntentFeatureState>())
        val result = adapter.executeControl(SamsungDailyControlRequest.Find(true))
        assertEquals(0xA0, SamsungBudsCodec.parseFrame(result.commands.single())!!.id)
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
        adapter.receive(SamsungBudsCodec.packet(0xA3, byteArrayOf(1,0)))
        assertEquals(true, adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findLeftMuted)
        adapter.receive(SamsungBudsCodec.packet(0xA1))
        assertEquals(true, adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
        assertEquals(0xA1, SamsungBudsCodec.parseFrame(adapter.executeControl(SamsungDailyControlRequest.Find(false)).commands.single())!!.id)
    }
    @Test fun extendedCustomAmbientAckConfirmsCoreAndNewRingClearsOldStop() {
        val adapter=SamsungBuds2ProAdapter(SamsungBudsCodec.Model.BUDS_PRO); adapter.receive(report(SamsungBudsCodec.Model.BUDS_PRO))
        adapter.receive(SamsungBudsCodec.packet(0x42,byteArrayOf(0x82.toByte(),1,2,3,4,1,2)))
        assertEquals(SamsungCustomAmbient(true,2,3,4,4),adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.customAmbient)
        adapter.receive(SamsungBudsCodec.packet(0xA1))
        val start=SamsungDailyControlRequest.Find(true)
        adapter.executeControl(start); adapter.controlWritten(start)
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
        adapter.receive(SamsungBudsCodec.packet(0xA3,byteArrayOf(1,0)))
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
        adapter.executeControl(start)
        adapter.receive(SamsungBudsCodec.packet(0xA1))
        adapter.controlWritten(start)
        assertEquals(true,adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
    }
    private fun report(model: SamsungBudsCodec.Model): ByteArray {
        val p = ByteArray(50); p[0] = 13; p[2] = 80; p[3] = 70; p[7] = 60
        if (model.ambientOnly) { p[7] = 0; p[8] = 0; p[9] = 2; p[10] = 1; p[11] = if (model.original) 7 else 3; p[13] = if (model.original) 1 else 0x22 }
        else { p[9] = 3; p[10] = 0; p[11] = 0x22; p[23] = 2; p[33] = 1; p[34] = 0x12; p[35] = 2 }
        return SamsungBudsCodec.packet(0x61,p,model)
    }
}
