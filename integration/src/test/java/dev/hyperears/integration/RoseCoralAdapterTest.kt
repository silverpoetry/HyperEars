package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class RoseCoralAdapterTest {
    @Test fun audioControlsUseSourceModesAndReadbackWithoutOptimismOrDisconnectEffects() {
        val adapter = coral()
        val unsupported = RoseCoralControlRequest.SetAudioMode(RoseCoralAudioMode.LDAC)
        assertFalse(adapter.supportsControl(unsupported))
        adapter.receive(report(2, 0x2B, 4))
        assertFalse(adapter.supportsControl(unsupported))
        adapter.receive(report(3, 0x2B, 0, 1))
        assertFalse(adapter.supportsControl(unsupported))
        adapter.receive(report(2, 0x2B, 0))
        assertEquals(RoseCoralAudioFeatureState(RoseCoralAudioMode.AAC_SBC),
            adapter.runtimeState().features.get<RoseCoralAudioFeatureState>())
        RoseCoralAudioMode.entries.forEach { mode ->
            val request = RoseCoralControlRequest.SetAudioMode(mode)
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            val result = adapter.executeControl(request)
            assertTrue(result.accepted)
            assertArrayEquals(byteArrayOf(0x2B, mode.wire.toByte()), result.commands.single().let { it.copyOfRange(3, it.size - 2) })
            assertArrayEquals(hex("FA 2B"), result.readback.single().let { it.copyOfRange(3, it.size - 2) })
            assertFalse(result.stateChanged)
            assertTrue(adapter.controlWritten(request).isEmpty())
            assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(request).confirmation)
        }
        assertEquals(RoseCoralAudioMode.AAC_SBC, adapter.runtimeState().features.get<RoseCoralAudioFeatureState>()?.mode)
        adapter.receive(report(2, 0x2B, 3))
        assertEquals(RoseCoralAudioMode.LC3, adapter.runtimeState().features.get<RoseCoralAudioFeatureState>()?.mode)
        val features = adapter.runtimeState().features
        assertEquals(features, FeatureStateTransport.decode(FeatureStateTransport.encode(features)))
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(unsupported))
    }
    @Test fun audioReportAdmitsOnlyAudioFeatureWithoutNoise() {
        val adapter = coral()
        adapter.receive(report(2, 0x2B, 0))
        assertNotNull(adapter.runtimeState().features.get("rose.coral.audio"))
        assertEquals("rose-coral-rs-common-v2-settings", adapter.miLinkCardPresentationId?.value)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertFalse(adapter.supportsControl(RoseCoralControlRequest.SetMultipoint(true)))
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get("rose.coral.audio"))
    }
    @Test fun firmwareReportIsReadOnlyInfoAndAdmitsSettingsWithoutNoise() {
        val adapter = coral()
        val result = adapter.receive(report(4, 0x0D, 0, 5, 0))
        assertEquals("050", adapter.runtimeState().features.get<RoseCoralInfoFeatureState>()?.firmwareVersion)
        assertEquals("rose-coral-rs-common-v2-settings", adapter.miLinkCardPresentationId?.value)
        assertTrue(result.commands.isEmpty())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertFalse(adapter.supportsControl(RoseCoralControlRequest.SetGameMode(true)))
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get("rose.coral.info"))
        assertNull(adapter.miLinkCardPresentationId)
    }

    @Test fun firmwareRejectsShortValuesPreservesUnsignedComponentsAndIgnoresTrailingBytes() {
        val adapter = coral()
        adapter.receive(report(3, 0x0D, 0, 5))
        assertNull(adapter.runtimeState().features.get<RoseCoralInfoFeatureState>())
        assertNull(adapter.miLinkCardPresentationId)
        adapter.receive(report(6, 0x0D, 255, 128, 10, 99, 88))
        val info = adapter.runtimeState().features.get<RoseCoralInfoFeatureState>()
        assertEquals(RoseCoralInfoFeatureState(255, 128, 10), info)
        assertEquals("25512810", info?.firmwareVersion)
        adapter.receive(report(2, 0x0D, 7))
        assertEquals(info, adapter.runtimeState().features.get<RoseCoralInfoFeatureState>())
        val snapshot = adapter.runtimeState().features
        assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.protocolSession.encode(RoseCoralControlRequest.SetBassLevel(5)).isEmpty())
    }
    private fun coral(): EarbudAdapter = requireNotNull(EarbudAdapterRegistry.resolve(
        EarbudIdentity("ROSE Coral", true, serviceUuids = setOf(RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID)),
    ))

    @Test fun exactQueryAndBinaryNoiseAreSourceSpecific() {
        val adapter = coral()
        assertArrayEquals(hex("FF 00 0C FA 0D 0C 09 2A 0E 32 2B 01 07 2F 2E 21 AA"),
            adapter.beginHandshake().commands.single())
        adapter.receive(report(2, 9, 1))
        assertEquals(setOf(NoiseMode.ANC, NoiseMode.OFF), adapter.effectiveSupportedNoiseModes())
        assertFalse(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)).accepted)
        val write = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF))
        assertTrue(write.accepted)
        assertArrayEquals(hex("FF 01 02 09 02 0D AA"), write.commands.single())
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertArrayEquals(hex("FF 02 02 FA 09 06 AA"), write.readback.single())
    }

    @Test fun batteryPreservesChargingAndUnknownNestedDataCannotGrantCapabilities() {
        val adapter = coral()
        adapter.receive(report(4, 0x77, 2, 9, 1))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(report(4, 12, 0xDD, 92, 0xE4))
        assertEquals(BatteryReading(93, true), adapter.runtimeState().battery.left)
        assertEquals(BatteryReading(92, false), adapter.runtimeState().battery.right)
        assertEquals(BatteryReading(100, true), adapter.runtimeState().battery.case)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(report(2, 9, 7))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
    }

    @Test fun validFrameInsideOpaqueFragmentDoesNotCreateFakeState() {
        val inner = report(2, 9, 1).map { it.toInt() and 255 }
        val outer = report(*(listOf(inner.size + 2, 0x77) + inner + listOf(0x55)).toIntArray())
        val adapter = coral()
        adapter.receive(outer.copyOfRange(0, outer.size - 3))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(outer.copyOfRange(outer.size - 3, outer.size))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
    }

    @Test fun advancedEvidenceUsesSettingsPresentationAndNeverOptimisticPolicy() {
        val adapter = coral()
        val request = RoseCoralControlRequest.SetGameMode(true)
        assertFalse(adapter.supportsControl(request))
        adapter.receive(report(2, 0x0E, 0))
        assertEquals("rose-coral-rs-common-v2-settings", adapter.miLinkCardPresentationId?.value)
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.supportsControl(RoseCoralControlRequest.SetMultipoint(true)))
        assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(request).confirmation)
        assertNull(adapter.controlPolicy(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)).stateAfterWrite)
        assertFalse(adapter.executeControl(request).stateChanged)
        assertEquals(false, adapter.runtimeState().features.get<RoseCoralSettingsFeatureState>()?.gameMode)
    }

    @Test fun advancedControlsUseExactDomainsReadbackAndStateSerialization() {
        val adapter = coral()
        adapter.receive(report(2, 14, 0, 2, 0x32, 1, 3, 0x2A, 5, 2, 2, 0x2E, 3, 2, 7, 1))
        val expected = RoseCoralSettingsFeatureState(false, true, 2, 3, true)
        assertEquals(expected, adapter.runtimeState().features.get<RoseCoralSettingsFeatureState>())
        val controls = listOf(
            RoseCoralControlRequest.SetGameMode(true) to hex("0E 01"),
            RoseCoralControlRequest.SetMultipoint(false) to hex("32 00"),
            RoseCoralControlRequest.SetBassLevel(0) to hex("2A 05 00"),
            RoseCoralControlRequest.SetPromptVolume(5) to hex("2E 05"),
            RoseCoralControlRequest.SetTouchEnabled(false) to hex("07 00"),
        )
        controls.forEach { (request, payload) ->
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            val result = adapter.executeControl(request)
            assertTrue(result.accepted)
            assertArrayEquals(payload, result.commands.single().let { it.copyOfRange(3, it.size - 2) })
            assertEquals(0xFA.toByte(), result.readback.single()[3])
            assertFalse(result.stateChanged)
            assertNull(adapter.controlPolicy(request).stateAfterWrite)
        }
        assertEquals(expected, adapter.runtimeState().features.get<RoseCoralSettingsFeatureState>())
        assertFalse(adapter.supportsControl(RoseCoralControlRequest.SetBassLevel(3)))
        assertFalse(adapter.supportsControl(RoseCoralControlRequest.SetPromptVolume(0)))
        val snapshot = adapter.runtimeState().features
        assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
        adapter.receive(report(2, 14, 3, 3, 0x2A, 5, 4, 2, 0x2E, 0))
        assertEquals(expected, adapter.runtimeState().features.get<RoseCoralSettingsFeatureState>())
    }

    @Test fun touchAndFindReportsRemainAtomicAndFindPreservesUnknownSide() {
        val adapter = coral()
        val touch = RoseCoralControlRequest.SetTouch(RoseCoralTouchSlot.RIGHT_HOLD, RoseCoralTouchAction.ANC_CYCLE)
        val find = RoseCoralControlRequest.SetFind(RoseCoralFindTarget.BOTH)
        assertFalse(adapter.supportsControl(touch))
        assertFalse(adapter.supportsControl(find))
        adapter.receive(report(17, 1, 1, 0, 2, 1, 3, 2, 4, 3, 0x11, 4, 0x12, 5, 0x13, 6, 0x14, 7))
        val mapping = adapter.runtimeState().features.get<RoseCoralTouchFeatureState>()
        assertEquals(RoseCoralTouchAction.ASSISTANT, mapping?.rightHold)
        assertArrayEquals(hex("01 14 08"), adapter.executeControl(touch).commands.single().let { it.copyOfRange(3, it.size - 2) })
        assertEquals(mapping, adapter.runtimeState().features.get<RoseCoralTouchFeatureState>())
        adapter.receive(report(3, 1, 1, 8))
        assertEquals(mapping, adapter.runtimeState().features.get<RoseCoralTouchFeatureState>())
        adapter.receive(report(2, 0x2F, 1))
        assertEquals(RoseCoralFindFeatureState(true, null), adapter.runtimeState().features.get<RoseCoralFindFeatureState>())
        adapter.receive(report(2, 0x2F, 2, 2, 0x2F, 5))
        assertEquals(RoseCoralFindFeatureState(false, true), adapter.runtimeState().features.get<RoseCoralFindFeatureState>())
        assertArrayEquals(hex("2F 03"), adapter.executeControl(find).commands.single().let { it.copyOfRange(3, it.size - 2) })
        assertEquals(find, ControlRequestTransport.decode(ControlRequestTransport.encode(find)))
        assertEquals(touch, ControlRequestTransport.decode(ControlRequestTransport.encode(touch)))
        assertEquals(adapter.runtimeState().features, FeatureStateTransport.decode(FeatureStateTransport.encode(adapter.runtimeState().features)))
    }

    @Test fun resetClearsEveryEvidenceAndRestartsSequenceWhileNativeRemainsExcluded() {
        val adapter = coral()
        adapter.receive(report(2, 9, 1, 2, 14, 1, 2, 0x2F, 3, 4, 12, 10, 20, 30))
        repeat(256) { adapter.protocolSession.encode(StandardControlRequest.Refresh) }
        assertEquals(0, adapter.protocolSession.encode(StandardControlRequest.Refresh).single()[1].toInt())
        adapter.resetProtocolSession()
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertTrue(adapter.runtimeState().features.values.isEmpty())
        assertNull(adapter.miLinkCardPresentationId)
        assertEquals(0, adapter.beginHandshake().commands.single()[1].toInt())
        assertFalse(adapter.matches(EarbudIdentity("ROSE Coral", false)))
        assertFalse(adapter.matches(EarbudIdentity("ROSE Coral", true, nativeSystemEarbud = true)))
        assertFalse(adapter.matches(EarbudIdentity("Other", true, serviceUuids = setOf(RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID))))
        assertTrue(adapter.matches(EarbudIdentity(" rose CORAL ", true)))
        assertEquals(InitialProtocolFailureResolution.KeepDormant, adapter.onInitialProtocolUnavailable())
        assertEquals(listOf(ControlAppCatalog.roseLink), adapter.controlApps)
    }

    @Test fun unavailableComponentClearsOnlyItAndRetainsValidBatteryEvidence() {
        val adapter = coral()
        adapter.receive(report(4, 12, 0xDD, 92, 0xFF))
        assertEquals(BatteryReading(93, true), adapter.runtimeState().battery.left)
        assertEquals(BatteryReading(92, false), adapter.runtimeState().battery.right)
        assertEquals(BatteryReading(null, false), adapter.runtimeState().battery.case)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        adapter.receive(report(4, 12, 90, 89, 0xE4))
        assertEquals(BatteryReading(100, true), adapter.runtimeState().battery.case)
        adapter.receive(report(4, 12, 88, 87, 0xFF))
        assertEquals(BatteryReading(88, false), adapter.runtimeState().battery.left)
        assertEquals(BatteryReading(null, false), adapter.runtimeState().battery.case)
        adapter.receive(report(4, 12, 0xFF, 0x7F, 0xFE))
        assertEquals(EarbudBattery(), adapter.runtimeState().battery)
        val unknown = coral()
        unknown.receive(report(4, 12, 0xFF, 0x7F, 0xFE))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, unknown.effectiveBatterySource())
        assertEquals(EarbudBattery(), unknown.runtimeState().battery)
    }

    @Test fun findCleanupIsOwnedBoundedAndNeverReplayedIntoAnotherSession() {
        val adapter = coral()
        adapter.receive(report(2, 0x2F, 3))
        // Observing an already ringing device never grants cleanup ownership.
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        val start = RoseCoralControlRequest.SetFind(RoseCoralFindTarget.BOTH)
        assertTrue(adapter.executeControl(start).accepted)
        // Encoding alone does not arm an action that might have failed to write.
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        val first = adapter.controlWritten(start).single() as AdapterEffect.ScheduleProtocolCleanup
        assertEquals(30_000L, first.delayMs)
        val second = adapter.controlWritten(start).single() as AdapterEffect.ScheduleProtocolCleanup
        assertTrue(adapter.expireProtocolWorkflow(first.key, first.lease).isEmpty())
        val stop = adapter.expireProtocolWorkflow(second.key, second.lease)
        assertEquals(1, stop.size)
        assertTrue(adapter.expireProtocolWorkflow(second.key, second.lease).isEmpty())
        adapter.controlWritten(start)
        assertEquals(1, adapter.prepareProtocolRelease().size)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        adapter.controlWritten(start)
        adapter.resetProtocolSession()
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
    }

    companion object {
        fun report(vararg body: Int): ByteArray {
            val head = byteArrayOf(0xDD.toByte(), 0) + body.map(Int::toByte).toByteArray()
            return head + byteArrayOf(head.sumOf { it.toInt() and 255 }.toByte(), 0xAA.toByte())
        }
        fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }
    @Test fun exactCoralPrecedesSharedUuidWithoutStaticPrivateCapabilities() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(
            "ROSE Coral", true, serviceUuids = setOf(RoseBudsFeelProtocolFamilyAdapter.DATA_CHANNEL_UUID),
        )))
        assertEquals("rose-coral-rs-common-v2", adapter.id)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertNull(adapter.miLinkCardPresentationId)
        assertEquals(HeadsetFormFactor.TWS, adapter.formFactor)
    }
}
