package dev.hyperears.presentation

import dev.hyperears.integration.*
import dev.hyperears.settings.HeadsetConnectionPresentation
import org.junit.Assert.*
import org.junit.Test

class NativePresentationModelTest {
    @Test fun pairedDiscoveryProjectionNeverChangesConnectionLifecycle() {
        val ended = state(EarbudBattery(case = BatteryReading(76, true)), true)
            .copy(lifecycle = DeviceLifecycle())
        assertFalse(ended.sessionActive)
        assertNull(NativeHeadsetProjection.from(ended))
        val projected = requireNotNull(NativeHeadsetProjection.fromPairedDiscovery(ended))
        assertEquals(76, projected.chargingCase.percent)
        assertFalse(ended.sessionActive)
    }

    @Test fun coralPrivateBatteryFeedsNativeConnectionPopupWithoutDuplicatePresentation() {
        val adapter = RoseCoralAdapter()
        val body = byteArrayOf(0xdd.toByte(), 0, 4, 12, 80, 60, 0x80.toByte())
        adapter.receive(body + byteArrayOf(body.sumOf { it.toInt() and 255 }.toByte(), 0xaa.toByte()))
        val connected = EarbudState(adapter = adapter.snapshot(), address = "02:00:00:00:00:01",
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
            features = adapter.runtimeState().features)
        val projection = requireNotNull(NativeHeadsetProjection.from(connected))
        assertFalse(projection.singleBattery)
        assertTrue(projection.showCaseInPopup)
        assertEquals(80, projection.left.percent)
        assertEquals(60, projection.right.percent)
        assertEquals(0, projection.chargingCase.percent)
        assertTrue(projection.chargingCase.charging)
        val metadata = NativePresentationMetadata(HeadsetConnection("coral-connection", 0), NativeAdmission.HYPEREARS)
        val gate = ConnectionPresentationGate()
        var shown = 0
        assertTrue(gate.tryPresent(metadata, 100) { shown++; true })
        assertFalse(gate.tryPresent(metadata, 101) { shown++; true })
        assertEquals(1, shown)
        assertFalse(gate.tryPresent(metadata.copy(connection = HeadsetConnection("system", 0),
            admission = NativeAdmission.SYSTEM), 102) { shown++; true })
    }
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
        assertFalse(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery.fromAggregate(20)))).canShowConnectionHint)
        assertFalse(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery(case = BatteryReading(80, false)), true))).canShowConnectionHint)
        assertTrue(requireNotNull(NativeHeadsetProjection.from(
            state(EarbudBattery(left = BatteryReading(0, false)), true))).canShowConnectionHint)
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
}
