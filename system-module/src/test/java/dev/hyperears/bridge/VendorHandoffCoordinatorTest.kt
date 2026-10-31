package dev.hyperears.bridge

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class VendorHandoffCoordinatorTest {
    private val address = "02:00:00:00:00:01"
    private val app = ControlAppSpec("test.vendor", "Vendor")
    private var token: String? = "session"
    private var state = EarbudState(
        address = address, revision = 10,
        adapter = StandardEarbudAdapter().snapshot().copy(controlApps = listOf(app)),
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
    )
    private var observer: ((EarbudState) -> Unit)? = null
    private var timeout: (() -> Unit)? = null
    private val sent = mutableListOf<ControlRequest>()
    private var launches = 0
    private var launchResult = true
    private var onSend: (() -> Unit)? = null
    private fun coordinator() = VendorHandoffCoordinator(
        address, app, { state }, { token },
        observe = { _, callback -> observer = callback; { observer = null } },
        schedule = { callback -> timeout = callback; { timeout = null } },
        send = { _, request -> sent += request; onSend?.invoke() },
        launch = { launches++; launchResult },
        leaseId = "lease",
    )
    private fun acknowledged(revision: Long = 11) = state.copy(revision = revision, lifecycle =
        state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = app))

    @Test fun waitsForFreshAcknowledgementAndLaunchesExactlyOnceWithResourcesRemoved() {
        assertTrue(coordinator().start())
        assertEquals(0, launches)
        observer!!(acknowledged(10))
        assertEquals(0, launches)
        val callback = observer!!
        state = acknowledged()
        callback(state)
        callback(state)
        assertEquals(1, launches)
        assertNull(observer)
        assertNull(timeout)
        assertEquals(listOf(StandardControlRequest.BeginVendorHandoff(app.packageName, "lease")), sent)
    }

    @Test fun subscriptionPrecedesSendSoSynchronousAckIsNotLost() {
        onSend = { if (sent.last() is StandardControlRequest.BeginVendorHandoff) {
            state = acknowledged(); observer!!(state)
        } }
        assertTrue(coordinator().start())
        assertEquals(1, launches)
        assertNull(observer)
        assertNull(timeout)
    }

    @Test fun timeoutEndsOnlyTheMatchingLeaseAndReleasesObservation() {
        assertTrue(coordinator().start())
        timeout!!()
        assertEquals(StandardControlRequest.EndVendorHandoff("lease"), sent.last())
        assertEquals(0, launches)
        assertNull(observer)
        assertNull(timeout)
    }

    @Test fun launchFailureReturnsControl() {
        launchResult = false
        assertTrue(coordinator().start())
        observer!!(acknowledged())
        assertEquals(StandardControlRequest.EndVendorHandoff("lease"), sent.last())
        assertNull(observer)
    }

    @Test fun replacedSessionCannotLaunchOrReceiveOldCleanup() {
        assertTrue(coordinator().start())
        token = "replacement"
        observer!!(acknowledged())
        assertEquals(0, launches)
        assertEquals(1, sent.size)
        assertNull(observer)
        assertNull(timeout)
    }

    @Test fun anUndeclaredApplicationNeverTriggersHandoff() {
        state = state.copy(adapter = StandardEarbudAdapter().snapshot())
        assertFalse(coordinator().start())
        assertTrue(sent.isEmpty())
        assertNull(observer)
    }
}
