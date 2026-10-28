package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class RoseCeramicsXSettingsTest {
    private fun report(field: Int, value: Int) = if (field == 4)
        byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 0, value.toByte()) else
        byteArrayOf(0, 0x28, 2, 0, 3, field.toByte(), 1, value.toByte())
    private fun request(setting: RoseCeramicsXSetting, value: Int) = RoseCeramicsXControlRequest.SetValue(setting, value)
    @Test fun independentReportsQualifySettingsOnlyAndNeverOptimisticallyCommitWrites() {
        val adapter = RoseLuliXAdapter()
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.supportsControl(request(RoseCeramicsXSetting.GAME, 1)))
        assertTrue(adapter.protocolSession.encode(request(RoseCeramicsXSetting.GAME, 1)).isEmpty())
        adapter.receive(report(8, 0))
        assertEquals(RoseLuliXAdapter.SETTINGS_ONLY_PRESENTATION_ID, adapter.snapshot().presentationId)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        val gameRequest = request(RoseCeramicsXSetting.GAME, 1)
        assertTrue(adapter.supportsControl(gameRequest))
        assertFalse(adapter.supportsControl(request(RoseCeramicsXSetting.TOUCH, 1)))
        val result = adapter.executeControl(gameRequest)
        assertArrayEquals(byteArrayOf(0, 0x25, 1, 0, 1, 1), result.commands.single())
        assertArrayEquals(byteArrayOf(0, 0x27, 1, 0, 1, 8), result.readback.single())
        adapter.receive(result.commands.single())
        assertEquals(0, adapter.runtimeState().features.get<RoseCeramicsXSettingsFeatureState>()!!.values[RoseCeramicsXSetting.GAME])
        adapter.receive(report(8, 1))
        assertEquals(1, adapter.runtimeState().features.get<RoseCeramicsXSettingsFeatureState>()!!.values[RoseCeramicsXSetting.GAME])
        assertEquals(gameRequest, ControlRequestTransport.decode(ControlRequestTransport.encode(gameRequest)))
        val features = adapter.runtimeState().features
        assertEquals(features, FeatureStateTransport.decode(FeatureStateTransport.encode(features)))
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(gameRequest))
        assertTrue(adapter.protocolSession.encode(gameRequest).isEmpty())
        assertNull(adapter.snapshot().presentationId)
    }
    @Test fun depthRequiresIndependentReportAndCurrentCorrespondingMode() {
        val adapter = RoseLuliXAdapter()
        val depth = request(RoseCeramicsXSetting.ANC_LEVEL, 2)
        adapter.receive(report(17, 0))
        assertFalse(adapter.executeControl(depth).accepted)
        assertTrue(adapter.protocolSession.encode(depth).isEmpty())
        adapter.receive(report(12, 1))
        assertEquals(RoseLuliXAdapter.PRESENTATION_ID, adapter.snapshot().presentationId)
        assertTrue(adapter.executeControl(depth).accepted)
        adapter.receive(report(12, 2))
        assertFalse(adapter.executeControl(depth).accepted)
        assertTrue(adapter.protocolSession.encode(depth).isEmpty())
        assertFalse(adapter.executeControl(request(RoseCeramicsXSetting.TRANSPARENCY_LEVEL, 2)).accepted)
        adapter.receive(report(18, 0))
        assertTrue(adapter.executeControl(request(RoseCeramicsXSetting.TRANSPARENCY_LEVEL, 2)).accepted)
    }
    @Test fun audioIsOneFieldAndOtherCeramicsIdentityDoesNotInheritIt() {
        val adapter = RoseLuliXAdapter()
        adapter.receive(report(11, 2))
        val result = adapter.executeControl(request(RoseCeramicsXSetting.AUDIO, 0))
        assertArrayEquals(byteArrayOf(0, 0x2B, 1, 0, 1, 0), result.commands.single())
        assertArrayEquals(byteArrayOf(0, 0x27, 1, 0, 1, 11), result.readback.single())
        adapter.receive(report(11, 3))
        assertEquals(2, adapter.runtimeState().features.get<RoseCeramicsXSettingsFeatureState>()!!.values[RoseCeramicsXSetting.AUDIO])
        assertFalse(adapter.supportsControl(request(RoseCeramicsXSetting.AUDIO, 3)))
        assertFalse(adapter.matches(EarbudIdentity("CERAMICS-X-7034", true)))
        val other = EarbudAdapterRegistry.resolve(EarbudIdentity("CERAMICS-X-7034", true))!!
        other.receive(report(8, 1))
        assertFalse(other.supportsControl(request(RoseCeramicsXSetting.GAME, 0)))
    }
}
