package dev.hyperears.integration

import dev.hyperears.protocol.jieli.JlRcspWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter tests for the DaiLing T3. Every device frame below is copied from a real HCI capture
 * (see `docs/dailing-t3-protocol.md`).
 */
class DailingT3AdapterTest {

    private fun identity(
        name: String,
        standardHeadset: Boolean = true,
        nativeSystemEarbud: Boolean = false,
        services: Set<String> = setOf(DailingT3Adapter.SPP_UUID),
    ) = EarbudIdentity(
        deviceName = name,
        standardHeadset = standardHeadset,
        nativeSystemEarbud = nativeSystemEarbud,
        serviceUuids = services,
    )

    @Test
    fun registryResolvesDailingT3ToModelAdapter() {
        listOf("DaiLing T3", "戴灵 T3", "DAILING T3").forEach { name ->
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(identity(name)))
            assertTrue(name, adapter is DailingT3Adapter)
            assertEquals("dailing-t3", adapter.id)
            assertEquals("戴灵 T3", adapter.displayName)
        }
    }

    @Test
    fun spsServiceAndRetailNameAreBothRequiredForCandidateSelection() {
        val withoutService = requireNotNull(
            EarbudAdapterRegistry.resolve(identity("DaiLing T3", services = emptySet())),
        )
        assertTrue(withoutService is StandardEarbudAdapter)

        val withoutBrand = requireNotNull(
            EarbudAdapterRegistry.resolve(identity("Some TWS")),
        )
        assertTrue(withoutBrand is StandardEarbudAdapter)
    }

    @Test
    fun matchesRejectsNonStandardAndSystemOwnedDevices() {
        val adapter = DailingT3Adapter()
        assertFalse(adapter.matches(identity("DaiLing T3", standardHeadset = false)))
        assertFalse(adapter.matches(identity("DaiLing T3", nativeSystemEarbud = true)))
    }

    @Test
    fun initialCapabilitiesStayLockedAndUseSppTransport() {
        val adapter = DailingT3Adapter()
        val snapshot = adapter.snapshot()

        assertEquals(AdapterResolution.EXACT_MATCH, snapshot.resolution)
        assertTrue(adapter.privateProtocolRequired)
        assertEquals(TransportReadiness.PROTOCOL_HANDSHAKE, adapter.transportReadiness)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, snapshot.batterySource)
        assertTrue(snapshot.capabilities.battery)
        assertTrue(snapshot.capabilities.audioHandoff)
        assertFalse(snapshot.capabilities.noiseControl)
        assertTrue(adapter.supportedNoiseModes.isEmpty())
        assertNull(snapshot.presentationId)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertFalse(
            adapter.supportsControl(DailingControlRequest.SetAncDepth(DailingAncDepth.DEEP)),
        )

        // The vendor app opens RFCOMM channel 1 insecurely; a secure SPP socket connects but the
        // headset then ignores every RCSP frame.
        val primary = adapter.transports.first() as RfcommEndpointSpec.Channel
        assertEquals(1, primary.number)
        assertFalse(primary.secure)
        val fallback = adapter.transports[1] as RfcommEndpointSpec.ServiceUuid
        assertEquals(DailingT3Adapter.SPP_UUID, fallback.uuid)
        assertEquals(listOf(ControlAppCatalog.dailing), adapter.controlApps)
    }

    @Test
    fun handshakeStartsWithAuthRandomPacket() {
        val adapter = DailingT3Adapter()
        val result = adapter.beginHandshake()

        assertEquals(HandshakeResult.AwaitingEvidence, result.handshake)
        assertEquals(1, result.commands.size)
        val packet = result.commands.single()
        // Auth start packet: tag 0x00 followed by 16 random bytes.
        assertEquals(17, packet.size)
        assertEquals(0x00, packet[0].toInt() and 0xFF)
    }

    @Test
    fun deviceInfoFrameConfirmsHandshakeWithoutOpeningCapabilities() {
        val adapter = DailingT3Adapter()
        completeAuth(adapter)
        val result = adapter.receive(deviceInfoFrame())

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())
    }

    @Test
    fun batteryFrameOpensPrivateComponentBattery() {
        val adapter = DailingT3Adapter()
        completeAuth(adapter)
        val result = adapter.receive(bytes("fedcba00c10007000804005a5a00"))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        val battery = requireNotNull(
            adapter.runtimeState().features.get<BatteryFeatureState>(),
        ).battery
        assertEquals(90, battery.left.percent)
        assertEquals(90, battery.right.percent)
        assertFalse(battery.left.charging)
    }

    @Test
    fun noiseReportOpensOnlyTheThreeCapturedModes() {
        val adapter = DailingT3Adapter()
        completeAuth(adapter)
        val result = adapter.receive(
            bytes("fedcba8009000d0eff0a0d024000400040004000"),
        )

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.effectiveSupportedNoiseModes(),
        )
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
    }

    @Test
    fun noiseReportPublishesDepthStateWithoutInventingACurrentLevel() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        val feature = requireNotNull(
            adapter.runtimeState().features.get<DailingAncDepthFeatureState>(),
        )
        assertNull(feature.current)
        assertEquals(DailingAncDepthFeatureState.ALL_DEPTHS, feature.supported)
        assertTrue(
            adapter.supportsControl(DailingControlRequest.SetAncDepth(DailingAncDepth.DEEP)),
        )
    }

    @Test
    fun setNoiseModeWritesCapturedAttributeBlock() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        val request = StandardControlRequest.SetNoiseMode(NoiseMode.ANC)
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        // ANC also re-applies the module-selected depth; the first activation defaults to deep.
        assertEquals(2, result.commands.size)
        val write = JlRcspWireCodec.Decoder().offer(result.commands.first()).single()
        assertEquals(JlRcspWireCodec.FLAG_APP_COMMAND, write.flag)
        assertEquals(JlRcspWireCodec.OP_ATTRIBUTE_SET, write.opCode)
        // Payload: sequence + FF 0A 0D 01 60 00 60 00 60 00 60 00
        assertEquals(
            "FF 0A 0D 01 60 00 60 00 60 00 60 00",
            write.data.copyOfRange(1, write.data.size).toHex(),
        )
        val depth = JlRcspWireCodec.Decoder().offer(result.commands[1]).single()
        assertEquals(JlRcspWireCodec.OP_EXT_ATTRIBUTE, depth.opCode)
        assertEquals("0E 01 00", depth.data.copyOfRange(1, 4).toHex())
        assertTrue(result.readback.isNotEmpty())

        // Only the device report is authoritative noise state.
        adapter.receive(bytes("fedcba8009000d0eff0a0d014000400040004000"))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun cardPresentationAppearsOnlyAfterConfirmedDepthFeature() {
        val adapter = DailingT3Adapter()
        assertNull(adapter.miLinkCardPresentationId)

        openEvidence(adapter)

        assertEquals(DailingMiLinkPresentationIds.CARD, adapter.miLinkCardPresentationId)
    }

    @Test
    fun setAncDepthWritesCapturedDepthCommandAndPublishesState() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        val result = adapter.executeControl(
            DailingControlRequest.SetAncDepth(DailingAncDepth.MIDDLE),
        )
        assertTrue(result.accepted)
        assertEquals(1, result.commands.size)
        val write = JlRcspWireCodec.Decoder().offer(result.commands.single()).single()
        assertEquals(JlRcspWireCodec.FLAG_APP_COMMAND, write.flag)
        assertEquals(JlRcspWireCodec.OP_EXT_ATTRIBUTE, write.opCode)
        // Payload: sequence + 0E 01 <level>, middle = 0x02 in the capture.
        assertEquals("0E 01 02", write.data.copyOfRange(1, 4).toHex())

        val feature = requireNotNull(
            adapter.runtimeState().features.get<DailingAncDepthFeatureState>(),
        )
        assertEquals(DailingAncDepth.MIDDLE, feature.current)
        assertEquals(DailingAncDepthFeatureState.ALL_DEPTHS, feature.supported)
    }

    @Test
    fun firstAncActivationDefaultsToDeepAndKeepsTheUsersChoice() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        adapter.executeControl(DailingControlRequest.SetAncDepth(DailingAncDepth.LIGHT))
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        val depth = JlRcspWireCodec.Decoder().offer(result.commands[1]).single()
        assertEquals("0E 01 01", depth.data.copyOfRange(1, 4).toHex())
    }

    @Test
    fun ancActivationPublishesTheAppliedDepthOnceTheDeviceAnswers() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        val featureBefore = adapter.runtimeState().features.get<DailingAncDepthFeatureState>()
        assertNull(featureBefore?.current)

        adapter.receive(bytes("fedcba0008000200edef"))

        val featureAfter = requireNotNull(
            adapter.runtimeState().features.get<DailingAncDepthFeatureState>(),
        )
        assertEquals(DailingAncDepth.DEEP, featureAfter.current)
    }

    @Test
    fun chosenDepthStaysForTheNextAncActivation() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        adapter.executeControl(DailingControlRequest.SetAncDepth(DailingAncDepth.MIDDLE))
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        val depth = JlRcspWireCodec.Decoder().offer(result.commands[1]).single()
        assertEquals("0E 01 02", depth.data.copyOfRange(1, 4).toHex())
    }

    @Test
    fun storedDepthOverridesTheFirstRunDefault() {
        val adapter = DailingT3Adapter()
        adapter.preferredAncDepth = DailingAncDepth.MIDDLE
        openEvidence(adapter)

        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        val depth = JlRcspWireCodec.Decoder().offer(result.commands[1]).single()
        assertEquals("0E 01 02", depth.data.copyOfRange(1, 4).toHex())
    }

    @Test
    fun gameModeUsesTheAdvInfoLtvCommand() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        val enable = adapter.executeControl(DailingControlRequest.SetGameMode(true))
        assertTrue(enable.accepted)
        val enableFrame = JlRcspWireCodec.Decoder().offer(enable.commands.single()).single()
        assertEquals(JlRcspWireCodec.OP_SET_ADV_INFO, enableFrame.opCode)
        assertEquals(
            listOf(0x02, 0x05, 0x02),
            enableFrame.data.copyOfRange(1, 4).map { it.toInt() and 0xFF },
        )
        assertEquals(
            true,
            adapter.runtimeState().features.get<DailingGameModeFeatureState>()?.enabled,
        )

        val disable = adapter.executeControl(DailingControlRequest.SetGameMode(false))
        val disableFrame = JlRcspWireCodec.Decoder().offer(disable.commands.single()).single()
        assertEquals(
            listOf(0x02, 0x05, 0x01),
            disableFrame.data.copyOfRange(1, 4).map { it.toInt() and 0xFF },
        )
        assertEquals(
            false,
            adapter.runtimeState().features.get<DailingGameModeFeatureState>()?.enabled,
        )
    }

    @Test
    fun handshakeAsksForTheDualDeviceState() {
        val adapter = DailingT3Adapter()
        completeAuth(adapter)
        adapter.receive(deviceInfoFrame())

        val queries = adapter.protocolSession.followUpCommands(ProtocolEvent.HandshakeAccepted)
        val frames = queries.map { JlRcspWireCodec.Decoder().offer(it).single() }
        assertEquals(
            true,
            frames.any { frame ->
                frame.opCode == JlRcspWireCodec.OP_EXT_ATTRIBUTE &&
                    frame.data.size >= 3 &&
                    frame.data[1].toInt() == 0x1A &&
                    (frame.data[2].toInt() and 0xFF) == 0xFF
            },
        )
    }

    @Test
    fun deviceReportUpdatesTheDualDeviceState() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        // Captured device answer: flag 0xC0, seq 0x36, custom command 1A with value 0.
        adapter.receive(bytes("fedcbac0ff0003361a00ef"))

        assertEquals(
            false,
            adapter.runtimeState().features.get<DailingDualConnectionFeatureState>()?.enabled,
        )
    }

    @Test
    fun dualConnectionUsesTheVendorAiCustomCommand() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)
        assertNull(
            adapter.runtimeState().features.get<DailingDualConnectionFeatureState>()?.enabled,
        )

        val enable = adapter.executeControl(DailingControlRequest.SetDualConnection(true))
        assertTrue(enable.accepted)
        val frame = JlRcspWireCodec.Decoder().offer(enable.commands.single()).single()
        assertEquals(JlRcspWireCodec.OP_EXT_ATTRIBUTE, frame.opCode)
        assertEquals(
            listOf(0x1A, 0x01),
            frame.data.copyOfRange(1, 3).map { it.toInt() and 0xFF },
        )
        assertEquals(
            true,
            adapter.runtimeState().features.get<DailingDualConnectionFeatureState>()?.enabled,
        )

        val disable = adapter.executeControl(DailingControlRequest.SetDualConnection(false))
        val offFrame = JlRcspWireCodec.Decoder().offer(disable.commands.single()).single()
        assertEquals(
            listOf(0x1A, 0x00),
            offFrame.data.copyOfRange(1, 3).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun protocolResetLocksCapabilitiesAndRestartsInitialization() {
        val adapter = DailingT3Adapter()
        openEvidence(adapter)

        adapter.resetProtocolSession()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())
        assertNull(adapter.runtimeState().features.get<DailingAncDepthFeatureState>())
        val first = adapter.beginHandshake().commands.first()
        assertEquals(17, first.size)
        assertEquals(0x00, first[0].toInt() and 0xFF)
    }

    @Test
    fun eachAdapterInstanceOwnsAnIndependentSession() {
        val first = requireNotNull(EarbudAdapterRegistry.resolve(identity("DaiLing T3")))
        val second = requireNotNull(EarbudAdapterRegistry.resolve(identity("DaiLing T3")))
        assertTrue(first is DailingT3Adapter)
        assertTrue(second is DailingT3Adapter)
        assertFalse(first === second)
        assertFalse(first.protocolSession === second.protocolSession)
        assertSame(DailingT3Adapter::class.java, first.javaClass)
    }

    /** Runs the device authentication exchange: `00`+random, `01`+proof, then the `pass` ack. */
    private fun completeAuth(adapter: DailingT3Adapter) {
        adapter.receive(bytes("00" + "00112233445566778899aabbccddeeff"))
        adapter.protocolSession.drainImmediateCommands()
        adapter.receive(bytes("01" + "ffeeddccbbaa99887766554433221100"))
        adapter.protocolSession.drainImmediateCommands()
        adapter.receive(bytes("0270617373"))
        adapter.protocolSession.drainImmediateCommands()
    }

    private fun openEvidence(adapter: DailingT3Adapter) {
        completeAuth(adapter)
        adapter.receive(deviceInfoFrame())
        adapter.receive(bytes("fedcba00c10007000804005a5a00"))
        adapter.receive(bytes("fedcba8009000d0eff0a0d024000400040004000"))
    }

    private fun deviceInfoFrame(): ByteArray = bytes(DEVICE_INFO_FRAME)

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String =
        toList().joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private companion object {
        /** Captured device-information response: `FE DC BA 00 03 00 7C` + 124 bytes. */
        const val DEVICE_INFO_FRAME =
            "fedcba0003007c000502002005010000000109024b147ae878288e0107040000000000000305000102" +
                "060a0307980004080001010409000000050a00000000110b68453979667365583655644b37724668" +
                "150c6a6c5f73646b5f61633639375f7075626c697368050d0110021c0811004b147ae87828021200" +
                "03134680051500000000"
    }
}
