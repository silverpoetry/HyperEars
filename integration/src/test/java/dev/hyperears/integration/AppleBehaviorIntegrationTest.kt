package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleBehaviorIntegrationTest {
    private fun frame(vararg values: Int) = values.map(Int::toByte).toByteArray()
    @Test fun telemetryAndConfigurationPublishOnlyTheirOwnNamespacedEvidenceAndReset() {
        val adapter = AppleAirPodsAdapter()
        val frames = listOf(
            "apple.wear" to frame(4,0,4,0,6,0,0,3),
            "apple.conversation" to frame(4,0,4,0,0x4b,0,2,0,1,2),
            "apple.stem" to frame(4,0,4,0,0x19,0,8,1),
            "apple.gesture_settings" to frame(4,0,4,0,9,0,0x16,1,5,254,255),
            "apple.equalizer" to frame(4,0,4,0,0x63,0,5,0,1,2,54,50,50),
        )
        frames.forEach { (id, bytes) ->
            assertNull(adapter.runtimeState().features.get(id))
            adapter.receive(bytes)
            assertNotNull(id, adapter.runtimeState().features.get(id))
        }
        val snapshot = adapter.runtimeState().features
        assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
        adapter.resetProtocolSession()
        frames.forEach { (id, _) -> assertNull(adapter.runtimeState().features.get(id)) }
    }

    @Test fun longPressUsesRightThenLeftAndPreservesOtherBudAndUnownedBytes() {
        val adapter = AppleAirPodsAdapter()
        val request = AppleControlRequest.SetLongPress(AppleBud.LEFT, AppleLongPressAction.NOISE_CONTROL)
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(frame(4,0,4,0,9,0,0x16,1,5,254,255))
        val before = adapter.runtimeState()
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertEquals(listOf(frame(4,0,4,0,9,0,0x16,1,1,254,255).toList()), result.commands.map(ByteArray::toList))
        assertEquals(before, adapter.runtimeState())
        assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(request).confirmation)
        assertFalse(result.readback.isEmpty())
        adapter.resetProtocolSession()
        assertFalse(adapter.executeControl(request).accepted)
    }

    @Test fun cycleNeedsOwnReportAndKnownListeningSupportAndPreservesOpaqueBytes() {
        val adapter = AppleAirPodsAdapter()
        val request = AppleControlRequest.SetListeningCycle(6)
        adapter.receive(frame(4,0,4,0,9,0,0x1a,6,253,254,255))
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(frame(4,0,4,0,9,0,0x0d,2,0,0,0))
        assertTrue(adapter.executeControl(request).accepted)
        assertEquals(listOf(frame(4,0,4,0,9,0,0x1a,6,253,254,255).toList()), adapter.executeControl(request).commands.map(ByteArray::toList))
        listOf(-1,0,8,14,16,255).forEach { assertFalse(adapter.executeControl(AppleControlRequest.SetListeningCycle(it)).accepted) }
        adapter.receive(frame(4,0,4,0,9,0,0x0d,4,0,0,0))
        assertTrue(adapter.executeControl(AppleControlRequest.SetListeningCycle(14)).accepted)
    }

    @Test fun equalizerWritesNeverReplaceActualReadbackWithRequestedValue() {
        val adapter = AppleAirPodsAdapter()
        val request = AppleControlRequest.SetEqualizer(2,55,50,50)
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(frame(4,0,4,0,0x63,0,5,0,1,2,54,50,50))
        val before = adapter.runtimeState()
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertEquals(listOf(frame(4,0,4,0,0x63,0,5,0,1,2,55,50,50).toList()), result.commands.map(ByteArray::toList))
        assertEquals(before, adapter.runtimeState())
        adapter.receive(frame(4,0,4,0,0x63,0,5,0,1,2,53,50,50))
        assertEquals(53, adapter.runtimeState().features.get<AppleEqualizerFeatureState>()!!.low)
        adapter.receive(frame(4,0,4,0,0x63,0,5,0,1,2,55,50,50))
        assertEquals(55, adapter.runtimeState().features.get<AppleEqualizerFeatureState>()!!.low)
        listOf(request.copy(state=0), request.copy(low=-1), request.copy(mid=101), request.copy(high=255)).forEach {
            assertFalse(adapter.executeControl(it).accepted)
        }
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        adapter.resetProtocolSession()
        assertFalse(adapter.executeControl(request).accepted)
    }

    @Test fun conversationUnknownsNeverAcquireActivityAndStemDuplicatesRemainEvents() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(frame(4,0,4,0,0x4b,0,2,0,1,3))
        val supplementary = adapter.runtimeState().features.get<AppleConversationFeatureState>()!!
        assertEquals(AppleConversationActivity.UNKNOWN, supplementary.primaryActivity)
        assertEquals(AppleConversationActivity.START, supplementary.supplementaryActivity)
        adapter.receive(frame(4,0,4,0,0x4b,0,2,0,2,1))
        assertEquals(AppleConversationActivity.UNKNOWN, adapter.runtimeState().features.get<AppleConversationFeatureState>()!!.supplementaryActivity)
        adapter.receive(frame(4,0,4,0,0x19,0,8,1))
        val first = adapter.runtimeState().features.get<AppleStemFeatureState>()!!
        adapter.receive(frame(4,0,4,0,0x19,0,8,1))
        assertEquals(first.sequence + 1, adapter.runtimeState().features.get<AppleStemFeatureState>()!!.sequence)
    }

    @Test fun newRequestsRoundTripAndSessionRejectsWritesBeforeEvidenceAndAfterReset() {
        val requests = listOf(
            AppleControlRequest.SetLongPress(AppleBud.RIGHT, AppleLongPressAction.DIGITAL_ASSISTANT),
            AppleControlRequest.SetListeningCycle(6),
            AppleControlRequest.SetEqualizer(2,55,50,50),
        )
        val session = object : AppleAirPodsAdapter() { fun session() = createProtocolSession() }.session()
        requests.forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
            assertTrue(session.encode(it).isEmpty())
            assertFalse(StandardEarbudAdapter().supportsControl(it))
        }
        session.offer(frame(4,0,4,0,9,0,0x16,1,5,254,255))
        session.offer(frame(4,0,4,0,9,0,0x1a,6,253,254,255))
        session.offer(frame(4,0,4,0,9,0,0x0d,2,0,0,0))
        session.offer(frame(4,0,4,0,0x63,0,5,0,1,2,54,50,50))
        requests.forEach { assertFalse(session.encode(it).isEmpty()) }
        session.reset()
        requests.forEach { assertTrue(session.encode(it).isEmpty()) }
    }

    @Test fun advancedReportsExposeSettingsWithoutInventingListeningControls() {
        val reports = listOf(
            frame(4,0,4,0,6,0,0,3),
            frame(4,0,4,0,0x4b,0,2,0,1,2),
            frame(4,0,4,0,9,0,0x16,1,5,254,255),
            frame(4,0,4,0,0x63,0,5,0,1,2,54,50,50),
        )
        reports.forEach { report ->
            val adapter = AppleAirPodsAdapter()
            assertNull(adapter.miLinkCardPresentationId)
            adapter.receive(report)
            assertEquals(AppleMiLinkPresentationIds.SETTINGS, adapter.miLinkCardPresentationId)
            assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ANC)))
            adapter.receive(frame(4,0,4,0,9,0,0x0d,2,0,0,0))
            assertEquals(AppleMiLinkPresentationIds.LISTENING, adapter.miLinkCardPresentationId)
            adapter.resetProtocolSession()
            assertNull(adapter.miLinkCardPresentationId)
        }
        val stemOnly = AppleAirPodsAdapter()
        stemOnly.receive(frame(4,0,4,0,0x19,0,8,1))
        assertNull(stemOnly.miLinkCardPresentationId)
        val interceptionOnly = AppleAirPodsAdapter()
        interceptionOnly.receive(frame(4,0,4,0,9,0,0x39,1,0,0,0))
        assertNotNull(interceptionOnly.runtimeState().features.get<AppleGestureSettingsFeatureState>())
        assertNull(interceptionOnly.miLinkCardPresentationId)
    }
}
