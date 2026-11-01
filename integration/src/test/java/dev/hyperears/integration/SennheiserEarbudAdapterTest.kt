package dev.hyperears.integration

import dev.hyperears.protocol.sennheiser.SennheiserGaiaWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SennheiserEarbudAdapterTest {
    @Test
    fun retailNamesSelectTheMatchingModelAdapter() {
        val momentum = resolve("MOMENTUM 4")
        assertEquals("sennheiser-momentum-4", momentum.id)
        assertEquals("Sennheiser MOMENTUM 4", momentum.displayName)
        assertEquals(HeadsetFormFactor.HEADPHONES, momentum.formFactor)
        assertEquals(AdapterResolution.EXACT_MATCH, momentum.resolution)
        assertEquals(
            listOf("sennheiser-gaia"),
            momentum.transports.map(EarbudTransportSpec::id),
        )

        val accentum = resolve("ACCENTUM Wireless")
        assertEquals("sennheiser-accentum", accentum.id)
        assertEquals(HeadsetFormFactor.HEADPHONES, accentum.formFactor)

        val accentumTws = resolve("ACCENTUM True Wireless")
        assertEquals("sennheiser-accentum-tws", accentumTws.id)
        assertEquals(HeadsetFormFactor.TWS, accentumTws.formFactor)
    }

    @Test
    fun brandPrefixedNamesStillResolveToTheFamilyAdapter() {
        assertEquals("sennheiser-family", resolve("Sennheiser headphones").id)
    }

    @Test
    fun ordinaryHeadsetsAreNotCapturedByTheSennheiserFamily() {
        listOf("Sony WH-1000XM5", "Some unnamed headset", "Jabra Elite 85h").forEach { name ->
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(identity(name)))
            assertFalse(name, adapter is SennheiserEarbudAdapter)
        }
    }

    @Test
    fun candidateRequiresAStandardNonNativeHeadset() {
        assertNull(EarbudAdapterRegistry.resolve(identity("MOMENTUM 4", standard = false)))
        assertNull(
            EarbudAdapterRegistry.resolve(
                identity("MOMENTUM 4", nativeSystemEarbud = true),
            ),
        )
        // LE-prefixed duplicates are excluded, but the standard headset still owns the device.
        assertFalse(
            requireNotNull(EarbudAdapterRegistry.resolve(identity("LE_MOMENTUM 4")))
                is SennheiserEarbudAdapter,
        )
    }

    @Test
    fun gaiaServiceUuidAloneNeverCapturesAnUnrelatedHeadset() {
        val adapter = EarbudAdapterRegistry.resolve(
            identity(
                name = "Unrelated headset",
                serviceUuids = setOf(SennheiserGaiaWireCodec.GAIA_SERVICE_UUID),
            ),
        )

        assertTrue(adapter is StandardEarbudAdapter)
        assertFalse(adapter is SennheiserEarbudAdapter)
    }

    @Test
    fun gaiaServiceUuidWidensAnAlreadyEligibleSennheiserHeadset() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                identity(
                    name = "Sennheiser unknown model",
                    serviceUuids = setOf(SennheiserGaiaWireCodec.GAIA_SERVICE_UUID.uppercase()),
                ),
            ),
        )

        assertTrue(adapter is SennheiserEarbudAdapter)
        assertEquals("sennheiser-family", adapter.id)
    }

    @Test
    fun privateCapabilitiesStartLockedBehindASystemBatteryProjection() {
        val adapter = resolve("MOMENTUM 4")
        val snapshot = adapter.snapshot()

        assertTrue(adapter.privateProtocolRequired)
        assertEquals(TransportReadiness.PROTOCOL_HANDSHAKE, adapter.transportReadiness)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, snapshot.batterySource)
        assertTrue(snapshot.capabilities.battery)
        assertTrue(snapshot.capabilities.audioHandoff)
        assertFalse(snapshot.capabilities.noiseControl)
        assertFalse(snapshot.capabilities.windNoiseControl)
        assertTrue(snapshot.supportedNoiseModes.isEmpty())
        assertNull(snapshot.presentationId)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun handshakeStartsWithOnlyTheQualcommCoreVersionProbe() {
        val adapter = resolve("MOMENTUM 4")

        assertEquals(
            listOf(SennheiserGaiaWireCodec.queryQualcommApiVersion).map(ByteArray::toList),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )
    }

    @Test
    fun firstValidResponsePublishesHandshakeAndThenReadsTelemetry() {
        val adapter = resolve("MOMENTUM 4")

        val result = adapter.receive(gaiaResponse(0x0100, 0x03, 0x01))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.queryBattery,
                SennheiserGaiaWireCodec.registerNotification(
                    SennheiserGaiaWireCodec.FEATURE_BATTERY,
                ),
                SennheiserGaiaWireCodec.queryCombinedNoiseMode,
                SennheiserGaiaWireCodec.registerNotification(
                    SennheiserGaiaWireCodec.FEATURE_ANC,
                ),
            ).map(ByteArray::toList),
            result.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun validBatteryReportUnlocksOnlyPrivateBattery() {
        val adapter = resolve("MOMENTUM 4")

        val result = adapter.receive(gaiaResponse(0x0703, 42))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(42, adapter.runtimeState().battery.overall.percent)
        assertNull(adapter.runtimeState().battery.left.percent)
        assertNull(adapter.runtimeState().battery.right.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
    }

    @Test
    fun combinedNoiseReportUnlocksAllFourMomentumModes() {
        val adapter = resolve("MOMENTUM 4")

        val result = adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))

        assertEquals(HandshakeResult.Ready, result.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY, NoiseMode.WIND),
            adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(
            MiLinkCardPresentationId("sennheiser-momentum-4"),
            adapter.snapshot().presentationId,
        )
    }

    @Test
    fun transparencyWinsTheProjectionWhenBothSelectorsAreActive() {
        val adapter = resolve("MOMENTUM 4")

        adapter.receive(combinedNoiseResponse(2, 1, 1, 1, 3, 1))

        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
    }

    @Test
    fun antiWindWinsOverAdaptiveButLosesToTransparency() {
        val adapter = resolve("MOMENTUM 4")

        adapter.receive(combinedNoiseResponse(2, 0, 1, 1, 3, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)

        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 0))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun accentumNeverDeclaresAntiWind() {
        val adapter = resolve("ACCENTUM Wireless")

        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))

        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertFalse(adapter.snapshot().capabilities.windNoiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
    }

    @Test
    fun accentumWaitsForBothTogglesBeforePublishingAMode() {
        val adapter = resolve("ACCENTUM Wireless")

        adapter.receive(gaiaResponse(0x1B05, 1))
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertNull(adapter.runtimeState().noiseMode)

        adapter.receive(gaiaResponse(0x1905, 0))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun momentumWritesEachSelectorSeparately() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))

        val anc = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(anc.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(3, 1),
                SennheiserGaiaWireCodec.setCombinedNoiseMode(2, 0),
                SennheiserGaiaWireCodec.setCombinedNoiseMode(1, 0),
            ).map(ByteArray::toList),
            anc.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(SennheiserGaiaWireCodec.queryCombinedNoiseMode).map(ByteArray::toList),
            anc.readback.map(ByteArray::toList),
        )
    }

    @Test
    fun momentumAntiWindEngagesAdaptiveFirst() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))

        val wind = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))

        assertTrue(wind.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(3, 1),
                SennheiserGaiaWireCodec.setCombinedNoiseMode(1, 1),
            ).map(ByteArray::toList),
            wind.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun accentumTransparencyWritesBothIndependentSwitches() {
        val adapter = resolve("ACCENTUM Wireless")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))

        val transparency = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )

        assertTrue(transparency.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setTransparencyToggle(true),
                SennheiserGaiaWireCodec.setAncToggle(false),
            ).map(ByteArray::toList),
            transparency.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.queryAncToggle,
                SennheiserGaiaWireCodec.queryTransparencyToggle,
            ).map(ByteArray::toList),
            transparency.readback.map(ByteArray::toList),
        )
    }

    @Test
    fun deviceInitiatedNotificationsUpdateThePublishedMode() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        adapter.receive(gaiaNotification(0x1A81, 2, 1, 1, 0, 3, 0))

        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
    }

    @Test
    fun commandEchoAndErrorsNeverUnlockCapabilities() {
        val adapter = resolve("MOMENTUM 4")

        val echo = adapter.receive(SennheiserGaiaWireCodec.setAncToggle(true))
        assertNull(echo.handshake)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())

        val error = adapter.receive(gaiaError(0x1B84, 0x01))
        assertNull(error.handshake)
        assertEquals(1, error.unknownFrames.size)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
    }

    @Test
    fun malformedPayloadsAreIgnoredWithoutUnlockingAnything() {
        val adapter = resolve("MOMENTUM 4")

        adapter.receive(gaiaResponse(0x0703, 101))
        adapter.receive(combinedNoiseResponse(2, 0))

        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertNull(adapter.runtimeState().battery.overall.percent)
    }

    @Test
    fun protocolResetRevokesPrivateEvidenceAndRestoresSystemBatteryFallback() {
        val adapter = resolve("MOMENTUM 4")
        adapter.onSystemBatteryChanged(68)
        adapter.receive(gaiaResponse(0x0703, 42))
        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))

        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)

        adapter.resetProtocolSession()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))

        adapter.receive(combinedNoiseResponse(2, 0, 1, 0, 3, 1))
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun officialControllerIsCataloguedForNavigationAndOwnershipOnly() {
        val app = ControlAppCatalog.sennheiserSmartControl

        assertEquals("com.sonova.chb.control", app.packageName)
        assertEquals("Sennheiser Smart Control", app.displayName)
        assertEquals(app, ControlAppCatalog.find(app.packageName))
        assertEquals(listOf(app), SennheiserEarbudAdapter().controlApps)
        assertEquals(listOf(app), resolve("MOMENTUM 4").controlApps)
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

    private fun gaiaResponse(commandId: Int, vararg payload: Int): ByteArray =
        SennheiserGaiaWireCodec.encode(
            SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
            responseId(commandId),
            payload.map(Int::toByte).toByteArray(),
        )

    private fun gaiaNotification(commandId: Int, vararg payload: Int): ByteArray =
        SennheiserGaiaWireCodec.encode(
            SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
            (commandId and 0xFE7F) or 0x0080,
            payload.map(Int::toByte).toByteArray(),
        )

    private fun gaiaError(commandId: Int, vararg payload: Int): ByteArray =
        SennheiserGaiaWireCodec.encode(
            SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
            responseId(commandId) or 0x0100,
            payload.map(Int::toByte).toByteArray(),
        )

    /** Response encodings differ per command: `0x1Axx` pairs carry the `0x0080` bit offset. */
    private fun responseId(commandId: Int): Int = when {
        commandId == 0x0603 -> 0x0703
        commandId == 0x1206 -> 0x1306
        commandId == 0x1804 -> 0x1904
        commandId == 0x1805 -> 0x1905
        commandId == 0x1A01 -> 0x1B01
        commandId == 0x1A04 -> 0x1B04
        commandId == 0x1A05 -> 0x1B05
        else -> commandId
    }

    private fun combinedNoiseResponse(vararg pairs: Int): ByteArray =
        SennheiserGaiaWireCodec.encode(
            SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
            0x1B01,
            pairs.map(Int::toByte).toByteArray(),
        )
}
