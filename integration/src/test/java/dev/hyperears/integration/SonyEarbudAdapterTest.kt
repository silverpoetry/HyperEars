package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyEarbudAdapterTest {
    @Test
    fun resolvesConcreteModelsBeforeProtocolFamilies() {
        assertEquals("sony-wh-1000xm5", resolve("WH-1000XM5").id)
        assertEquals(HeadsetFormFactor.HEADPHONES, resolve("WH-1000XM5").formFactor)
        assertEquals("sony-wf-c510", resolve("WF-C510").id)
        assertTrue(resolve("WF-C510").supportedNoiseModes.isEmpty())
        assertEquals(null, resolve("WF-C510").miLinkCardPresentationId)
        val xm6 = resolve("WF-1000XM6")
        assertEquals("sony-wf-1000xm6", xm6.id)
        assertEquals(HeadsetFormFactor.TWS, xm6.formFactor)
        assertEquals("sony-rfcomm-v2", xm6.transports.first().id)
        assertEquals("sony-linkbuds-s", resolve("LinkBuds S").id)
        assertEquals("sony-linkbuds", resolve("LinkBuds").id)
        assertEquals("sony-linkbuds", resolve("Sony LinkBuds").id)
    }

    @Test
    fun unknownModelsStartConservativeUntilTheFamilyProtocolIsConfirmed() {
        val noiseModel = resolve("WH-CH999N")
        assertEquals("sony-headphones-noise-protocol-family", noiseModel.id)
        assertFalse(noiseModel.capabilities.noiseControl)
        assertEquals(
            "sony-headphones-noise-protocol-family",
            resolve("Sony WH-CH999N").id,
        )

        val batteryModel = resolve("WF-C999")
        assertEquals("sony-tws-protocol-family", batteryModel.id)
        assertTrue(batteryModel.capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, batteryModel.batterySource)
        assertFalse(batteryModel.capabilities.noiseControl)
    }

    @Test
    fun serviceEvidenceUnlocksProtocolButLeShadowNameDoesNot() {
        val identity = identity(
            name = "Wireless Audio",
            services = setOf(SonyHeadphonesWireCodec.RFCOMM_SERVICE_V1),
        )
        assertEquals("sony-tws-protocol-family", EarbudAdapterRegistry.resolve(identity)?.id)
        assertFalse(resolve("LE_WF-C710N").privateProtocolRequired)
    }

    @Test
    fun sharedIap2UuidNeverActsAsSonyOrBoseIdentity() {
        val identity = identity(
            name = "Wireless Audio",
            services = setOf(BoseEarbudAdapter.IAP2_ACCESSORY_UUID),
        )

        assertEquals(
            StandardEarbudAdapter.ID,
            EarbudAdapterRegistry.resolve(identity)?.id,
        )
    }

    @Test
    fun sharedIap2UuidDoesNotOverrideARecognizedSonyModel() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity(
                name = "LinkBuds S",
                services = setOf(BoseEarbudAdapter.IAP2_ACCESSORY_UUID),
            ),
        )

        assertEquals("sony-linkbuds-s", adapter?.id)
    }

    @Test
    fun boseVendorServiceCanSelectBoseFamilyWithoutSharedIap2Uuid() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity(
                name = "Wireless Audio",
                services = setOf(BoseEarbudAdapter.BOSE_BMAP_BLE_SERVICE_UUID),
            ),
        )

        assertEquals(BoseEarbudAdapter.ID, adapter?.id)
    }

    @Test
    fun v1HandshakeAcksAndAdvancesOneRequestPerDeviceAck() {
        val adapter = resolve("WH-1000XM3")
        val protocol = requireNotNull(adapter.protocolSession)
        val init = decode(protocol.initialReadCommands().single())
        assertArrayEquals(bytes("00 00"), init.payload)

        val handshakeEvents = protocol.offer(command(0, "01 00 40 10"))
        assertEquals(
            listOf(ProtocolEvent.HandshakeAccepted),
            handshakeEvents,
        )
        assertEquals(
            SonyHeadphonesWireCodec.MessageType.ACK,
            decode(protocol.drainImmediateCommands().single()).type,
        )

        protocol.offer(ack(1))
        val batteryQuery = decode(protocol.drainImmediateCommands().single())
        assertEquals(1, batteryQuery.sequence)
        assertArrayEquals(bytes("10 00"), batteryQuery.payload)

        protocol.offer(ack(0))
        val ambientQuery = decode(protocol.drainImmediateCommands().single())
        assertArrayEquals(bytes("66 02"), ambientQuery.payload)
    }

    @Test
    fun v1ParsesBatteryAndAmbientReports() {
        val protocol = requireNotNull(resolve("WH-1000XM3").protocolSession)
        protocol.initialReadCommands()
        protocol.offer(command(0, "01 00 40 10"))
        protocol.drainImmediateCommands()

        val batteryEvents = protocol.offer(command(0, "11 00 5a 00"))
        assertEquals(
            listOf(ProtocolEvent.CapabilitiesIdentified(battery = true)),
            batteryEvents.filterIsInstance<ProtocolEvent.CapabilitiesIdentified>(),
        )
        val batteryEvent = batteryEvents
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .map(ProtocolEvent.FeatureStateChanged::state)
            .filterIsInstance<BatteryFeatureState>()
            .single()
        assertEquals(90, batteryEvent.battery.overall.percent)

        val noiseEvents = protocol.offer(command(0, "67 02 01 02 02 01 00 00"))
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
            noiseEvents.filterIsInstance<ProtocolEvent.CapabilitiesIdentified>()
                .single()
                .noiseModes,
        )
        val noiseEvent = noiseEvents
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .map(ProtocolEvent.FeatureStateChanged::state)
            .filterIsInstance<NoiseModeFeatureState>()
            .single()
        assertEquals(NoiseMode.ANC, noiseEvent.mode)
    }

    @Test
    fun unknownSonyFamilyOpensEachCapabilityOnlyAfterItsOwnStateEvidence() {
        val adapter = resolve("WH-CH999N")
        adapter.beginHandshake()

        val handshake = adapter.receive(command(0, "01 00 40 10"))
        assertEquals(HandshakeResult.Ready, handshake.handshake)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(command(0, "11 00 5a 00"))
        assertTrue(adapter.snapshot().capabilities.battery)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(command(0, "67 02 01 02 02 01 00 00"))
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
    }

    @Test
    fun exactSonyModelAlsoUnlocksNoiseOnlyAfterItsStateEvidence() {
        val adapter = resolve("WH-1000XM3")

        assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())

        adapter.beginHandshake()
        val handshake = adapter.receive(command(0, "01 00 40 10"))

        assertEquals(HandshakeResult.Ready, handshake.handshake)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(command(0, "67 02 01 02 02 01 00 00"))

        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
            adapter.snapshot().supportedNoiseModes,
        )
    }

    @Test
    fun v2UsesDual2BatteryAndExtendedAmbientPayloads() {
        val protocol = requireNotNull(resolve("WF-C700N").protocolSession)
        protocol.initialReadCommands()
        protocol.offer(command(0, "01 00 03 00 00 00 00 00"))
        protocol.drainImmediateCommands()
        protocol.offer(ack(1))

        val batteryQuery = decode(protocol.drainImmediateCommands().single())
        assertArrayEquals(bytes("22 01"), batteryQuery.payload)

        val batteryEvent = protocol.offer(command(0, "23 01 4b 00 50 01"))
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .map(ProtocolEvent.FeatureStateChanged::state)
            .filterIsInstance<BatteryFeatureState>()
            .single()
        assertEquals(75, batteryEvent.battery.left.percent)
        assertEquals(80, batteryEvent.battery.right.percent)
        assertTrue(batteryEvent.battery.right.charging)
    }

    @Test
    fun wf1000xm6UnlocksNoiseControlFromModernAmbientNotifies() {
        val adapter = resolve("WF-1000XM6")

        adapter.beginHandshake()
        val handshake = adapter.receive(command(0, "01 00 03 00 30 02 00 00"))
        assertEquals(HandshakeResult.Ready, handshake.handshake)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(command(0, "69 19 00 01 01 00 14 00 00"))
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
    }

    @Test
    fun wf1000xm6QueriesAndParsesModernAmbientFrames() {
        val protocol = requireNotNull(resolve("WF-1000XM6").protocolSession)
        protocol.initialReadCommands()
        protocol.offer(command(0, "01 00 03 00 30 02 00 00"))
        protocol.drainImmediateCommands()
        protocol.offer(ack(1))

        val batteryQuery = decode(protocol.drainImmediateCommands().single())
        assertArrayEquals(bytes("22 09"), batteryQuery.payload)

        val batteryEvent = protocol.offer(command(0, "23 09 37 00 51 00 64 64"))
            .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .map(ProtocolEvent.FeatureStateChanged::state)
            .filterIsInstance<BatteryFeatureState>()
            .single()
        assertEquals(55, batteryEvent.battery.left.percent)
        assertEquals(81, batteryEvent.battery.right.percent)

        protocol.offer(ack(0))
        val caseQuery = decode(protocol.drainImmediateCommands().last())
        assertArrayEquals(bytes("22 0a"), caseQuery.payload)
        protocol.offer(command(0, "23 0a 58 00 1e"))

        protocol.offer(ack(1))
        val ambientQuery = decode(protocol.drainImmediateCommands().last())
        assertArrayEquals(bytes("66 19"), ambientQuery.payload)

        val transparencyEvents = protocol.offer(command(0, "69 19 00 01 01 00 14 00 00"))
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            transparencyEvents.filterIsInstance<ProtocolEvent.CapabilitiesIdentified>()
                .single()
                .noiseModes,
        )
        assertEquals(
            NoiseMode.TRANSPARENCY,
            transparencyEvents.filterIsInstance<ProtocolEvent.FeatureStateChanged>()
                .map(ProtocolEvent.FeatureStateChanged::state)
                .filterIsInstance<NoiseModeFeatureState>()
                .single()
                .mode,
        )
        assertEquals(
            NoiseMode.ANC,
            protocol.offer(command(0, "69 19 00 01 00 00 14 00 00"))
                .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
                .map(ProtocolEvent.FeatureStateChanged::state)
                .filterIsInstance<NoiseModeFeatureState>()
                .single()
                .mode,
        )
        assertEquals(
            NoiseMode.OFF,
            protocol.offer(command(0, "69 19 00 00 00 00 14 00 00"))
                .filterIsInstance<ProtocolEvent.FeatureStateChanged>()
                .map(ProtocolEvent.FeatureStateChanged::state)
                .filterIsInstance<NoiseModeFeatureState>()
                .single()
                .mode,
        )
    }

    @Test
    fun wf1000xm6EncodesModernAmbientWrites() {
        val protocol = requireNotNull(resolve("WF-1000XM6").protocolSession)
        protocol.initialReadCommands()
        protocol.offer(command(0, "01 00 03 00 30 02 00 00"))
        protocol.drainImmediateCommands()
        protocol.offer(ack(1))
        protocol.drainImmediateCommands()
        protocol.offer(ack(0))
        protocol.drainImmediateCommands()
        protocol.offer(ack(1))
        protocol.drainImmediateCommands()
        protocol.offer(ack(0))

        val ancWrite = decode(
            protocol.encode(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).single(),
        )
        assertArrayEquals(bytes("68 19 00 01 00 00 14 00 00"), ancWrite.payload)
        protocol.offer(ack(1))

        val transparencyWrite = decode(
            protocol.encode(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)).single(),
        )
        assertArrayEquals(bytes("68 19 00 01 01 00 14 00 00"), transparencyWrite.payload)
        protocol.offer(ack(0))

        val offWrite = decode(
            protocol.encode(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).single(),
        )
        assertArrayEquals(bytes("68 19 00 00 00 00 14 00 00"), offWrite.payload)
    }

    private fun resolve(name: String): EarbudAdapter = requireNotNull(
        EarbudAdapterRegistry.resolve(identity(name)),
    )

    private fun identity(
        name: String,
        services: Set<String> = emptySet(),
    ): EarbudIdentity = EarbudIdentity(
        deviceName = name,
        standardHeadset = true,
        serviceUuids = services,
    )

    private fun command(sequence: Int, payload: String): ByteArray =
        SonyHeadphonesWireCodec.encode(
            type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
            sequence = sequence,
            payload = bytes(payload),
        )

    private fun ack(sequence: Int): ByteArray = SonyHeadphonesWireCodec.encode(
        type = SonyHeadphonesWireCodec.MessageType.ACK,
        sequence = sequence,
    )

    private fun decode(bytes: ByteArray): SonyHeadphonesWireCodec.Frame =
        SonyHeadphonesWireCodec.Decoder().offer(bytes).single()

    private fun bytes(hex: String): ByteArray = hex
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
