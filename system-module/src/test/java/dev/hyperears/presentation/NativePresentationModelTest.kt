package dev.hyperears.presentation

import dev.hyperears.integration.*
import dev.hyperears.settings.HeadsetConnectionPresentation
import org.junit.Assert.*
import org.junit.Test

class NativePresentationModelTest {
    private fun state(battery: EarbudBattery, privateBattery: Boolean = false) = EarbudState(
        adapter = StandardEarbudAdapter().snapshot().copy(
            batterySource = if (privateBattery) BatterySource.PRIVATE_PROTOCOL else BatterySource.SYSTEM_AGGREGATE,
        ),
        address = "02:00:00:00:00:01",
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
    ).withFeature(BatteryFeatureState(battery))

    @Test
    fun aggregateNeverBecomesTwoIndependentBatteryReadings() {
        for (privateBattery in listOf(false, true)) {
            val p = requireNotNull(NativeHeadsetProjection.from(state(EarbudBattery.fromAggregate(30), privateBattery)))
            assertTrue(p.singleBattery)
            assertEquals(30, p.left.percent)
            assertNull(p.right.percent)
            assertNull(p.chargingCase.percent)
        }
    }

    @Test
    fun partialTelemetryPreservesZeroChargingAndUnknownComponents() {
        val p = requireNotNull(NativeHeadsetProjection.from(state(EarbudBattery(
            left = BatteryReading(0, true), right = BatteryReading(null, false),
            case = BatteryReading(76, false)), true)))
        assertFalse(p.singleBattery)
        assertEquals(0, p.left.percent)
        assertTrue(p.left.charging)
        assertNull(p.right.percent)
        assertEquals(76, p.chargingCase.percent)
    }

    @Test
    fun connectedHintWaitsForRealEarBatteryWithoutFabricatingComponents() {
        val unknown = state(EarbudBattery(), true)
        val headphones = unknown.copy(adapter = unknown.adapter!!.copy(formFactor = HeadsetFormFactor.HEADPHONES))
        assertFalse(requireNotNull(NativeHeadsetProjection.from(headphones)).canShowConnectionHint)
        assertTrue(requireNotNull(NativeHeadsetProjection.from(
            headphones.withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(20))))).canShowConnectionHint)
        assertTrue(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery.fromAggregate(20)))).canShowConnectionHint)
        assertFalse(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery(case = BatteryReading(80, false)), true))).canShowConnectionHint)
        assertTrue(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery(left = BatteryReading(0, false)), true))).canShowConnectionHint)
    }

    @Test
    fun everyRegisteredAdapterCanPresentSystemBatteryBeforeProtocolConfirmation() {
        EarbudAdapterRegistry.adapters.forEach { adapter ->
            val initial = EarbudState(
                adapter = adapter.snapshot(),
                address = "02:00:00:00:00:01",
                lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
            ).withFeature(BatteryFeatureState(EarbudBattery.fromSystemAggregate(100)))
            val projection = requireNotNull(NativeHeadsetProjection.from(initial))
            assertTrue(adapter.id, projection.canShowConnectionHint)
            assertTrue(adapter.id, projection.singleBattery)
            assertEquals(adapter.id, 100, projection.left.percent)
            assertNull(adapter.id, projection.right.percent)
            assertNull(adapter.id, projection.chargingCase.percent)
            assertEquals(adapter.id,
                if (adapter.formFactor == HeadsetFormFactor.TWS) "earphone" else "single_battery_head",
                projection.singleBatteryHintArtwork)
        }
    }

    @Test
    fun ceramicsXLogKeepsAggregateTelemetryAndAllConfirmedNoiseModes() {
        val adapter = RoseLuliXAdapter()
        adapter.onSystemBatteryChanged(100)
        adapter.receive(byteArrayOf(0x00, 0x27, 0x02, 0x00, 0x03, 0x0C, 0x01, 0x02))
        // This unclassified report appeared before disconnection; it proves no battery fields.
        adapter.receive(byteArrayOf(0x09, 0xFF.toByte(), 0, 0, 1, 9, 1, 0x0B, 0, 0, 0x1E))
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertEquals(NoiseMode.entries.toSet(), adapter.snapshot().supportedNoiseModes)
        val input = state(EarbudBattery.fromSystemAggregate(100)).copy(adapter = adapter.snapshot())
        val projection = requireNotNull(NativeHeadsetProjection.from(input))
        assertTrue(projection.canShowConnectionHint)
        assertTrue(projection.singleBattery)
        assertEquals(100, projection.left.percent)
        assertFalse(projection.showCaseInPopup)
    }

    @Test
    fun popupShowsKnownCaseWithoutChangingChargeStateAndClearsUnknownCase() {
        val battery = EarbudBattery(
            left = BatteryReading(100, false), right = BatteryReading(100, false),
            case = BatteryReading(99, false),
        )
        val projected = requireNotNull(NativeHeadsetProjection.from(state(battery, true)))
        assertTrue(projected.showCaseInPopup)
        assertEquals(battery.case, projected.chargingCase)
        assertFalse(projected.left.charging)
        assertFalse(projected.right.charging)
        val zero = requireNotNull(NativeHeadsetProjection.from(
            state(battery.copy(case = BatteryReading(0, false)), true)))
        assertTrue(zero.showCaseInPopup)
        val unknown = requireNotNull(NativeHeadsetProjection.from(
            state(battery.copy(case = BatteryReading(null, false)), true)))
        assertFalse(unknown.showCaseInPopup)
        val aggregate = requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery.fromAggregate(80))))
        assertFalse(aggregate.showCaseInPopup)
        val headphoneState = state(battery, true).let {
            it.copy(adapter = it.adapter!!.copy(formFactor = HeadsetFormFactor.HEADPHONES))
        }
        assertFalse(requireNotNull(NativeHeadsetProjection.from(headphoneState)).showCaseInPopup)
    }

    @Test
    fun protocolAvailabilityDoesNotChangePhysicalConnectionEligibility() {
        val state = state(EarbudBattery.fromAggregate(52)).copy(lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.DORMANT,
            protocolHandshake = ProtocolHandshakeState.PENDING,
        ))
        assertFalse(state.connected)
        assertNotNull(NativeHeadsetProjection.from(state))
        assertNull(NativeHeadsetProjection.from(state.copy(lifecycle = DeviceLifecycle())))
    }

    @Test
    fun replayReconnectAndUnknownOwnershipCannotGenerateDuplicateHints() {
        val gate = ConnectionPresentationGate()
        val connected = HeadsetConnection("physical-1", 100)
        val pending = NativePresentationMetadata(connected)
        assertFalse(gate.tryPresent(pending, 150) { fail("Unconfirmed ownership"); true })
        val admitted = pending.copy(admission = NativeAdmission.HYPEREARS)
        assertTrue(gate.tryPresent(admitted, 200) { true })
        assertFalse(gate.tryPresent(admitted, 300) { fail("Duplicate submission"); true })
        assertFalse(gate.tryPresent(admitted.copy(admission = NativeAdmission.SYSTEM), 300) { true })
        assertFalse(gate.tryPresent(admitted.copy(connection = HeadsetConnection("replay", 100), replay = true), 400) { true })
        assertFalse(gate.tryPresent(admitted.copy(connection = HeadsetConnection("replay", 100)), 500) { true })
        assertTrue(gate.tryPresent(admitted.copy(connection = HeadsetConnection("physical-2", 400)), 500) { true })
        assertFalse(gate.tryPresent(admitted.copy(connection = HeadsetConnection("old", 0)), 20_000) { true })
    }

    @Test
    fun blockedPresentationCanSubmitOnALaterEventWithoutDuplicateOrLateDisplay() {
        val gate = ConnectionPresentationGate()
        val admitted = NativePresentationMetadata(HeadsetConnection("physical", 100), NativeAdmission.HYPEREARS)
        assertFalse(gate.tryPresent(admitted, 200) { false })
        assertFalse(gate.tryPresent(admitted, 300) { false })
        assertTrue(gate.tryPresent(admitted, 400) { true })
        assertFalse(gate.tryPresent(admitted, 500) { fail("Already submitted"); true })
        val expired = admitted.copy(connection = HeadsetConnection("expired", 100))
        assertFalse(gate.tryPresent(expired, 200) { false })
        assertFalse(gate.tryPresent(expired, 10_101) { fail("Expired connection"); true })
    }

    @Test
    fun replayCannotCancelALiveConnectionWaitingForAdmissionOrBattery() {
        val gate = ConnectionPresentationGate()
        val pending = NativePresentationMetadata(HeadsetConnection("physical", 100))
        assertFalse(gate.tryPresent(pending, 150) { fail("Not admitted"); true })
        assertFalse(gate.tryPresent(pending.copy(replay = true), 200) { fail("Replay"); true })
        val admitted = pending.copy(admission = NativeAdmission.HYPEREARS)
        assertFalse(gate.tryPresent(admitted, 300) { false })
        assertFalse(gate.tryPresent(admitted.copy(replay = true), 400) { fail("Replay"); true })
        assertTrue(gate.tryPresent(admitted, 500) { true })
        assertFalse(gate.tryPresent(admitted, 600) { fail("Duplicate"); true })
    }

    @Test
    fun smartUsesTheNativeSceneWhileExplicitChoicesDoNotRequireIt() {
        assertEquals(NativePresentationTarget.POPUP,
            resolveNativePresentationTarget(HeadsetConnectionPresentation.SMART, true))
        assertEquals(NativePresentationTarget.ISLAND,
            resolveNativePresentationTarget(HeadsetConnectionPresentation.SMART, false))
        assertNull(resolveNativePresentationTarget(HeadsetConnectionPresentation.SMART, null))
        for (scene in listOf(true, false, null)) {
            assertEquals(NativePresentationTarget.POPUP,
                resolveNativePresentationTarget(HeadsetConnectionPresentation.BATTERY_POPUP, scene))
            assertEquals(NativePresentationTarget.ISLAND,
                resolveNativePresentationTarget(HeadsetConnectionPresentation.SYSTEM_HINT, scene))
            assertNull(resolveNativePresentationTarget(HeadsetConnectionPresentation.OFF, scene))
        }
    }

    @Test
    fun absentNativeCirculationSubsystemDoesNotBlockDisplay() {
        assertFalse(displayBlockedByNativeCirculation(flowOwns = null))
    }

    @Test
    fun observedNativeCirculationKeepsItsVerdict() {
        assertTrue(displayBlockedByNativeCirculation(flowOwns = true))
        assertFalse(displayBlockedByNativeCirculation(flowOwns = false))
    }

    /**
     * The reported build has no installable native hint subsystem (plugin ready hint=false), so every
     * circulation reading is null there. That absence alone must never read as "the native flow owns the
     * display": the popup stays gated only by the native window occupancy tag and the connection gate.
     */
    @Test
    fun missingHintSubsystemNeverGrantsACirculationBlock() {
        for (flowOwns in listOf<Boolean?>(null, true, false)) {
            assertEquals(flowOwns.toString(), flowOwns == true, displayBlockedByNativeCirculation(flowOwns))
        }
    }

    @Test
    fun legacyRecordReadersDecideBeforeTheStableWrappers() {
        // Old builds carry the three record getters; their verdict expression must stay unchanged and
        // the wrappers must never override it, even when they would disagree.
        assertTrue(flowOwnsFromReadings(Triple(1, false, false), Pair(false, false)) == true)
        assertTrue(flowOwnsFromReadings(Triple(0, true, false), null) == true)
        assertTrue(flowOwnsFromReadings(Triple(0, false, true), null) == true)
        assertEquals(false, flowOwnsFromReadings(Triple(0, false, false), Pair(true, true)))
    }

    @Test
    fun stableWrappersOnlyDecideWhenLegacyNamesAreAbsent() {
        assertTrue(flowOwnsFromReadings(null, Pair(true, false)) == true)
        assertTrue(flowOwnsFromReadings(null, Pair(false, true)) == true)
        assertEquals(false, flowOwnsFromReadings(null, Pair(false, false)))
    }

    @Test
    fun missingLegacyAndWrapperNamesBothReportIndeterminate() {
        // Neither the record's old names nor the MBN wrappers exist: no verdict, and an indeterminate
        // reading must never block the display through displayBlockedByNativeCirculation.
        assertNull(flowOwnsFromReadings(null, null))
        assertFalse(displayBlockedByNativeCirculation(flowOwnsFromReadings(null, null)))
    }

    @Test
    fun caseAreaIsForcedHiddenOnlyOnContractAbsentBuilds() {
        // Exhaustive 2x2: a contract-bearing build applies the projection verdict unchanged, and a
        // contract-absent build hides the area even when the projection wanted to show it.
        for (missing in listOf(true, false)) {
            for (showCase in listOf(true, false)) {
                val expected = if (missing) false else showCase
                assertEquals("missing=$missing showCase=$showCase",
                    expected, caseAreaEffective(missing, showCase))
            }
        }
    }
}
