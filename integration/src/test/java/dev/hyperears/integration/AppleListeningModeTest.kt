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

    @Test
    fun deviceModelAndLiveModeEnableAdaptiveInEitherOrder() {
        val models = listOf("A3056", "A3055", "A3057", "A3531", "A3532", "A3533",
            "A3439", "A3440", "A3441", "A2931", "A2699", "A2698",
            "A3047", "A3048", "A3049", "A3063", "A3064", "A3065")
        models.forEach { model ->
            (1..3).forEach { mode ->
                listOf(true, false).forEach { identityFirst ->
                    val adapter = AppleAirPodsAdapter()
                    adapter.receive(if (identityFirst) deviceInfo(model) else report(mode))
                    assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
                    adapter.receive(if (identityFirst) report(mode) else deviceInfo(model))
                    assertTrue("$model / $mode / $identityFirst", adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
                    val before = adapter.runtimeState()
                    val result = adapter.executeControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE))
                    assertTrue(result.accepted)
                    assertEquals(listOf(report(4).toList()), result.commands.map(ByteArray::toList))
                    assertEquals(before, adapter.runtimeState())
                }
            }
        }
    }

    @Test
    fun deviceInfoAloneNeverAdmitsListeningAndResetClearsBothEvidenceKinds() {
        val adapter = AppleAirPodsAdapter()
        adapter.receive(deviceInfo("A3441"))
        assertNull(adapter.listening())
        assertNull(adapter.miLinkCardPresentationId)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        adapter.resetProtocolSession()
        adapter.receive(report(2))
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
        adapter.resetProtocolSession()
        adapter.receive(deviceInfo("A3441"))
        assertNull(adapter.listening())
    }

    @Test
    fun nameSpoofsAndUnknownModelsNeedObservedAdaptiveAndIdentityCanRevokeInference() {
        val adapter = AppleAirPodsAdapter()
        listOf("", "unknown", "a3441", " A3441", "A3053", "A2084", "A1523").forEach {
            adapter.receive(deviceInfo(it, "A3441"))
            adapter.receive(report(2))
            assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
        }
        adapter.receive(deviceInfo("A3441"))
        assertTrue(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
        adapter.receive(deviceInfo("unknown"))
        assertFalse(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
        adapter.receive(report(4))
        adapter.receive(deviceInfo("unknown"))
        adapter.receive(report(1))
        assertTrue(adapter.supportsControl(AppleControlRequest.SetListeningMode(AppleListeningMode.ADAPTIVE)))
    }

    private fun deviceInfo(model: String, name: String = "Fixture"): ByteArray {
        val fields = listOf(name, model, "", "", "7A305", "", "", "", "", "", "")
        val body = fields.flatMap { (it.toByteArray(Charsets.UTF_8) + byteArrayOf(0)).toList() }.toByteArray() + ByteArray(34) + byteArrayOf(0, 0)
        val length = body.size + 2
        return byteArrayOf(4, 0, 4, 0, 0x1d, 0, 2, length.toByte(), (length shr 8).toByte(), 4, 0) + body
    }

    private fun AppleAirPodsAdapter.listening() = runtimeState().features.get<AppleListeningModeFeatureState>()

    private fun report(value: Int) = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, value.toByte(), 0, 0, 0)
}
