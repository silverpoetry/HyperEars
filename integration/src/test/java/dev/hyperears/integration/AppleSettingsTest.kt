package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleSettingsTest {
    @Test fun validMicrophoneReportConfirmsProtocolWithoutListeningAdmission() {
        val adapter = AppleAirPodsAdapter()
        assertEquals(HandshakeResult.Ready, adapter.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 1, 2, 0, 0, 0)).handshake)
        assertNotNull(adapter.runtimeState().features.get("apple.settings"))
        assertNull(adapter.runtimeState().features.get<AppleListeningModeFeatureState>())
        assertNull(adapter.miLinkCardPresentationId)
    }

    private data class Case(val id: Int, val old: Int, val target: Int, val request: AppleControlRequest, val index: Int = 0)
    private val cases = listOf(
        Case(0x01, 0, 2, AppleControlRequest.SetMicrophone(AppleMicrophone.LEFT)),
        Case(0x1b, 2, 1, AppleControlRequest.SetOneBudAnc(true)),
        Case(0x28, 2, 1, AppleControlRequest.SetConversationAwareness(true)),
        Case(0x26, 2, 1, AppleControlRequest.SetPersonalizedVolume(true)),
        Case(0x0a, 2, 1, AppleControlRequest.SetWearingDetection(true)),
        Case(0x25, 2, 1, AppleControlRequest.SetVolumeSwipe(true)),
        Case(0x17, 0, 2, AppleControlRequest.SetDoublePressInterval(AppleIntervalLevel.LEVEL_2)),
        Case(0x18, 0, 2, AppleControlRequest.SetHoldInterval(AppleIntervalLevel.LEVEL_2)),
        Case(0x23, 0, 2, AppleControlRequest.SetSwipeInterval(AppleIntervalLevel.LEVEL_2)),
        Case(0x2e, 25, 75, AppleControlRequest.SetAdaptiveStrength(75)),
        Case(0x24, 3, 2, AppleControlRequest.SetCallMapping(AppleCallMapping.FLIPPED), 1),
        Case(0x34, 2, 1, AppleControlRequest.SetAllowOff(true)),
        Case(0x1f, 25, 75, AppleControlRequest.SetChimeVolume(75)),
        Case(0x35, 2, 1, AppleControlRequest.SetSleepPause(true)),
    )

    @Test fun everyControlRequiresOwnReportAndUsesDeviceConfirmationPreservingOtherBytes() {
        cases.forEach { case ->
            val adapter = AppleAirPodsAdapter()
            assertFalse(adapter.supportsControl(case.request))
            assertFalse(adapter.executeControl(case.request).accepted)
            adapter.receive(report(0x0d, listOf(4, 0, 0, 0)))
            assertFalse(adapter.supportsControl(case.request))
            val old = mutableListOf(253, 254, 255, 128).also { it[case.index] = case.old }
            adapter.receive(report(case.id, old))
            val before = adapter.runtimeState()
            val result = adapter.executeControl(case.request)
            assertTrue("id=${case.id}", result.accepted)
            val expected = if (case.id == 0x2e) listOf(case.target, 0, 0, 0)
                else old.toMutableList().also { it[case.index] = case.target }
            assertEquals(listOf(report(case.id, expected).toList()), result.commands.map(ByteArray::toList))
            assertEquals(listOf(dev.hyperears.protocol.apple.AppleAapWireCodec.requestNotifications.toList()), result.readback.map(ByteArray::toList))
            assertFalse(result.stateChanged)
            assertEquals(before, adapter.runtimeState())
            assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(case.request).confirmation)
            assertNull(adapter.controlPolicy(case.request).stateAfterWrite)
            adapter.receive(report(case.id, expected))
            assertFalse(before == adapter.runtimeState())
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<AppleSettingsFeatureState>())
            assertFalse(adapter.supportsControl(case.request))
            assertFalse(adapter.executeControl(case.request).accepted)
        }
    }

    @Test fun invalidDomainsNeverConfirmAndRetainPreviousValidReportIncludingUnownedBytes() {
        cases.forEach { case ->
            val valid = when (case.id) {
                0x01, 0x17, 0x18, 0x23 -> 0..2
                0x2e -> 0..100
                0x1f -> 15..100
                0x24 -> 2..3
                else -> 1..2
            }
            (0..255).filterNot { it in valid }.forEach { invalid ->
                val adapter = AppleAirPodsAdapter()
                val bad = mutableListOf(99, 98, 97, 96).also { it[case.index] = invalid }
                adapter.receive(report(case.id, bad))
                assertNull(adapter.runtimeState().features.get<AppleSettingsFeatureState>())
                assertFalse(adapter.supportsControl(case.request))
                adapter.receive(report(0x0d, listOf(4, 0, 0, 0)))
                val good = mutableListOf(255, 254, 253, 252).also { it[case.index] = case.old }
                adapter.receive(report(case.id, good))
                val before = adapter.runtimeState()
                adapter.receive(report(case.id, bad))
                assertEquals(before, adapter.runtimeState())
                val expected = if (case.id == 0x2e) listOf(case.target, 0, 0, 0)
                    else good.toMutableList().also { it[case.index] = case.target }
                assertEquals(listOf(report(case.id, expected).toList()), adapter.executeControl(case.request).commands.map(ByteArray::toList))
            }
        }
    }

    @Test fun adaptiveRequiresActuallyReportedModeAndRangeAtAdapterAndSessionBoundaries() {
        val adapter = AppleAirPodsAdapter()
        val request = AppleControlRequest.SetAdaptiveStrength(50)
        adapter.receive(report(0x2e, listOf(25, 255, 254, 253)))
        assertFalse(adapter.supportsControl(request))
        adapter.receive(report(0x0d, listOf(4, 0, 0, 0)))
        assertTrue(adapter.supportsControl(request))
        adapter.receive(report(0x0d, listOf(2, 0, 0, 0)))
        assertFalse(adapter.supportsControl(request))
        val session = SessionFixture().newSession()
        assertTrue(session.encode(request).isEmpty())
        session.offer(report(0x2e, listOf(25, 0, 0, 0)))
        assertTrue(session.encode(request).isEmpty())
        session.offer(report(0x0d, listOf(4, 0, 0, 0)))
        assertFalse(session.encode(request).isEmpty())
        listOf(-1, 101, 255, Int.MAX_VALUE).forEach {
            val invalid = AppleControlRequest.SetAdaptiveStrength(it)
            assertFalse(adapter.supportsControl(invalid))
            assertTrue(session.encode(invalid).isEmpty())
        }
        session.reset()
        assertTrue(session.encode(request).isEmpty())
        session.offer(report(0x0d, listOf(4, 0, 0, 0)))
        assertTrue(session.encode(request).isEmpty())
    }

    @Test fun requestAndFeatureSerializationAndContractsRemainNamespaced() {
        val adapter = AppleAirPodsAdapter()
        cases.forEach { case ->
            val encoded = ControlRequestTransport.encode(case.request)
            assertTrue(encoded.contains("apple.set_"))
            assertEquals(case.request, ControlRequestTransport.decode(encoded))
            assertFalse(StandardEarbudAdapter().supportsControl(case.request))
            val data = mutableListOf(0, 0, 0, 0).also { it[case.index] = case.old }
            adapter.receive(report(case.id, data))
        }
        val state = adapter.runtimeState().features.get<AppleSettingsFeatureState>()!!
        val snapshot = DeviceFeatureSnapshot().update(state)
        val encoded = FeatureStateTransport.encode(snapshot)
        assertTrue(encoded.contains("apple.settings"))
        assertEquals(snapshot, FeatureStateTransport.decode(encoded))
        assertTrue(adapter.featureStateContract.accepts(adapter, state))
        assertFalse(StandardEarbudAdapter().featureStateContract.accepts(StandardEarbudAdapter(), state))
    }

    @Test fun unknownIdsAndPartialFramesCannotOpenControlsAndResetDiscardsPartial() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(255, listOf(1, 1, 1, 1)))
        assertNull(adapter.runtimeState().features.get<AppleSettingsFeatureState>())
        val frame = report(0x01, listOf(2, 0, 0, 0))
        for (split in 1 until frame.size) {
            adapter.resetProtocolSession()
            adapter.receive(frame.copyOfRange(0, split))
            assertNull(adapter.runtimeState().features.get<AppleSettingsFeatureState>())
            adapter.receive(frame.copyOfRange(split, frame.size))
            assertEquals(AppleMicrophone.LEFT, adapter.runtimeState().features.get<AppleSettingsFeatureState>()?.microphone)
        }
        adapter.resetProtocolSession()
        adapter.receive(frame.take(8).toByteArray())
        adapter.resetProtocolSession()
        adapter.receive(frame.drop(8).toByteArray())
        assertNull(adapter.runtimeState().features.get<AppleSettingsFeatureState>())
    }

    @Test fun enumAndBooleanValuesRoundTripWithoutImplicitDefaultOrOptimism() {
        val adapter = AppleAirPodsAdapter()
        AppleMicrophone.entries.forEachIndexed { wire, microphone ->
            adapter.receive(report(0x01, listOf(wire, 128, 254, 255)))
            assertEquals(microphone, adapter.runtimeState().features.get<AppleSettingsFeatureState>()?.microphone)
            val request = AppleControlRequest.SetMicrophone(microphone)
            assertEquals(listOf(report(0x01, listOf(wire, 128, 254, 255)).toList()), adapter.executeControl(request).commands.map(ByteArray::toList))
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        }
        AppleIntervalLevel.entries.forEachIndexed { wire, level ->
            listOf(0x17, 0x18, 0x23).forEach { adapter.receive(report(it, listOf(wire, 0, 0, 0))) }
            val state = adapter.runtimeState().features.get<AppleSettingsFeatureState>()!!
            assertEquals(level, state.doublePressInterval)
            assertEquals(level, state.holdInterval)
            assertEquals(level, state.swipeInterval)
            listOf(AppleControlRequest.SetDoublePressInterval(level), AppleControlRequest.SetHoldInterval(level), AppleControlRequest.SetSwipeInterval(level)).forEach {
                assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
                assertEquals(wire, adapter.executeControl(it).commands.single()[7].toInt())
            }
        }
        listOf(true, false).forEach { enabled ->
            val wire = if (enabled) 1 else 2
            val requests = listOf(
                0x1b to AppleControlRequest.SetOneBudAnc(enabled),
                0x28 to AppleControlRequest.SetConversationAwareness(enabled),
                0x26 to AppleControlRequest.SetPersonalizedVolume(enabled),
                0x0a to AppleControlRequest.SetWearingDetection(enabled),
                0x25 to AppleControlRequest.SetVolumeSwipe(enabled),
                0x34 to AppleControlRequest.SetAllowOff(enabled),
                0x35 to AppleControlRequest.SetSleepPause(enabled),
            )
            requests.forEach { (id, request) ->
                adapter.receive(report(id, listOf(wire, 255, 254, 253)))
                assertEquals(listOf(report(id, listOf(wire, 255, 254, 253)).toList()), adapter.executeControl(request).commands.map(ByteArray::toList))
                assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            }
            val state = adapter.runtimeState().features.get<AppleSettingsFeatureState>()!!
            assertEquals(List(7) { enabled }, listOf(state.oneBudAnc, state.conversationAwareness, state.personalizedVolume, state.wearingDetection, state.volumeSwipe, state.allowOff, state.sleepPause))
            val snapshot = DeviceFeatureSnapshot().update(state)
            assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
        }
        listOf(2 to AppleCallMapping.FLIPPED, 3 to AppleCallMapping.DEFAULT).forEach { (wire, mapping) ->
            adapter.receive(report(0x24, listOf(255, wire, 128, 254)))
            assertEquals(mapping, adapter.runtimeState().features.get<AppleSettingsFeatureState>()?.callMapping)
            val request = AppleControlRequest.SetCallMapping(mapping)
            assertEquals(listOf(report(0x24, listOf(255, wire, 128, 254)).toList()), adapter.executeControl(request).commands.map(ByteArray::toList))
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        }
    }

    @Test fun forgedInvalidNumericStateAndMalformedInternalReportsNeverAuthorizeControls() {
        val forged = AppleSettingsFeatureState(adaptiveStrength = 255, chimeVolume = 0)
        assertFalse(forged.supports(AppleControlRequest.SetAdaptiveStrength(50), AppleListeningMode.ADAPTIVE))
        assertFalse(forged.supports(AppleControlRequest.SetChimeVolume(50), null))
        val empty = AppleSettingsFeatureState()
        listOf(emptyList(), listOf(1), listOf(1, 0, 0), listOf(1, 0, 0, 0, 0), listOf(1, 0, 0, 256), listOf(1, -1, 0, 0)).forEach {
            assertNull(empty.acceptReport(dev.hyperears.protocol.apple.AppleAapWireCodec.State.Control(0x28, it)))
        }
    }

    private class SessionFixture : AppleAirPodsAdapter() {
        fun newSession(): ProtocolSession = createProtocolSession()
    }

    @Test fun everySettingRequiresSessionEvidenceEvenWithAdoptedOrForgedPublicState() {
        cases.forEach { case ->
            val source = AppleAirPodsAdapter()
            source.receive(report(0x0d, listOf(4, 0, 0, 0)))
            source.receive(report(case.id, mutableListOf(99, 98, 97, 96).also { it[case.index] = case.old }))
            val fresh = AppleAirPodsAdapter()
            fresh.adoptRuntimeState(source.runtimeState())
            val before = fresh.runtimeState()
            val result = fresh.executeControl(case.request)
            assertFalse(result.accepted)
            assertTrue(result.commands.isEmpty())
            assertEquals(before, fresh.runtimeState())
            val session = SessionFixture().newSession()
            assertTrue(session.encode(case.request).isEmpty())
            session.offer(report(0x0d, listOf(4, 0, 0, 0)))
            assertTrue(session.encode(case.request).isEmpty())
            session.offer(report(case.id, mutableListOf(99, 98, 97, 96).also { it[case.index] = case.old }))
            assertFalse(session.encode(case.request).isEmpty())
            session.reset()
            assertTrue(session.encode(case.request).isEmpty())
        }
    }

    @Test fun chimeAndAdaptiveOutOfRangeRequestsCannotWriteDespiteValidEvidence() {
        val adapter = AppleAirPodsAdapter()
        val session = SessionFixture().newSession()
        val reports = report(0x0d, listOf(4, 0, 0, 0)) + report(0x2e, listOf(50, 0, 0, 0)) + report(0x1f, listOf(50, 0, 0, 0))
        adapter.receive(reports)
        session.offer(reports)
        val bad = listOf(-1, 101, 255, Int.MAX_VALUE).map { AppleControlRequest.SetAdaptiveStrength(it) } +
            listOf(-1, 0, 14, 101, 255, Int.MAX_VALUE).map { AppleControlRequest.SetChimeVolume(it) }
        bad.forEach {
            assertFalse(adapter.supportsControl(it))
            assertFalse(adapter.executeControl(it).accepted)
            assertTrue(session.encode(it).isEmpty())
        }
        listOf(0, 100).forEach {
            assertTrue(adapter.executeControl(AppleControlRequest.SetAdaptiveStrength(it)).accepted)
        }
        listOf(15, 100).forEach {
            assertTrue(adapter.executeControl(AppleControlRequest.SetChimeVolume(it)).accepted)
        }
    }

    @Test fun invalidAndUnknownControlsNeverHandshakeButValidSettingDoesExactlyOnce() {
        val adapter = AppleAirPodsAdapter()
        listOf(report(0xff, listOf(1, 0, 0, 0)), report(0x28, listOf(0, 0, 0, 0)), report(0x24, listOf(2, 0, 0, 0))).forEach {
            assertNull(adapter.receive(it).handshake)
        }
        assertEquals(HandshakeResult.Ready, adapter.receive(report(0x28, listOf(1, 0, 0, 0))).handshake)
        assertNull(adapter.receive(report(0x01, listOf(1, 0, 0, 0))).handshake)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.OFF)))
        assertEquals(AppleAirPodsAdapter().effectiveBatterySource(), adapter.effectiveBatterySource())
        adapter.resetProtocolSession()
        assertEquals(HandshakeResult.Ready, adapter.receive(report(0x01, listOf(1, 0, 0, 0))).handshake)
    }

    private fun report(id: Int, values: List<Int>) =
        byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte()) + values.map(Int::toByte).toByteArray()
}
