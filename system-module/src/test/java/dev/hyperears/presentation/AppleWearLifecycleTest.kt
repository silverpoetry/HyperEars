package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test
import dev.hyperears.integration.*

class AppleWearLifecycleTest {
    private class Host : AppleWearCoordinator.Host {
        var starts = 0; var stops = 0; var pauses = 0; var plays = 0
        var callback: (() -> Unit)? = null
        var sample = AppleWearCoordinator.Media(AppleWearPolicy.Player("token", 42, 7), true)
        override fun start(changed: () -> Unit): Boolean { starts++; callback = changed; return true }
        override fun stop() { stops++ }
        override fun media(address: String) = sample
        override fun execute(player: AppleWearPolicy.Player, action: AppleWearPolicy.Action): Boolean {
            if (action == AppleWearPolicy.Action.PAUSE) pauses++ else plays++
            return true
        }
    }
    @Test fun eitherAppleControllerStopsPhoneWearActionsAndInvalidatesLateCallbacks() {
        for (owner in listOf(ControlAppCatalog.andPods, ControlAppCatalog.librePods)) {
            val host = Host()
            val runtime = AppleWearRuntime(AppleWearCoordinator(host, { 0 }, {}))
            val state = EarbudState(address = "address", lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
                protocolHandshake = ProtocolHandshakeState.CONFIRMED))
                .withFeature(AppleSettingsFeatureState(wearingDetection = true))
                .withFeature(AppleWearFeatureState(AppleWearPosition.IN_EAR, AppleWearPosition.IN_EAR))
            runtime.update(state, "session", NativeAdmission.HYPEREARS, true)
            val late = host.callback!!
            runtime.update(state.copy(lifecycle = state.lifecycle.copy(
                controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = owner)), "session", NativeAdmission.HYPEREARS, true)
            assertEquals(owner.packageName, 1, host.stops)
            late()
            assertEquals(0, host.pauses); assertEquals(0, host.plays)
        }
    }

    @Test fun removalTearsDownAndLateCallbackCannotResume() {
        val host = Host()
        val deadlines = mutableListOf<Long?>()
        val runtime = AppleWearCoordinator(host, { 0 }, deadlines::add)
        runtime.update("address", "session", 2)
        runtime.update("address", "session", 1)
        assertEquals(1, host.pauses)
        host.sample = host.sample.copy(playing = false)
        host.callback!!()
        runtime.remove("address")
        host.callback!!()
        runtime.update("address", "session", 2)
        assertEquals(0, host.plays)
        assertEquals(2, host.starts)
        assertEquals(1, host.stops)
    }
    @Test fun sessionReplacementAndClearDropPendingPause() {
        val host = Host()
        val runtime = AppleWearCoordinator(host, { 0 }, {})
        runtime.update("address", "session", 2); runtime.update("address", "session", 0)
        host.sample = host.sample.copy(playing = false)
        host.callback!!()
        runtime.update("address", "replacement", 2)
        assertEquals(0, host.plays)
        runtime.clear()
        assertEquals(1, host.stops)
    }

    @Test fun runtimeRequiresCurrentAdmissionTransportSettingsAndModuleOwnership() {
        val host = Host()
        val runtime = AppleWearRuntime(AppleWearCoordinator(host, { 0 }, {}))
        val state = EarbudState(address = "address", lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
            .withFeature(AppleSettingsFeatureState(wearingDetection = true))
            .withFeature(AppleWearFeatureState(AppleWearPosition.IN_EAR, AppleWearPosition.IN_EAR))
        val disabled = listOf(state.copy(lifecycle = state.lifecycle.copy(privateTransport = PrivateTransportState.RECOVERING)),
            state.copy(lifecycle = state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppSpec("fixture.controller", "Controller"))),
            state.copy(lifecycle = state.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)),
            state.withFeature(AppleSettingsFeatureState(wearingDetection = false)),
            state.withFeature(AppleSettingsFeatureState()),
            state.copy(lifecycle = state.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED)))
        disabled.forEach { runtime.update(it, "session", NativeAdmission.HYPEREARS, true) }
        runtime.update(state, "session", NativeAdmission.SYSTEM, true)
        runtime.update(state, "session", NativeAdmission.UNKNOWN, true)
        runtime.update(state, "session", NativeAdmission.HYPEREARS, false)
        assertEquals(0, host.starts)
        runtime.update(state, "session", NativeAdmission.HYPEREARS, true)
        assertEquals(1, host.starts)
        runtime.update(disabled.first(), "session", NativeAdmission.HYPEREARS, true)
        assertEquals(1, host.stops)
    }
}
