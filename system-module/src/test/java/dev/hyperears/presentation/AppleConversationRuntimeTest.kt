package dev.hyperears.presentation

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class AppleConversationRuntimeTest {
    private val address = "AA:BB:CC:00:00:01"
    private fun state(status: Int = 1, enabled: Boolean = true) = EarbudState(
        address = address,
        lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED),
        features = DeviceFeatureSnapshot().update(AppleSettingsFeatureState(conversationAwareness = enabled))
            .update(AppleConversationFeatureState(1, status)),
    )
    private fun AppleConversationRuntime.report(status: Int = 1, enabled: Boolean = true, session: String = "one") =
        update(state(status), session, NativeAdmission.HYPEREARS, enabled)

    @Test fun cachedReplayAndCallbacksNeverExtendOriginalSixtySecondDeadline() {
        val host = Fake(address); val runtime = AppleConversationRuntime(host)
        runtime.report()
        host.advance(30_000); runtime.report(); host.callback?.invoke()
        assertEquals(listOf(60_000L), host.ducks.map { it.second })
        host.advance(60_000)
        assertEquals(1, host.releases.size)
        runtime.report(); host.callback?.invoke()
        assertEquals(1, host.ducks.size)
    }

    @Test fun clearPauseAndAdmissionLossNeverReplayCachedStart() {
        listOf("clear", "pause", "admission", "deviceSetting").forEach { loss ->
            val host = Fake(address); val runtime = AppleConversationRuntime(host)
            runtime.report()
            when (loss) {
                "clear" -> runtime.clear()
                "pause" -> runtime.report(enabled = false)
                "admission" -> runtime.update(state(), "one", NativeAdmission.SYSTEM, true)
                else -> runtime.update(state(enabled = false), "one", NativeAdmission.HYPEREARS, true)
            }
            assertEquals(loss, 1, host.releases.size)
            runtime.report()
            assertEquals(loss, 1, host.ducks.size)
            runtime.report(status = 8); runtime.report()
            assertEquals(loss, 2, host.ducks.size)
        }
    }

    @Test fun routeAndCallCallbacksReleaseAndRouteReturnAloneDoesNotRearm() {
        listOf(false, true).forEach { call ->
            val host = Fake(address); val runtime = AppleConversationRuntime(host)
            runtime.report()
            if (call) host.normalMode = false else host.routed = false
            host.callback!!.invoke()
            assertEquals(1, host.releases.size)
            host.normalMode = true; host.routed = true
            host.callback!!.invoke(); runtime.report()
            assertEquals(1, host.ducks.size)
        }
    }

    @Test fun oldReleaseTimerCannotTerminateNewSessionEnvelope() {
        val host = Fake(address); val runtime = AppleConversationRuntime(host)
        runtime.report()
        runtime.remove(address)
        runtime.report(session = "two")
        assertEquals(2, host.ducks.size)
        val terminatedBeforeOldTimer = host.terminations.size
        host.advance(350)
        assertEquals(terminatedBeforeOldTimer, host.terminations.size)
        runtime.clear(); host.advance(700)
        assertEquals(terminatedBeforeOldTimer + 1, host.terminations.size)
    }

    @Test fun playerLossReleasesOnlyOwnedHandleAndNeverShapesAnotherAddress() {
        val host = Fake(address); val runtime = AppleConversationRuntime(host)
        runtime.update(state().copy(address = "AA:BB:CC:00:00:02"), "other", NativeAdmission.HYPEREARS, true)
        assertTrue(host.ducks.isEmpty())
        runtime.report()
        val original = host.player
        host.routed = false; host.callback!!.invoke()
        assertEquals(listOf(original), host.releases)
        host.advance(350)
        assertEquals(listOf(original), host.terminations)
    }

    @Test fun registrationFailureUnwindsAndDoesNotReplayCachedStartAfterRecovery() {
        val host = Fake(address).apply { failListen = true }; val runtime = AppleConversationRuntime(host)
        runtime.report()
        assertEquals(1, host.stops)
        assertNull(host.callback)
        assertTrue(host.ducks.isEmpty())
        host.failListen = false; runtime.report()
        assertTrue(host.ducks.isEmpty())
        runtime.report(status = 8); runtime.report()
        assertEquals(1, host.ducks.size)
    }

    @Test fun failedDuckIsNotRecordedAsOwnedAndFailedReleaseAttemptsTermination() {
        val host = Fake(address).apply { failDuck = true }; val runtime = AppleConversationRuntime(host)
        runtime.report(); runtime.clear()
        assertTrue(host.releases.isEmpty())
        host.failDuck = false
        runtime.report(status = 8); runtime.report()
        host.failRelease = true; runtime.clear()
        assertEquals(1, host.terminations.size)
        assertEquals(1, host.releases.size)
    }

    private class Fake(val address: String) : AppleConversationBackend {
        override var available = true
        override var normalMode = true
        var time = 0L
        var routed = true
        var failListen = false
        var failDuck = false
        var failRelease = false
        var stops = 0
        var callback: (() -> Unit)? = null
        val player = AppleConversationAudio.Player(17, 10123, Any())
        val ducks = mutableListOf<Pair<AppleConversationAudio.Player, Long>>()
        val releases = mutableListOf<AppleConversationAudio.Player>()
        val terminations = mutableListOf<AppleConversationAudio.Player>()
        private val tasks = mutableListOf<Pair<Long, Runnable>>()
        override fun now() = time
        override fun players(address: String) = if (routed && address == this.address) listOf(player) else emptyList()
        override fun duck(player: AppleConversationAudio.Player, remaining: Long) {
            if (failDuck) error("duck failed")
            ducks += player to remaining
        }
        override fun release(player: AppleConversationAudio.Player) {
            releases += player
            if (failRelease) error("release failed")
        }
        override fun terminate(player: AppleConversationAudio.Player) { terminations += player }
        override fun schedule(task: Runnable, delayMs: Long) { tasks += time + delayMs to task }
        override fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        override fun listen(changed: () -> Unit) {
            callback = changed
            if (failListen) error("registration failed")
        }
        override fun stopListening() { stops++; callback = null }
        fun advance(target: Long) {
            while (true) {
                val next = tasks.filter { it.first <= target }.minByOrNull { it.first } ?: break
                tasks.remove(next); time = next.first; next.second.run()
            }
            time = target
        }
    }
}
