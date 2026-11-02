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
                SennheiserGaiaWireCodec.queryAncToggle,
                SennheiserGaiaWireCodec.queryTransparencyToggle,
                SennheiserGaiaWireCodec.queryCombinedNoiseMode,
                SennheiserGaiaWireCodec.registerNotification(
                    SennheiserGaiaWireCodec.FEATURE_ANC,
                ),
                SennheiserGaiaWireCodec.registerNotification(
                    SennheiserGaiaWireCodec.FEATURE_TRANSPARENT_HEARING,
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

        assertEquals(HandshakeResult.Ready, adapter.receive(gaiaResponse(0x1B05, 1)).handshake)
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))

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
    fun transparencyWinsTheProjectionOnceTheSwitchIsOn() {
        val adapter = resolve("MOMENTUM 4")

        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 1))
        adapter.receive(combinedNoiseResponse(1, 1, 2, 0, 3, 1))

        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
    }

    @Test
    fun antiWindWinsOverAdaptiveButLosesToTransparency() {
        val adapter = resolve("MOMENTUM 4")

        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 2, 2, 0, 3, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)

        adapter.receive(gaiaResponse(0x1905, 1))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        adapter.receive(gaiaResponse(0x1B05, 0))
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
    fun momentumWritesOneFramePerRealStateChange() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        // ANC is already on and transparency already off, so nothing needs to change; the runtime
        // rejects an empty write as a failed control, so the primary frame is written anyway.
        val anc = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        assertTrue(anc.accepted)
        assertEquals(
            listOf(SennheiserGaiaWireCodec.setAncToggle(true)).map(ByteArray::toList),
            anc.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.queryAncToggle,
                SennheiserGaiaWireCodec.queryTransparencyToggle,
                SennheiserGaiaWireCodec.queryCombinedNoiseMode,
            ).map(ByteArray::toList),
            anc.readback.map(ByteArray::toList),
        )

        // The master switch is already on, so transparency is the whole of the transition.
        val transparency = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )
        assertTrue(transparency.accepted)
        assertEquals(
            listOf(SennheiserGaiaWireCodec.setTransparencyToggle(true)).map(ByteArray::toList),
            transparency.commands.map(ByteArray::toList),
        )
    }

    /**
     * Off is the ANC master switch alone: transparent hearing stays formally on but has no effect
     * without ANC, and switching it off would discard the remembered level.
     */
    @Test
    fun momentumOffOnlySwitchesTheAncMasterSwitchOff() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 1))

        val off = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF))

        assertTrue(off.accepted)
        assertEquals(
            listOf(SennheiserGaiaWireCodec.setAncToggle(false)).map(ByteArray::toList),
            off.commands.map(ByteArray::toList),
        )
        assertFalse(
            off.commands.map(ByteArray::toList)
                .contains(SennheiserGaiaWireCodec.setTransparencyToggle(false).toList()),
        )
    }

    /** Switching the master switch on already ends transparent hearing, so off is one frame. */
    @Test
    fun momentumOffToAncWritesTheMasterSwitchAlone() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 0))
        adapter.receive(gaiaResponse(0x1905, 1))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)

        val anc = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        assertTrue(anc.accepted)
        assertEquals(
            listOf(SennheiserGaiaWireCodec.setAncToggle(true)).map(ByteArray::toList),
            anc.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun momentumAntiWindWritesTheSelectorOnceTheMasterSwitchIsOn() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))

        val wind = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))

        assertTrue(wind.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(
                    SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND,
                    SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO,
                ),
            ).map(ByteArray::toList),
            wind.commands.map(ByteArray::toList),
        )
    }

    /** The mode table is the only command that clears a selector the previous mode left behind. */
    @Test
    fun momentumWindToAncClearsTheStaleSelector() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 2, 2, 0, 3, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)

        val anc = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        assertTrue(anc.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(
                    SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND,
                    SennheiserGaiaWireCodec.STATE_OFF,
                ),
            ).map(ByteArray::toList),
            anc.commands.map(ByteArray::toList),
        )
    }

    @Test
    fun momentumTransparencyToWindEndsTransparencyWithTheSelector() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 1))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        val wind = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND))

        assertTrue(wind.accepted)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.setCombinedNoiseMode(
                    SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND,
                    SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO,
                ),
            ).map(ByteArray::toList),
            wind.commands.map(ByteArray::toList),
        )
    }

    /** A write is several independent frames, and the headset reports each one as it lands. */
    @Test
    fun intermediateNoiseReportsAreHeldUntilTheRequestedModeArrives() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 0))
        adapter.receive(gaiaResponse(0x1905, 1))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)

        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        // The master switch lands first, while the cached transparency switch is still on.
        val intermediate = adapter.receive(gaiaNotification(0x1A85, 1))
        assertFalse(intermediate.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(AdapterEffect.RequestState(NoiseModeFeatureState.FEATURE_ID, 150L)),
            intermediate.effects,
        )

        // The optimistic publish already showed the requested mode, so the confirming report must
        // not move the snapshot a second time.
        val settled = adapter.receive(gaiaNotification(0x1885, 0))
        assertFalse(settled.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            listOf(AdapterEffect.CancelStateRequest(NoiseModeFeatureState.FEATURE_ID)),
            settled.effects,
        )
    }

    /** The delay ladder is bounded, so a headset that never settles still publishes a real report. */
    @Test
    fun heldNoiseReportsAreBoundedByTheSettleLadder() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))

        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.ANC))

        listOf(150L, 300L, 500L).forEach { delayMs ->
            val held = adapter.receive(gaiaNotification(0x1A85, 0))
            assertFalse(held.stateChanged)
            assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
            assertEquals(
                listOf(AdapterEffect.RequestState(NoiseModeFeatureState.FEATURE_ID, delayMs)),
                held.effects,
            )
        }

        val forced = adapter.receive(gaiaNotification(0x1A85, 0))
        assertTrue(forced.stateChanged)
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    /** A device report after the expectation cleared must reach the snapshot untouched. */
    @Test
    fun unsolicitedNoiseReportsPassThroughOnceTheExpectationCleared() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))

        adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))

        // The optimistic publish already showed transparency, so this report confirms rather than
        // changes the snapshot.
        val confirmed = adapter.receive(gaiaNotification(0x1885, 1))
        assertFalse(confirmed.stateChanged)
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)

        val unsolicited = adapter.receive(gaiaNotification(0x1885, 0))
        assertTrue(unsolicited.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertTrue(unsolicited.effects.isEmpty())
    }

    @Test
    fun protocolResetDropsThePendingNoiseExpectation() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))

        adapter.controlWritten(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY))
        adapter.resetProtocolSession()

        adapter.receive(gaiaResponse(0x1B05, 1))
        val result = adapter.receive(gaiaResponse(0x1905, 0))

        assertTrue(result.stateChanged)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    /** A cached selector must not survive a reconnection. */
    @Test
    fun protocolResetClearsTheCachedAntiWindSelector() {
        val adapter = resolve("MOMENTUM 4")
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 2, 2, 0, 3, 1))
        assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)

        adapter.resetProtocolSession()

        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))

        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
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
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        adapter.receive(gaiaNotification(0x1A85, 0))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)

        // Transparent hearing only takes effect while the ANC master switch is on.
        adapter.receive(gaiaNotification(0x1A85, 1))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)

        adapter.receive(gaiaNotification(0x1885, 1))
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
        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))

        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.noiseControl)

        adapter.resetProtocolSession()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        assertNull(adapter.snapshot().presentationId)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))

        adapter.receive(gaiaResponse(0x1B05, 1))
        adapter.receive(gaiaResponse(0x1905, 0))
        adapter.receive(combinedNoiseResponse(1, 0, 2, 0, 3, 1))
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
