package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class VivoTws3eSettingsTest {
    private val report = """{"eventId":"A102|10084","C":"92_89","params":{"double_click_l":"4","double_click_r":"255","long_click_l":"8","long_click_r":"11","eq_type":"2","wear_enable":"1","noise":"1"}}"""
    @Test fun exactModelOnlyUnlocksFieldsFromReportsAndResets() {
        val adapter = VivoTws3eAdapter()
        val request = VivoTws3eControlRequest.SetDeepX(VivoDeepX.AUDIOBOOK)
        assertFalse(adapter.supportsControl(request)); assertNull(adapter.snapshot().presentationId)
        report.toByteArray().forEach { adapter.receive(byteArrayOf(it)) }
        assertTrue(adapter.supportsControl(request))
        val state = adapter.runtimeState().features.get<VivoTws3eFeatureState>()!!
        assertEquals(VivoDoubleTap.PLAY_PAUSE, state.leftTap)
        assertEquals(VivoDoubleTap.NONE, state.rightTap)
        assertEquals(92, adapter.runtimeState().battery.left.percent)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertTrue(adapter.executeControl(request).accepted)
        assertEquals(VivoDeepX.BASS, adapter.runtimeState().features.get<VivoTws3eFeatureState>()!!.deepX)
        assertTrue(adapter.protocolSession.readback(request).isEmpty())
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(request)); assertNull(adapter.snapshot().presentationId)
        assertTrue(adapter.protocolSession.encode(request).isEmpty())
        listOf(VivoEarbudAdapter(), VivoTwsAir3ProAdapter()).forEach {
            it.receive(report.toByteArray())
            assertNull(it.runtimeState().features.get<VivoTws3eFeatureState>())
            assertTrue(it.protocolSession.encode(request).isEmpty())
        }
    }
    @Test fun invalidValuesNeverConfirmAndPartialSnapshotsRevokeMissingControls() {
        assertNull(VivoTws3eStatus.parse(report.replace("\"eq_type\":\"2\"", "\"eq_type\":\"4\"")))
        assertNull(VivoTws3eStatus.parse(report.replace("\"double_click_l\":\"4\"", "\"double_click_l\":\"1\"")))
        assertNull(VivoTws3eStatus.parse(report.replace("A102|10084", "other")))
        assertNull(VivoTws3eStatus.parse("""{"eventId":"A102|10084","params":{}}"""))
        assertNull(VivoTws3eStatus.parse(report.replace("92_89", "101_89")))
        val adapter = VivoTws3eAdapter()
        adapter.receive(report.toByteArray())
        adapter.receive("""{"eventId":"A102|10084","params":{"wear_enable":"0"}}""".toByteArray())
        assertFalse(adapter.supportsControl(VivoTws3eControlRequest.SetHold(VivoHoldCycle.ALL, VivoHoldCycle.NONE)))
        assertTrue(adapter.supportsControl(VivoTws3eControlRequest.SetWearDetection(true)))
    }
    @Test fun typedIpcRoundTripsEveryRequestAndSnapshot() {
        val requests = listOf(
            VivoTws3eControlRequest.SetDoubleTap(true, VivoDoubleTap.NONE),
            VivoTws3eControlRequest.SetHold(VivoHoldCycle.ALL, VivoHoldCycle.NONE),
            VivoTws3eControlRequest.SetDeepX(VivoDeepX.TREBLE),
            VivoTws3eControlRequest.SetWearDetection(false),
        )
        requests.forEach { assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it))) }
        val feature = VivoTws3eStatus.parse(report)!!.settings
        val snapshot = DeviceFeatureSnapshot().update(feature)
        assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
    }
}
