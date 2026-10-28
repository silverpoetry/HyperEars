package dev.hyperears.hook

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class RoseCoralCardPolicyTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(RoseCoralSettingsFeatureState(gameMode = false, bassLevel = 1, promptVolume = 3))
    @Test fun settingsBeforeNoiseAndTypedBounds() {
        val state = state()
        assertTrue(RoseCoralCardPolicy.available(state, address))
        assertEquals(RoseCoralControlRequest.SetGameMode(true), RoseCoralCardPolicy.request(state, address, RoseCoralSetting.GAME, 1))
        assertNull(RoseCoralCardPolicy.request(state, address, RoseCoralSetting.GAME, 0))
        assertNull(RoseCoralCardPolicy.request(state, address, RoseCoralSetting.BASS, 3))
        assertNull(RoseCoralCardPolicy.request(state, address, RoseCoralSetting.PROMPT, 0))
        assertNull(RoseCoralCardPolicy.request(state, address, RoseCoralSetting.MULTIPOINT, 1))
        assertNull(RoseCoralCardPolicy.noiseRequest(state, address, NoiseMode.ANC))
    }
    @Test fun freshLifecycleAndAddressGateEveryRequest() {
        val state = state()
        listOf(state.copy(address = "other"),
            state.copy(lifecycle = state.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED)),
            state.copy(lifecycle = state.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)),
            state.copy(lifecycle = state.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)),
            state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppSpec("test.controller", "Test")))
        ).forEach { assertNull(RoseCoralCardPolicy.request(it, address, RoseCoralSetting.GAME, 1)) }
    }
}
