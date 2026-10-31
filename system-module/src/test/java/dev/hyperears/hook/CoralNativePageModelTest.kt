package dev.hyperears.hook

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class CoralNativePageModelTest {
    private fun state() = EarbudState(adapter = RoseCoralAdapter().snapshot(), address = "00:11:22:33:44:55",
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.CONNECTED, protocolHandshake = ProtocolHandshakeState.CONFIRMED))

    @Test fun admissionKeepsDedicatedPageDuringHandoffAndReconnect() {
        val state = state()
        assertTrue(CoralNativePageModel.eligible(state))
        assertFalse(CoralNativePageModel.eligible(state.copy(adapter = StandardEarbudAdapter().snapshot())))
        assertTrue(CoralNativePageModel.eligible(state.copy(lifecycle = state.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING))))
        assertTrue(CoralNativePageModel.eligible(state.copy(lifecycle = state.lifecycle.copy(
            controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppSpec("cn.ikaile.ruoshui.client", "RoseLink")))))
    }

    @Test fun unknownBatteryStaysUnknownAndChargingUsesNativeFlag() {
        assertEquals("255,255,255", CoralNativePageModel.battery(state()))
        val state = state().withFeature(BatteryFeatureState(EarbudBattery(
            left = BatteryReading(0, false), right = BatteryReading(70, true), case = BatteryReading(100, false))))
        assertEquals("0,198,100", CoralNativePageModel.battery(state))
    }

    @Test fun actualHandoffLifecycleKeepsPageButCannotSendCommands() {
        val state = state().copy(lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED,
            privateTransport = PrivateTransportState.NOT_REQUIRED,
            protocolHandshake = ProtocolHandshakeState.NOT_REQUIRED,
            controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = ControlAppSpec("cn.ikaile.ruoshui.client", "RoseLink")))
        assertTrue(CoralNativePageModel.eligible(state))
        assertTrue(NativeHeadsetPageModel.eligible(state))
        assertFalse(RoseCoralCardPolicy.ready(state, state.address!!))
        assertNull(NativeHeadsetPageModel.noiseRequest(state, state.address!!, NoiseMode.ANC))
    }

    @Test fun unsupportedFeaturesNeverBecomeNativeCapabilities() {
        assertEquals("00:11:22:33:44:55,000000000000000000000000", CoralNativePageModel.support("00:11:22:33:44:55", state()))
        assertNull(CoralNativePageModel.anc(state()))
    }
}
