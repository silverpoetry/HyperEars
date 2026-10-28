package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.PuddingApkWireCodec as Wire
import dev.hyperears.protocol.moondrop.MoondropPuddingWireCodec as Legacy
import org.junit.Assert.*
import org.junit.Test

class PuddingAdvancedTest {
    @Test fun apkZeroIsKnownAndOmittedComponentsRemainUnchanged() {
        val adapter = MoondropPuddingAdapter()
        adapter.receive(byteArrayOf(-1,4,0,5,0,29,39,1,1)) // Exact LED report selects APK dialect.
        adapter.receive(byteArrayOf(-1,4,0,10,0,29,27,1,1,80,2,70,3,60))
        adapter.receive(byteArrayOf(-1,4,0,6,0,29,27,1,1,0))
        assertEquals(0, adapter.runtimeState().battery.left.percent)
        assertEquals(70, adapter.runtimeState().battery.right.percent)
        assertEquals(60, adapter.runtimeState().battery.case.percent)
        adapter.receive(byteArrayOf(-1,4,0,6,0,29,27,1,2,-1))
        assertEquals(70, adapter.runtimeState().battery.right.percent)
    }
    private fun report(sub: Int, op: Int, value: Int) = Wire.packet(byteArrayOf(0,29,sub.toByte(),op.toByte(),value.toByte()))
    @Test fun completeReportSelectsApkButNegotiationAloneDoesNot() {
        val adapter = MoondropPuddingAdapter()
        adapter.receive(Wire.packet(byteArrayOf(0,10,-125,0,0,4,3,1)))
        assertNull(adapter.runtimeState().features.get<PuddingSettingsFeatureState>())
        val result = adapter.receive(report(65,3,4))
        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(4, adapter.runtimeState().features.get<PuddingSettingsFeatureState>()!!.values[PuddingSetting.NOISE])
        assertTrue(adapter.executeControl(PuddingControlRequest.SetValue(PuddingSetting.NOISE,3)).accepted)
        assertFalse(adapter.executeControl(PuddingControlRequest.SetValue(PuddingSetting.GAIN,2)).accepted)
        adapter.receive(report(31,1,0))
        assertArrayEquals(Wire.write(30,2), adapter.executeControl(PuddingControlRequest.SetValue(PuddingSetting.GAIN,2)).commands.single())
    }
    @Test fun typedWritesWaitForDelayedReadbackAndKeepConfirmedValues() {
        val adapter = MoondropPuddingAdapter()
        adapter.receive(report(65,3,4) + report(31,1,0) + report(39,1,1))
        PuddingSetting.entries.forEach { setting ->
            val value = if (setting == PuddingSetting.NOISE) 3 else 0
            val request = PuddingControlRequest.SetValue(setting,value)
            assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            val before = adapter.runtimeState().features.get<PuddingSettingsFeatureState>()
            assertTrue(adapter.executeControl(request).accepted)
            assertEquals(before,adapter.runtimeState().features.get<PuddingSettingsFeatureState>())
            assertEquals(listOf(AdapterEffect.RequestState(PuddingSettingsFeatureState.FEATURE_ID,
                if (setting == PuddingSetting.NOISE) 180L else 140L)),adapter.controlWritten(request))
        }
    }
    @Test fun contradictoryHandshakeAndScalarCannotSelectEitherDialect() {
        val adapter = MoondropPuddingAdapter()
        val result = adapter.receive(byteArrayOf(-1,4,0,4,0,10,-125,0,0,4,3,1) + report(65,3,4))
        assertNull(result.handshake)
        assertNull(adapter.runtimeState().features.get<PuddingSettingsFeatureState>())
    }
    @Test fun batteryPrefixDoesNotMisclassifyLegacyAsApk() {
        val adapter = MoondropPuddingAdapter()
        adapter.receive(byteArrayOf(-1,4,0,6,0,29,27,1,1,70,2,80,3,90))
        assertNull(adapter.runtimeState().features.get<PuddingSettingsFeatureState>())
        assertTrue(adapter.protocolSession.query(TelemetryQuery.RefreshFeature(PuddingSettingsFeatureState.DIALECT_QUERY)).isNotEmpty())
    }
    @Test fun capturedHandshakeLocksLegacyAndDisallowsApkWrites() {
        val adapter = MoondropPuddingAdapter()
        adapter.receive(byteArrayOf(-1,4,0,4,0,10,-125,0,0,4,3,1))
        adapter.receive(report(31,1,0))
        assertFalse(adapter.executeControl(PuddingControlRequest.SetValue(PuddingSetting.GAIN,2)).accepted)
        assertTrue(adapter.protocolSession.query(TelemetryQuery.RefreshFeature(PuddingSettingsFeatureState.DIALECT_QUERY)).isEmpty())
    }
    @Test fun startupEffectsArePuddingOnlyAndLegacyMakesFallbackInert() {
        assertTrue(MoondropRobinAdapter().beginHandshake().effects.isEmpty())
        val adapter = MoondropPuddingAdapter()
        val effect = adapter.beginHandshake().effects.single() as AdapterEffect.RequestState
        assertEquals(PuddingSettingsFeatureState.DIALECT_QUERY,effect.featureId)
        adapter.receive(byteArrayOf(-1,4,0,4,0,10,-125,0,0,4,3,1))
        assertTrue(adapter.queryState(effect.featureId).isEmpty())
    }
    @Test fun fallbackReadIsBoundedAndResetClearsSelection() {
        val session = PuddingAdvancedSession()
        val query = TelemetryQuery.RefreshFeature(PuddingSettingsFeatureState.DIALECT_QUERY)
        assertTrue(session.query(query).any { it.contentEquals(Wire.handshake) })
        assertTrue(session.query(query).isEmpty())
        session.offer(report(39,1,1))
        assertTrue(session.encode(PuddingControlRequest.SetValue(PuddingSetting.LED,0)).isNotEmpty())
        session.reset()
        assertTrue(session.encode(PuddingControlRequest.SetValue(PuddingSetting.LED,0)).isEmpty())
    }
}
