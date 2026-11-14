package dev.hyperears.integration

import dev.hyperears.protocol.jieli.JlRcspWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailingT3AdapterTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val battery = bytes("fedcba00c10007000804005a5000ef")
    private val noise = bytes("fedcba8009000d0eff0a0d024000400040004000ef")
    private val game = bytes("fedcba00c100050008020501ef")
    private fun identity(name: String) = EarbudIdentity(deviceName = name, standardHeadset = true)

    private fun authenticate(adapter: DailingT3Adapter) {
        adapter.beginHandshake()
        assertEquals(1, adapter.receive(byteArrayOf(1) + ByteArray(16)).commands.size)
        assertEquals(1, adapter.receive(byteArrayOf(0) + ByteArray(16)).commands.size)
        assertEquals(5, adapter.receive(bytes("0270617373")).commands.size)
    }

    @Test fun exactModelCandidateDoesNotRequireCachedSdpOrMatchOtherBrands() {
        listOf("DaiLing T3", "戴灵 T3", "DAILING T3").forEach {
            assertTrue(EarbudAdapterRegistry.resolve(identity(it)) is DailingT3Adapter)
        }
        assertFalse(DailingT3Adapter().matches(identity("Some TWS").copy(
            serviceUuids = setOf("00001101-0000-1000-8000-00805f9b34fb"))))
        assertFalse(DailingT3Adapter().matches(identity("DaiLing T3").copy(nativeSystemEarbud = true)))
        assertFalse(DailingT3Adapter().matches(identity("DaiLing T3").copy(standardHeadset = false)))
    }

    @Test fun initialCapabilitiesAndUnauthenticatedFramesStayLocked() {
        val adapter = DailingT3Adapter()
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertNull(adapter.miLinkCardPresentationId)
        adapter.receive(noise + battery + game)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertNull(adapter.runtimeState().features.get<DailingGameModeFeatureState>())
    }

    @Test fun authenticationRequiresOrderedStagesAndCompletePassBytes() {
        val adapter = DailingT3Adapter()
        adapter.beginHandshake()
        adapter.receive(byteArrayOf(1) + ByteArray(16))
        adapter.receive(byteArrayOf(0) + ByteArray(16))
        assertEquals(HandshakeResult.Rejected, adapter.receive(bytes("026661696c")).handshake)
        assertTrue(adapter.receive(battery).commands.isEmpty())
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        val outOfOrder = DailingT3Adapter()
        outOfOrder.beginHandshake()
        assertEquals(HandshakeResult.Rejected, outOfOrder.receive(bytes("0270617373")).handshake)
    }

    @Test fun fragmentedAuthenticationAndCoalescedFirstFramePreserveData() {
        val adapter = DailingT3Adapter()
        adapter.beginHandshake()
        val greeting = byteArrayOf(1) + ByteArray(16)
        assertTrue(adapter.receive(greeting.copyOf(8)).commands.isEmpty())
        assertEquals(1, adapter.receive(greeting.copyOfRange(8, 17)).commands.size)
        adapter.receive(byteArrayOf(0) + ByteArray(16))
        val result = adapter.receive(bytes("0270617373") + battery)
        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(8, result.commands.size) // five initialization reads, then three feature reads
        assertEquals(90, adapter.runtimeState().features.get<BatteryFeatureState>()!!.battery.left.percent)
    }

    @Test fun batteryNoiseAndGameAreIndependentlyConfirmed() {
        val adapter = DailingT3Adapter()
        authenticate(adapter)
        adapter.receive(battery)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(noise)
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertNull(adapter.runtimeState().features.get<DailingGameModeFeatureState>())
        assertFalse(adapter.executeControl(DailingControlRequest.SetGameMode(true)).accepted)
        assertNull(adapter.miLinkCardPresentationId)
        adapter.receive(game)
        assertEquals(false, adapter.runtimeState().features.get<DailingGameModeFeatureState>()?.enabled)
        assertEquals(DailingMiLinkPresentationIds.CARD, adapter.miLinkCardPresentationId)
    }

    @Test fun gameWriteDoesNotPublishSuccessBeforeReadback() {
        val adapter = DailingT3Adapter()
        authenticate(adapter)
        adapter.receive(game)
        val result = adapter.executeControl(DailingControlRequest.SetGameMode(true))
        assertTrue(result.accepted)
        assertEquals(false, adapter.runtimeState().features.get<DailingGameModeFeatureState>()?.enabled)
        assertEquals(ControlConfirmationPolicy.DEVICE_REPORT,
            adapter.controlPolicy(DailingControlRequest.SetGameMode(true)).confirmation)
        val readback = adapter.protocolSession.readback(DailingControlRequest.SetGameMode(true))
        assertEquals(JlRcspWireCodec.OP_ADV_INFO, JlRcspWireCodec.Decoder().offer(readback.single()).single().opCode)
        adapter.receive(bytes("fedcba00c100050009020502ef"))
        assertEquals(true, adapter.runtimeState().features.get<DailingGameModeFeatureState>()?.enabled)
    }

    @Test fun standardAncOnlyWritesOneModeFrameAndReadsModeExplicitly() {
        val adapter = DailingT3Adapter()
        authenticate(adapter)
        adapter.receive(noise)
        val request = StandardControlRequest.SetNoiseMode(NoiseMode.ANC)
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertEquals(1, result.commands.size)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode)
        assertEquals(JlRcspWireCodec.OP_ATTRIBUTE_QUERY,
            JlRcspWireCodec.Decoder().offer(adapter.protocolSession.readback(request).single()).single().opCode)
    }

    @Test fun refreshReadsAllThreeFeaturesAndSequenceWraps() {
        val adapter = DailingT3Adapter()
        authenticate(adapter)
        adapter.receive(battery)
        repeat(100) {
            val commands = adapter.protocolSession.query(TelemetryQuery.RefreshAll)
            assertEquals(listOf(0xC3, 7, 0xC1),
                commands.map { JlRcspWireCodec.Decoder().offer(it).single().opCode })
        }
        assertEquals(1, adapter.protocolSession.query(
            TelemetryQuery.RefreshFeature(DailingGameModeFeatureState.FEATURE_ID)).size)
    }

    @Test fun unknownReportClearsPreviousBatteryButDoesNotOpenItInitially() {
        val adapter = DailingT3Adapter()
        authenticate(adapter)
        val unknown = bytes("fedcba00c100070008040000ff00ef")
        adapter.receive(unknown)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        adapter.receive(battery)
        adapter.receive(unknown)
        val state = adapter.runtimeState().features.get<BatteryFeatureState>()!!.battery
        assertNull(state.left.percent)
        assertNull(state.right.percent)
    }

    @Test fun resetAndSeparateDevicesDoNotShareConfirmation() {
        val first = DailingT3Adapter()
        val second = DailingT3Adapter()
        authenticate(first)
        first.receive(battery + noise + game)
        assertNotSame(first.protocolSession, second.protocolSession)
        assertNull(second.miLinkCardPresentationId)
        first.resetProtocolSession()
        assertFalse(first.effectiveCapabilities().noiseControl)
        assertNull(first.runtimeState().features.get<DailingGameModeFeatureState>())
        assertEquals(17, first.beginHandshake().commands.single().size)
    }

    @Test fun customRequestUsesExistingEnvelopeWithoutManualRegistration() {
        val request = DailingControlRequest.SetGameMode(true)
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val features = AdapterRuntimeState().features.update(DailingGameModeFeatureState(true))
        assertEquals(features, FeatureStateTransport.decode(FeatureStateTransport.encode(features)))
    }
}
