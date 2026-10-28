package dev.hyperears.presentation

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class AppleLongPressRuntimeTest {
    private class Host : AppleLongPressRuntime.Host {
        var time = 0L
        var ready = true
        var launches = 0
        var launchSuccess = true
        var stops = 0
        var callback: (() -> Unit)? = null
        var task: Runnable? = null
        override fun now() = time
        override fun ready(address: String) = ready
        override fun assistant(address: String): Boolean { launches++; return launchSuccess }
        override fun listen(changed: () -> Unit): Boolean { callback = changed; return true }
        override fun stop() { stops++ }
        override fun schedule(task: Runnable, delay: Long) { this.task = task }
        override fun cancel(task: Runnable) { this.task = null }
    }
    private fun state(mask: Int = 0, report: Long = 1, sequence: Long = 1,
        bud: AppleBud = AppleBud.LEFT, press: AppleStemPress = AppleStemPress.LONG): EarbudState =
        EarbudState(address = "address", lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
            .withFeature(AppleGestureSettingsFeatureState(leftLongPress = AppleLongPressAction.DIGITAL_ASSISTANT,
                rightLongPress = AppleLongPressAction.NOISE_CONTROL, listeningCycleMask = 6,
                interceptionMask = mask, interceptionSequence = report))
            .withFeature(AppleListeningModeFeatureState(AppleListeningMode.ANC,
                setOf(AppleListeningMode.ANC, AppleListeningMode.TRANSPARENCY)))
            .withFeature(AppleStemFeatureState(press, bud, sequence))

    @Test fun onlyFreshAckThenFreshLongEventExecutesAndNoiseUsesReportedCycle() {
        val host = Host(); val writes = mutableListOf<Boolean>(); val controls = mutableListOf<AppleControlRequest>()
        val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, request -> controls.add(request); true })
        runtime.update(state(), "s")
        runtime.update(state(mask = 8), "s") // Cached sequence cannot acknowledge.
        assertEquals(0, host.launches)
        runtime.update(state(8, 2, 2), "s") // Ack packet's cached stem is consumed.
        assertEquals(0, host.launches)
        runtime.update(state(8, 2, 3), "s")
        runtime.update(state(8, 2, 3), "s")
        assertEquals(1, host.launches)
        runtime.update(state(8, 2, 4, press = AppleStemPress.DOUBLE), "s")
        assertEquals(1, host.launches)
        runtime.update(state(8, 2, 5, bud = AppleBud.RIGHT), "s")
        assertEquals(listOf(AppleControlRequest.SetListeningMode(AppleListeningMode.TRANSPARENCY)), controls)
        assertEquals(listOf(true), writes)
        runtime.clear()
        assertEquals(listOf(true, false), writes)
        assertEquals(1, host.stops)
    }

    @Test fun timeoutRestoresUnacknowledgedWriteAndDoesNotRetry() {
        val host = Host(); val writes = mutableListOf<Boolean>()
        val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, _ -> true })
        runtime.update(state(), "s")
        host.time = 3_000; host.task!!.run()
        runtime.update(state(8, 2, 8), "s")
        assertEquals(listOf(true, false), writes)
        assertEquals(0, host.launches)
    }

    @Test fun initiallyMissingRouteWaitsWithoutWriteAndConflictingAckReleases() {
        val host = Host(); host.ready = false
        val writes = mutableListOf<Boolean>()
        val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, _ -> true })
        runtime.update(state(), "s")
        assertTrue(writes.isEmpty())
        assertNull(host.task)
        host.ready = true; host.callback!!()
        assertEquals(listOf(true), writes)
        runtime.update(state(9, 2, 2), "s")
        assertEquals(listOf(true, false), writes)
        assertEquals(0, host.launches)
    }

    @Test fun temporaryCallNeedsFreshRestoreAckBeforeRouteRecoveryReacquires() {
        val host = Host(); val writes = mutableListOf<Boolean>()
        val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, _ -> true })
        runtime.update(state(), "s"); runtime.update(state(8, 2), "s")
        host.ready = false; host.callback!!()
        assertEquals(listOf(true, false), writes)
        host.ready = true; host.callback!!()
        assertEquals(listOf(true, false), writes)
        runtime.update(state(0, 2, 2), "s") // Cached restore value is insufficient.
        assertEquals(listOf(true, false), writes)
        runtime.update(state(0, 3, 2), "s")
        assertEquals(listOf(true, false, true), writes)
        runtime.update(state(8, 4, 3), "s")
        assertEquals(0, host.launches)
        runtime.update(state(8, 4, 4), "s")
        assertEquals(1, host.launches)
    }

    @Test fun restorationTimeoutOrDifferentOwnerDoesNotRearm() {
        for (timeout in listOf(false, true)) {
            val host = Host(); val writes = mutableListOf<Boolean>()
            val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, _ -> true })
            runtime.update(state(), "s"); runtime.update(state(8, 2), "s")
            host.ready = false; host.callback!!()
            if (timeout) { host.time = 3_000; host.task!!.run() }
            else runtime.update(state(1, 3, 2), "s")
            host.ready = true; host.callback!!()
            runtime.update(state(0, 4, 3), "s")
            assertEquals(listOf(true, false), writes)
            assertEquals(0, host.launches)
        }
    }

    @Test fun failedAcquireIsRestoredAndExistingOwnerIsNeverTakenOver() {
        for (mask in listOf(0, 8)) {
            val host = Host(); val writes = mutableListOf<Boolean>()
            val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); false }, { _, _ -> true })
            runtime.update(state(mask), "s")
            runtime.update(state(8, 2, 2), "s")
            assertEquals(if (mask == 0) listOf(true, false) else emptyList<Boolean>(), writes)
            assertEquals(0, host.launches)
        }
    }

    @Test fun routeCallExecutorLossAndRemovalReleaseBeforeFurtherEvents() {
        for (cause in listOf("route", "executor", "remove", "ownership")) {
            val host = Host(); val writes = mutableListOf<Boolean>()
            val runtime = AppleLongPressRuntime(host, { _, _, acquire -> writes.add(acquire); true }, { _, _ -> true })
            runtime.update(state(), "s"); runtime.update(state(8, 2), "s")
            when (cause) {
                "route" -> { host.ready = false; host.callback!!() }
                "executor" -> { host.launchSuccess = false; runtime.update(state(8, 2, 2), "s") }
                "remove" -> runtime.remove("address")
                else -> runtime.update(state(8, 2).let { it.copy(lifecycle = it.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
                    externalControlApp = ControlAppSpec("fixture.controller", "Controller"))) }, "s")
            }
            assertEquals(listOf(true, false), writes)
            host.callback!!()
            assertEquals(if (cause == "executor") 1 else 0, host.launches)
        }
    }

    @Test fun sessionReplacementRestoresOldLeaseAndDoesNotConsumeOldEvents() {
        val host = Host(); val writes = mutableListOf<Pair<String, Boolean>>()
        val runtime = AppleLongPressRuntime(host, { _, session, acquire -> writes.add(session to acquire); true }, { _, _ -> true })
        runtime.update(state(), "old"); runtime.update(state(8, 2, 5), "old")
        runtime.update(state(sequence = 99), "new")
        runtime.update(state(8, 2, 99), "new")
        assertEquals(listOf("old" to true, "old" to false, "new" to true), writes)
        assertEquals(0, host.launches)
    }

    @Test fun oneModeOrUnsupportedModeLeavesFirmwareInControl() {
        val settings = AppleGestureSettingsFeatureState(leftLongPress = AppleLongPressAction.DIGITAL_ASSISTANT,
            rightLongPress = AppleLongPressAction.NOISE_CONTROL, listeningCycleMask = 2)
        val listening = AppleListeningModeFeatureState(AppleListeningMode.ANC, setOf(AppleListeningMode.ANC))
        assertFalse(AppleLongPressActions.eligible(settings, listening))
        assertFalse(AppleLongPressActions.eligible(settings.copy(listeningCycleMask = 6), listening))
        assertTrue(AppleLongPressActions.eligible(settings.copy(rightLongPress = AppleLongPressAction.DIGITAL_ASSISTANT), null))
    }
}
