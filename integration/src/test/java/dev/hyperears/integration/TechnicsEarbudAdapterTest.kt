package dev.hyperears.integration

import dev.hyperears.protocol.technics.TechnicsRaceWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicsEarbudAdapterTest {
    @Test
    fun azFamilyNamesSelectTechnicsWithoutCapturingA800() {
        listOf(
            "AZ30",
            "EAH-AZ40",
            "EAH-AZ60",
            "AZ100",
            "EAH-AZ80",
            "EAH-AZ60M2",
            "Technics EAH-AZ70W",
        ).forEach { name ->
            assertTrue(name, resolve(name) is TechnicsEarbudAdapter)
        }

        assertFalse(resolve("EAH-A800") is TechnicsEarbudAdapter)
        assertFalse(resolve("Technics headphones") is TechnicsEarbudAdapter)
    }

    @Test
    fun technicsCandidateRequiresAStandardNonNativeHeadset() {
        assertNull(
            EarbudAdapterRegistry.resolve(identity("EAH-AZ80", standard = false)),
        )
        assertNull(
            EarbudAdapterRegistry.resolve(
                identity("EAH-AZ80", nativeSystemEarbud = true),
            ),
        )
    }

    @Test
    fun vendorUuidIsOnlyATransportEndpoint() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity(
                name = "Unrelated headset",
                serviceUuids = setOf(TechnicsEarbudAdapter.TECHNICS_SPP_UUID),
            ),
        )

        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is TechnicsEarbudAdapter)
    }

    @Test
    fun privateCapabilitiesStartLockedAndHandshakeQueriesReadOnlyState() {
        val adapter = TechnicsEarbudAdapter()
        val snapshot = adapter.snapshot()

        assertTrue(adapter.privateProtocolRequired)
        assertEquals(TransportReadiness.PROTOCOL_HANDSHAKE, adapter.transportReadiness)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, snapshot.batterySource)
        assertTrue(snapshot.capabilities.battery)
        assertTrue(snapshot.capabilities.audioHandoff)
        assertFalse(snapshot.capabilities.noiseControl)
        assertTrue(snapshot.supportedNoiseModes.isEmpty())
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertEquals(
            listOf(
                TechnicsEarbudAdapter.TECHNICS_SPP_UUID,
                TechnicsEarbudAdapter.STANDARD_SPP_UUID,
            ),
            adapter.transports.map { (it as RfcommEndpointSpec.ServiceUuid).uuid },
        )
        assertEquals(
            listOf(
                TechnicsRaceWireCodec.queryAgentBattery,
                TechnicsRaceWireCodec.queryClientBattery,
                TechnicsRaceWireCodec.queryCaseBattery,
                TechnicsRaceWireCodec.queryOutsideControl,
            ).map(ByteArray::toList),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )
    }

    @Test
    fun validComponentBatteryReportsUnlockOnlyPrivateBattery() {
        val adapter = TechnicsEarbudAdapter()

        val first = adapter.receive(raceIndication(0x0CD6, 0x00, 0x00, 81))
        adapter.receive(raceIndication(0x0CD6, 0x00, 0x01, 74))
        adapter.receive(raceResponse(0x0040, 0x00, 63))

        assertEquals(HandshakeResult.Ready, first.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(74, adapter.runtimeState().battery.left.percent)
        assertEquals(81, adapter.runtimeState().battery.right.percent)
        assertEquals(63, adapter.runtimeState().battery.case.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun validOutsideControlReportSeparatelyUnlocksThreeStandardModes() {
        val adapter = TechnicsEarbudAdapter()

        val result = adapter.receive(raceResponse(0x000A, 0x00, 0x02, 90, 55))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
        assertEquals(
            TechnicsEarbudAdapter.SUPPORTED_NOISE_MODES,
            adapter.snapshot().supportedNoiseModes,
        )

        listOf(
            NoiseMode.OFF to TechnicsRaceWireCodec.NoiseMode.OFF,
            NoiseMode.ANC to TechnicsRaceWireCodec.NoiseMode.ANC,
            NoiseMode.TRANSPARENCY to TechnicsRaceWireCodec.NoiseMode.TRANSPARENCY,
        ).forEach { (domainMode, wireMode) ->
            val control = adapter.executeControl(
                StandardControlRequest.SetNoiseMode(domainMode),
            )
            assertTrue(domainMode.name, control.accepted)
            assertEquals(
                TechnicsRaceWireCodec
                    .setNoiseMode(
                        mode = wireMode,
                        noiseCancelLevel = 90,
                        ambientLevel = 55,
                    )
                    .map(ByteArray::toList),
                control.commands.map(ByteArray::toList),
            )
            assertEquals(
                listOf(TechnicsRaceWireCodec.queryOutsideControl).map(ByteArray::toList),
                control.readback.map(ByteArray::toList),
            )
        }

        val wind = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))
        assertFalse(wind.accepted)
        assertTrue(wind.commands.isEmpty())
    }

    @Test
    fun deviceReadbackUpdatesModeAndLevelsForTheNextWrite() {
        val adapter = TechnicsEarbudAdapter()
        adapter.receive(raceResponse(0x000A, 0x00, 0x01, 90, 55))

        val transparency = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )
        assertTrue(transparency.accepted)
        assertEquals(
            listOf(TechnicsRaceWireCodec.queryOutsideControl).map(ByteArray::toList),
            transparency.readback.map(ByteArray::toList),
        )

        adapter.receive(raceResponse(0x000A, 0x00, 0x02, 37, 73))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        val anc = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(anc.accepted)
        assertEquals(
            TechnicsRaceWireCodec
                .setNoiseMode(
                    mode = TechnicsRaceWireCodec.NoiseMode.ANC,
                    noiseCancelLevel = 37,
                    ambientLevel = 73,
                )
                .map(ByteArray::toList),
            anc.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun unknownNoiseFrameDoesNotUnlockRuntime() {
        val adapter = TechnicsEarbudAdapter()

        val result = adapter.receive(raceResponse(0x1234, 0x00, 0x01))

        assertNull(result.handshake)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertNull(adapter.runtimeState().noiseMode)
    }

    @Test
    fun reconnectRequiresFreshOutsideControlReportBeforeModeWrite() {
        val adapter = TechnicsEarbudAdapter()
        adapter.receive(raceResponse(0x000A, 0x00, 0x02, 90, 55))

        adapter.resetProtocolSession()
        adapter.receive(raceIndication(0x0CD6, 0x00, 0x00, 81))

        val blocked = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertFalse(blocked.accepted)
        assertTrue(blocked.commands.isEmpty())

        adapter.receive(raceResponse(0x000A, 0x00, 0x00, 37, 73))
        val allowed = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(allowed.accepted)
        assertEquals(
            TechnicsRaceWireCodec
                .setNoiseMode(
                    mode = TechnicsRaceWireCodec.NoiseMode.ANC,
                    noiseCancelLevel = 37,
                    ambientLevel = 73,
                )
                .map(ByteArray::toList),
            allowed.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun protocolResetRevokesPrivateEvidenceAndRestoresSystemBatteryFallback() {
        val adapter = TechnicsEarbudAdapter()
        adapter.onSystemBatteryChanged(68)
        adapter.receive(raceIndication(0x0CD6, 0x00, 0x00, 81))
        adapter.receive(raceIndication(0x0CD6, 0x00, 0x01, 74))
        adapter.receive(raceResponse(0x0040, 0x00, 63))
        adapter.receive(raceResponse(0x000A, 0x00, 0x01, 90, 55))

        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)

        adapter.resetProtocolSession()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))

        assertTrue(adapter.onSystemBatteryChanged(68))
        assertEquals(68, adapter.runtimeState().battery.left.percent)
        assertEquals(68, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)

        adapter.receive(raceResponse(0x000A, 0x00, 0x00, 37, 73))
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun officialControllerIsCataloguedForNavigationAndOwnershipOnly() {
        val app = ControlAppCatalog.technicsAudioConnect

        assertEquals("com.panasonic.technicsaudioconnect", app.packageName)
        assertEquals(app, ControlAppCatalog.find(app.packageName))
        assertEquals(listOf(app), TechnicsEarbudAdapter().controlApps)
        assertFalse(resolve("Unrelated headset") is TechnicsEarbudAdapter)
    }

    private fun resolve(name: String): EarbudAdapter =
        requireNotNull(EarbudAdapterRegistry.resolve(identity(name)))

    private fun identity(
        name: String,
        standard: Boolean = true,
        nativeSystemEarbud: Boolean = false,
        serviceUuids: Set<String> = emptySet(),
    ): EarbudIdentity = EarbudIdentity(
        deviceName = name,
        standardHeadset = standard,
        nativeSystemEarbud = nativeSystemEarbud,
        serviceUuids = serviceUuids,
    )

    private fun raceResponse(raceId: Int, vararg payload: Int): ByteArray {
        return raceFrame(0x5B, raceId, *payload)
    }

    private fun raceIndication(raceId: Int, vararg payload: Int): ByteArray {
        return raceFrame(0x5D, raceId, *payload)
    }

    private fun raceFrame(type: Int, raceId: Int, vararg payload: Int): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0x05,
            type.toByte(),
            length.toByte(),
            0x00,
            raceId.toByte(),
            (raceId ushr 8).toByte(),
            *payload.map(Int::toByte).toByteArray(),
        )
    }
}
