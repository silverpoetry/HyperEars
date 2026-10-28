package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleListeningModeTest {
    @Test
    fun adaptiveReportRetainsVendorListeningState() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 4, 0, 0, 0))

        assertNotNull(adapter.runtimeState().features.get("apple.listening_mode"))
        assertEquals(AppleListeningMode.ADAPTIVE, adapter.listening()?.mode)
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
    }

    @Test
    fun batteryAndInvalidReportsDoNotAdmitListeningWrites() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 80, 2, 1))
        adapter.receive(report(0) + report(5) + report(255))
        assertNull(adapter.listening())
        assertNull(adapter.miLinkCardPresentationId)
        AppleListeningMode.entries.forEach {
            assertFalse(adapter.executeControl(AppleControlRequest.SetListeningMode(it)).accepted)
        }
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun baselineReportAdmitsThreeModesButNotAdaptive() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(2))
        assertEquals(AppleListeningMode.entries.toSet() - AppleListeningMode.ADAPTIVE, adapter.listening()?.supportedModes)
        assertEquals(AppleMiLinkPresentationIds.LISTENING, adapter.miLinkCardPresentationId)
        AppleListeningMode.entries.forEach { mode ->
            assertEquals(mode != AppleListeningMode.ADAPTIVE, adapter.supportsControl(AppleControlRequest.SetListeningMode(mode)))
        }
        assertFalse(adapter.executeControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)).accepted)
        assertFalse(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)).accepted)
        assertFalse(adapter.supportsControl(FloS2ControlRequest.SetSoundEffect(FloS2SoundEffect.STANDARD)))
    }

    @Test
    fun observedAdaptiveSupportSurvivesModeChangesButNotReset() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(4))
        adapter.receive(report(1))
        assertEquals(AppleListeningMode.entries.toSet(), adapter.listening()?.supportedModes)
        assertTrue(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))

        adapter.resetProtocolSession()
        assertNull(adapter.listening())
        assertNull(adapter.miLinkCardPresentationId)
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
        adapter.receive(report(3))
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
    }

    @Test
    fun writesRequestReadbackWithoutPublishingRequestedMode() {
        AppleListeningMode.entries.forEachIndexed { index, mode ->
            val adapter = AppleAirPodsAdapter()
            adapter.receive(report(4))
            adapter.receive(report(1))
            val before = adapter.runtimeState()
            val request = AppleControlRequest.SetListeningMode(mode)
            val result = adapter.executeControl(request)
            assertTrue(result.accepted)
            assertFalse(result.stateChanged)
            assertEquals(listOf(report(index + 1).toList()), result.commands.map(ByteArray::toList))
            assertEquals(listOf(dev.hyperears.protocol.apple.AppleAapWireCodec.requestNotifications.toList()), result.readback.map(ByteArray::toList))
            assertEquals(before, adapter.runtimeState())
            assertEquals(ControlConfirmationPolicy.DEVICE_REPORT, adapter.controlPolicy(request).confirmation)
            assertNull(adapter.controlPolicy(request).stateAfterWrite)
            adapter.receive(report(index + 1))
            assertEquals(mode, adapter.listening()?.mode)
        }
    }

    @Test
    fun standardCompatibilityControlAlsoWaitsForReport() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(4))
        val before = adapter.runtimeState()
        val result = adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF))
        assertTrue(result.accepted)
        assertFalse(result.stateChanged)
        assertTrue(result.readback.isNotEmpty())
        assertEquals(before, adapter.runtimeState())
        adapter.receive(report(1))
        assertEquals(AppleListeningMode.OFF, adapter.listening()?.mode)
    }

    @Test
    fun allModesRoundTripWithStableNamespacedContracts() {
        AppleListeningMode.entries.forEach { mode ->
            val request = AppleControlRequest.SetListeningMode(mode)
            val encoded = ControlRequestTransport.encode(request)
            assertTrue(encoded.contains("apple.set_listening_mode"))
            assertEquals(request, ControlRequestTransport.decode(encoded))
            val features = DeviceFeatureSnapshot().update(AppleListeningModeFeatureState(mode, AppleListeningMode.entries.toSet()))
            val state = FeatureStateTransport.encode(features)
            assertTrue(state.contains("apple.listening_mode"))
            assertEquals(features, FeatureStateTransport.decode(state))
            assertFalse(StandardEarbudAdapter().supportsControl(request))
        }
    }

    @Test
    fun invalidReportsLeaveConfirmedStateAndChoicesUnchanged() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(2))
        val before = adapter.runtimeState()
        adapter.receive(report(0) + report(5) + report(255))
        assertEquals(before, adapter.runtimeState())
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
    }

    @Test
    fun fragmentedReportsOnlyPublishCompleteStateAndResetDiscardsPartialFrame() {
        for (split in 1 until report(4).size) {
            val adapter = AppleAirPodsAdapter()
            adapter.receive(report(4).copyOfRange(0, split))
            assertNull(adapter.listening())
            adapter.receive(report(4).copyOfRange(split, report(4).size))
            assertEquals(AppleListeningMode.ADAPTIVE, adapter.listening()?.mode)
        }
        val adapter = AppleAirPodsAdapter()
        adapter.receive(report(4).take(8).toByteArray())
        adapter.resetProtocolSession()
        adapter.receive(report(4).drop(8).toByteArray())
        assertNull(adapter.listening())
        adapter.receive(report(2))
        assertEquals(AppleListeningMode.ANC, adapter.listening()?.mode)
    }

    private fun AppleAirPodsAdapter.listening() = runtimeState().features.get<AppleListeningModeFeatureState>()

    private fun report(value: Int) = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, value.toByte(), 0, 0, 0)
}
