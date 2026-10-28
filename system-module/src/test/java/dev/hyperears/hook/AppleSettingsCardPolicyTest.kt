package dev.hyperears.hook

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class AppleSettingsCardPolicyTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED,
        privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(AppleSettingsFeatureState(
        microphone = AppleMicrophone.AUTOMATIC, oneBudAnc = false, conversationAwareness = false,
        personalizedVolume = false, wearingDetection = false, volumeSwipe = false,
        doublePressInterval = AppleIntervalLevel.LEVEL_0, holdInterval = AppleIntervalLevel.LEVEL_0,
        swipeInterval = AppleIntervalLevel.LEVEL_0, adaptiveStrength = 40,
        callMapping = AppleCallMapping.DEFAULT, allowOff = false, chimeVolume = 50, sleepPause = false,
    )).withFeature(AppleListeningModeFeatureState(AppleListeningMode.ADAPTIVE, AppleListeningMode.entries.toSet()))

    @Test fun allFourteenControlsUseTypedRequestsAndSuppressNoOps() {
        val current = state()
        assertEquals(14, AppleSetting.entries.size)
        AppleSetting.entries.forEach { setting ->
            val value = requireNotNull(setting.value(current.features.get<AppleSettingsFeatureState>()!!))
            assertNull(AppleSettingsCardPolicy.request(current, address, setting, value))
            val changed = if (value == setting.range.first) setting.range.last else setting.range.first
            assertNotNull(setting.name, AppleSettingsCardPolicy.request(current, address, setting, changed))
            assertNull(AppleSettingsCardPolicy.request(current, address, setting, setting.range.first - 1))
            assertNull(AppleSettingsCardPolicy.request(current, address, setting, setting.range.last + 1))
        }
    }

    @Test fun everyControlMapsToItsExactTypedRequest() {
        val expected = listOf(
            AppleControlRequest.SetMicrophone(AppleMicrophone.LEFT),
            AppleControlRequest.SetOneBudAnc(true),
            AppleControlRequest.SetConversationAwareness(true),
            AppleControlRequest.SetPersonalizedVolume(true),
            AppleControlRequest.SetWearingDetection(true),
            AppleControlRequest.SetVolumeSwipe(true),
            AppleControlRequest.SetDoublePressInterval(AppleIntervalLevel.LEVEL_2),
            AppleControlRequest.SetHoldInterval(AppleIntervalLevel.LEVEL_2),
            AppleControlRequest.SetSwipeInterval(AppleIntervalLevel.LEVEL_2),
            AppleControlRequest.SetAdaptiveStrength(100),
            AppleControlRequest.SetCallMapping(AppleCallMapping.FLIPPED),
            AppleControlRequest.SetAllowOff(true),
            AppleControlRequest.SetChimeVolume(100),
            AppleControlRequest.SetSleepPause(true),
        )
        assertEquals(expected, AppleSetting.entries.map {
            AppleSettingsCardPolicy.request(state(), address, it, it.range.last)
        })
    }

    @Test fun freshAddressLifecycleAndFeatureEvidenceGateEveryControl() {
        val state = state()
        val blocked = listOf(
            state.copy(address = "00:11:22:33:44:66"),
            state.copy(lifecycle = state.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED)),
            state.copy(lifecycle = state.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)),
            state.copy(lifecycle = state.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)),
            state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppSpec("test.controller", "Test"))),
            state.copy(features = DeviceFeatureSnapshot()),
        )
        blocked.forEach { candidate ->
            assertFalse(AppleSettingsCardPolicy.available(candidate, address))
            AppleSetting.entries.forEach { assertNull(AppleSettingsCardPolicy.request(candidate, address, it, it.range.last)) }
        }
    }

    @Test fun adaptiveRequiresActualAdaptiveAndChimeHasExactBounds() {
        val state = state().withFeature(AppleListeningModeFeatureState(AppleListeningMode.ANC, AppleListeningMode.entries.toSet()))
        assertNull(AppleSettingsCardPolicy.request(state, address, AppleSetting.ADAPTIVE_STRENGTH, 60))
        assertEquals(15..100, AppleSetting.CHIME_VOLUME.range)
        assertEquals(0..100, AppleSetting.ADAPTIVE_STRENGTH.range)
    }
}
