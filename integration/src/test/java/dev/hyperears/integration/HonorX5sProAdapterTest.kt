package dev.hyperears.integration

import dev.hyperears.protocol.honor.HonorX5sSppCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HonorX5sProAdapterTest {

    private val adapter = HonorX5sProAdapter()

    @Test
    fun registryResolvesByNormalizedDeviceName() {
        val identity = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = true,
        )
        assertTrue(EarbudAdapterRegistry.resolve(identity) is HonorX5sProAdapter)
        assertFalse(
            EarbudAdapterRegistry.resolve(identity("AirPods Pro")) is HonorX5sProAdapter,
        )
    }

    @Test
    fun matchesRejectsNonStandardHeadset() {
        val systemNative = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = true,
            nativeSystemEarbud = true,
        )
        assertFalse(adapter.matches(systemNative))
        val nonStandard = EarbudIdentity(
            deviceName = "荣耀亲选耳机X5s Pro",
            standardHeadset = false,
        )
        assertFalse(adapter.matches(nonStandard))
    }

    @Test
    fun declaresSppTransportAndInitialStandardCapabilities() {
        val transport = adapter.transports.single() as RfcommEndpointSpec.ServiceUuid
        assertEquals(HonorX5sProAdapter.SPP_UUID, transport.uuid)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())
        assertNull(adapter.runtimeState().features.get<HonorAncDepthFeatureState>())
    }

    @Test
    fun batteryEvidencePromotesToPrivateSource() {
        adapter.receive(hex("5A 00 10 00 01 27 01 01 47 02 03 64 64 47 03 03 64 64 00 EF 9F"))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertTrue(adapter.effectiveCapabilities().battery)
        val battery = adapter.runtimeState().features.get<BatteryFeatureState>()?.battery
        assertEquals(100, battery?.left?.percent)
        assertEquals(71, battery?.case?.percent)
    }

    @Test
    fun stateFrameEvidenceOpensNoiseModesAndDepth() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"))
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
        val depth = adapter.runtimeState().features.get<HonorAncDepthFeatureState>()
        assertEquals(HonorAncDepth.SMART, depth?.current)
        assertEquals(HonorAncDepth.entries.toSet(), depth?.supported)
    }

    @Test
    fun offAndTransparencyReportNullCurrentDepth() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73"))
        val depth = adapter.runtimeState().features.get<HonorAncDepthFeatureState>()
        assertNull(depth?.current)
        assertEquals(HonorAncDepth.entries.toSet(), depth?.supported)
    }

    @Test
    fun staleDepthRequestAfterOffCannotImplicitlyEnableAnc() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"))
        val request = HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP)
        assertTrue(adapter.supportsControl(request))
        assertEquals(HonorX5sProAdapter.PRESENTATION_ID, adapter.miLinkCardPresentationId)
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73"))
        assertFalse(adapter.executeControl(request).accepted)
        assertTrue(adapter.protocolSession.encode(request).isEmpty())
        assertTrue(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).accepted)
        assertFalse(adapter.supportsControl(request))
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"))
        assertTrue(adapter.supportsControl(request))
    }

    @Test
    fun ancCommandAlwaysUsesSmartDepth() {
        confirmNoiseModes()
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(result.accepted)
        assertArrayEquals(
            HonorX5sSppCodec.modeCommand(
                HonorX5sSppCodec.NoiseMode.ANC,
                HonorX5sSppCodec.AncDepth.SMART,
            ),
            result.commands[0],
        )
    }

    @Test
    fun setAncDepthContractAndEncoding() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"))
        val result = adapter.executeControl(
            HonorControlRequest.SetAncDepth(HonorAncDepth.LIGHT),
        )
        assertTrue(result.accepted)
        assertArrayEquals(
            HonorX5sSppCodec.modeCommand(
                HonorX5sSppCodec.NoiseMode.ANC,
                HonorX5sSppCodec.AncDepth.LIGHT,
            ),
            result.commands[0],
        )
    }

    @Test
    fun setAncDepthRejectedBeforeNoiseEvidence() {
        val result = adapter.executeControl(
            HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP),
        )
        assertFalse(result.accepted)
    }

    @Test
    fun featureAndControlRoundTripThroughTransports() {
        // Feature state: encoded snapshot contains the Honor depth feature with its SerialName.
        val snapshot = DeviceFeatureSnapshot(
            listOf(
                NoiseModeFeatureState(NoiseMode.ANC),
                HonorAncDepthFeatureState(
                    current = HonorAncDepth.MEDIUM,
                    supported = HonorAncDepth.entries.toSet(),
                ),
            ),
        )
        val encoded = FeatureStateTransport.encode(snapshot)
        assertTrue(encoded.contains("\"honor.x5spro.anc_depth\""))
        val decoded = FeatureStateTransport.decode(encoded)!!
        assertEquals(HonorAncDepth.MEDIUM, decoded.get<HonorAncDepthFeatureState>()?.current)

        // Control request: typed round trip preserves the model request.
        val requestEncoded = ControlRequestTransport.encode(
            HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP),
        )
        assertTrue(requestEncoded.contains("\"honor.x5spro.set_anc_depth\""))
        val requestDecoded = ControlRequestTransport.decode(requestEncoded)
        assertEquals(
            HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP),
            requestDecoded,
        )
    }

    @Test
    fun firstValidFrameConfirmsHandshake() {
        val batteryResult = adapter.receive(
            hex("5A 00 10 00 01 27 01 01 5D 02 03 64 64 5D 03 03 64 64 00 24 19"),
        )
        assertEquals(HandshakeResult.Ready, batteryResult.handshake)
        val again = adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 00 26 00"))
        assertNull(again.handshake)
    }

    @Test
    fun stateFrameAloneConfirmsHandshake() {
        val fresh = HonorX5sProAdapter()
        val result = fresh.receive(hex("5A 00 07 00 2B 2A 01 02 01 00 26 00"))
        assertEquals(HandshakeResult.Ready, result.handshake)
    }

    @Test
    fun caseLevelDoesNotFollowCasedEarbud() {
        // 8/9 sequence: the cased-slot byte drifts with the right earbud (69->66) while the
        // case field stays stable (100->96). The reported case must stay with the case field.
        adapter.receive(hex("5A 00 10 00 01 27 01 01 45 02 03 64 45 64 03 03 64 64 00 62 53"))
        var battery = adapter.runtimeState().features.get<BatteryFeatureState>()?.battery
        assertEquals(100, battery?.case?.percent)
        assertEquals(69, battery?.right?.percent)

        adapter.receive(hex("5A 00 10 00 01 27 01 01 43 02 03 64 43 60 03 03 64 64 00 34 23"))
        battery = adapter.runtimeState().features.get<BatteryFeatureState>()?.battery
        assertEquals(96, battery?.case?.percent)
        assertEquals(67, battery?.right?.percent)
    }

    @Test
    fun caseSlotObservedValueIsPreserved() {
        // Single earbud charging in the case (00:15): the case slot observed value (94) is kept
        // as-is; without an independent judge it must not be replaced by any cached value.
        adapter.receive(hex("5A 00 10 00 01 08 01 01 5E 02 03 64 5E 5E 03 03 64 64 00 1A 0D"))
        val battery = adapter.runtimeState().features.get<BatteryFeatureState>()?.battery
        assertEquals(94, battery?.case?.percent)
        assertEquals(100, battery?.left?.percent)
        assertEquals(94, battery?.right?.percent)
    }

    @Test
    fun inCaseOffStateIsNotMistakenForAnc() {
        adapter.receive(hex("5A 00 10 00 01 08 01 01 5F 02 03 64 64 5F 03 03 64 64 00 09 48"))
        val result = adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 00 26 00"))
        assertTrue(result.stateChanged)
        assertEquals(
            NoiseMode.OFF,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
    }

    @Test
    fun singleEarbudOffStateIsNotMistakenForAnc() {
        adapter.receive(hex("5A 00 10 00 01 27 01 01 00 02 03 00 49 00 03 03 64 64 00 7F 36"))
        val result = adapter.receive(hex("5A 00 07 00 2B 2A 01 02 01 00 26 00"))
        assertTrue(result.stateChanged)
        assertEquals(
            NoiseMode.OFF,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
    }

    @Test
    fun stateFrameUpdatesNoiseMode() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10"))
        assertEquals(
            NoiseMode.ANC,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 02 35 73"))
        assertEquals(
            NoiseMode.TRANSPARENCY,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
        assertEquals(
            NoiseMode.OFF,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
    }

    @Test
    fun partialFrameIsBufferedAcrossOffers() {
        val full = hex("5A 00 07 00 2B 2A 01 02 01 01 36 21")
        adapter.receive(full.copyOfRange(0, 5))
        assertNull(adapter.runtimeState().features.get<NoiseModeFeatureState>())
        val result = adapter.receive(full.copyOfRange(5, full.size))
        assertTrue(result.stateChanged)
        assertEquals(
            NoiseMode.ANC,
            adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode,
        )
    }

    @Test
    fun heartbeatFrameIsIgnoredWithoutStateChange() {
        val before = adapter.runtimeState()
        val result = adapter.receive(hex("5A 00 05 00 2B 79 01 00 45 E0"))
        assertFalse(result.stateChanged)
        assertEquals(before, adapter.runtimeState())
    }

    @Test
    fun initialCommandsIncludeStateQuery() {
        // The state query (`2B 2A 01 00`) must be sent at connect so a single-eardrum
        // connection gets a state report immediately instead of waiting for the pushed report.
        val commands = adapter.beginHandshake().commands
        assertTrue(
            commands.any { cmd ->
                val hexText = cmd.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
                hexText.startsWith("5a0005002b2a0100")
            },
        )
        assertTrue(commands.contains(HonorX5sSppCodec.queryBattery))
    }

    @Test
    fun refreshEncodesBatteryQuery() {
        val result = adapter.executeControl(StandardControlRequest.Refresh)
        assertTrue(result.accepted)
        assertArrayEquals(HonorX5sSppCodec.queryBattery, result.commands[0])
    }

    @Test
    fun windNoiseModeIsRejected() {
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertFalse(result.accepted)
    }

    private fun confirmNoiseModes() {
        adapter.receive(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun identity(name: String): EarbudIdentity =
        EarbudIdentity(deviceName = name, standardHeadset = true)
}
