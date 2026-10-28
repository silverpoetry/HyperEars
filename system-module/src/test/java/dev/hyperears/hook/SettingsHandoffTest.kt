package dev.hyperears.hook

import android.os.Binder
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SettingsHandoffTest {
    private val address = "00:11:22:33:44:55"
    private val app = ControlAppCatalog.roseLink
    @Test fun trustedExternalAckLaunchesAfterPrivateReleaseAndNormalControlsStayBlocked() {
        val f = Fixture(); assertTrue(f.coordinator().start())
        assertEquals(0, f.launches)
        f.releaseAck()
        assertNull(f.cache.record(address))
        assertNotNull(f.cache.trustedRecord(address))
        assertEquals(1, f.launches)
        assertNull(f.observer); assertNull(f.timeout)
        assertTrue(f.gate.canReturn(f.record()!!))
        assertFalse(f.gate.send(f.record()!!, StandardControlRequest.SetNoiseMode(NoiseMode.ANC)) { error("ordinary control leaked") })
        assertFalse(f.gate.send(f.record()!!, StandardControlRequest.EndVendorHandoff("another")) { error("other lease leaked") })
        assertTrue(f.gate.send(f.record()!!, StandardControlRequest.EndVendorHandoff("lease")) { f.sent += StandardControlRequest.EndVendorHandoff("lease") })
        assertFalse(f.gate.canReturn(f.record()!!))
    }
    @Test fun timeoutAndLaunchFailureEndOnlyTheLocalLease() {
        val timeout = Fixture(); assertTrue(timeout.coordinator().start()); timeout.timeout!!()
        assertEquals(StandardControlRequest.EndVendorHandoff("lease"), timeout.sent.last())
        assertNull(timeout.observer)
        val failure = Fixture(); failure.launchResult = false
        assertTrue(failure.coordinator().start()); failure.releaseAck()
        assertEquals(StandardControlRequest.EndVendorHandoff("lease"), failure.sent.last())
        assertFalse(failure.gate.canReturn(failure.record()!!))
    }
    @Test fun pageTeardownCancelsBeforeLosingItsTrustedSendContext() {
        val f = Fixture(); assertTrue(f.coordinator().start())
        // Settings onDestroyView dispatches cancellation before releasing page/token state.
        f.observer!!(EarbudState())
        f.pageAddress = "other"
        assertEquals(StandardControlRequest.EndVendorHandoff("lease"), f.sent.last())
        assertNull(f.observer); assertNull(f.timeout); assertEquals(0, f.launches)
        assertNull(f.record())
    }
    @Test fun changedHostAddressOrNativeOwnerCannotLaunchOrReturnAcrossScope() {
        val changed = Fixture(); assertTrue(changed.coordinator().start()); changed.pageAddress = "other"
        changed.releaseAck(); assertEquals(0, changed.launches)
        assertEquals(1, changed.sent.size)
        val native = Fixture(); assertTrue(native.coordinator().start())
        native.publish(native.state.copy(revision = 2), NativeAdmission.SYSTEM)
        assertNull(native.record()); assertEquals(0, native.launches)
        val replaced = Fixture(); assertTrue(replaced.coordinator().start())
        replaced.token = "new-session"; replaced.releaseAck()
        assertEquals(0, replaced.launches)
        assertFalse(replaced.gate.canReturn(replaced.record()!!))
    }
    @Test fun appExitRestoresEligibilityForAnotherExplicitHandoff() {
        val f = Fixture(); assertTrue(f.coordinator().start()); f.releaseAck()
        f.publish(f.state.copy(revision = 3, lifecycle = DeviceLifecycle(
            SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED)))
        assertFalse(f.gate.canReturn(f.record()!!))
        assertTrue(f.gate.send(f.record()!!, StandardControlRequest.BeginVendorHandoff(app.packageName, "new-lease")) {})
    }
    @Test fun beginRequiresDeclaredAppAndConfirmedPrivateModuleState() {
        val f = Fixture()
        assertFalse(f.gate.send(f.record()!!, StandardControlRequest.BeginVendorHandoff("other", "lease")) {})
        f.publish(f.state.copy(revision = 2, lifecycle = f.state.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)))
        assertFalse(f.gate.send(f.record()!!, StandardControlRequest.BeginVendorHandoff(app.packageName, "lease")) {})
        assertFalse(f.gate.send(f.record()!!, StandardControlRequest.EndVendorHandoff("lease")) {})
    }
    private inner class Fixture {
        val cache = SettingsHeadsetState(); val gate = SettingsControlGate(); val producer = Binder()
        var pageAddress = address; var token = "session"
        var state = EarbudState(address = address, revision = 1, adapter = RoseCoralAdapter().snapshot(),
            lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
        var observer: ((EarbudState) -> Unit)? = null
        var timeout: (() -> Unit)? = null
        var launches = 0; var launchResult = true
        val sent = mutableListOf<ControlRequest>()
        init { publish(state) }
        fun record() = settingsPageRecord(address, address, { pageAddress }, { cache.trustedRecord(address) })
        fun publish(value: EarbudState, admission: NativeAdmission = NativeAdmission.HYPEREARS) {
            state = value
            val intent = NativePresentationContract.attach(ModuleContract.stateChanged(state, token, ModuleContract.SETTINGS_PACKAGE),
                NativePresentationMetadata(HeadsetConnection("connection", 1), admission), producer)
            assertTrue(cache.accept(intent))
            gate.observe(address, cache.trustedRecord(address))
            observer?.invoke(record()?.state ?: EarbudState())
        }
        fun releaseAck() = publish(state.copy(revision = state.revision + 1, lifecycle = DeviceLifecycle(
            SystemProfileState.CONNECTED, PrivateTransportState.NOT_REQUIRED, ProtocolHandshakeState.NOT_REQUIRED,
            ControlOwnership.EXTERNAL_APP, app)))
        fun coordinator() = VendorHandoffCoordinator(address, app, { record()?.state ?: EarbudState() }, { record()?.token },
            observe = { _, callback -> observer = callback; { observer = null } },
            schedule = { callback -> timeout = callback; { timeout = null } },
            send = { _, request -> check(gate.send(requireNotNull(record()), request) { sent += request }) },
            launch = { launches++; launchResult }, leaseId = "lease")
    }
}
