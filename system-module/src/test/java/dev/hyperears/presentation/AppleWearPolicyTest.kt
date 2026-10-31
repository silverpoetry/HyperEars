package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test

class AppleWearPolicyTest {
    private val player = AppleWearPolicy.Player("token", 42, 7)
    private fun sample(count: Int, playing: Boolean = true, player: AppleWearPolicy.Player? = this.player,
        valid: Boolean = true) = AppleWearPolicy.Sample("session", count, player, playing, valid)

    @Test fun initialAndDuplicateReportsNeverPlay() {
        val p = AppleWearPolicy()
        assertNull(p.update(sample(0, false), 0))
        assertNull(p.update(sample(2, false), 1))
        assertNull(p.update(sample(2), 2))
    }

    @Test fun resumeRequiresIssuedObservedPauseSamePlayer() {
        val p = AppleWearPolicy()
        p.update(sample(2), 0)
        assertEquals(AppleWearPolicy.Action.PAUSE, p.update(sample(1), 10))
        p.issued(true, 10)
        assertNull(p.update(sample(1, false), 20))
        assertEquals(AppleWearPolicy.Action.PLAY, p.update(sample(2, false), 30))
        assertNull(p.update(sample(2, false), 31))
    }

    @Test fun failedOrUnacknowledgedPauseCannotResume() {
        for (success in listOf(false, true)) {
            val p = AppleWearPolicy()
            p.update(sample(2), 0)
            p.update(sample(0), 1)
            p.issued(success, 1)
            assertNull(p.update(sample(0), 1502))
            assertNull(p.update(sample(2, false), 1503))
        }
    }

    @Test fun identityRouteCallAndSessionLossInvalidateOwnership() {
        val changes = listOf(sample(1, false, player.copy(token = "other")),
            sample(1, false, player.copy(playerId = 8)), sample(1, false, valid = false),
            sample(1, false).copy(session = "replacement"))
        changes.forEach { changed ->
            val p = AppleWearPolicy()
            p.update(sample(2), 0); p.update(sample(1), 1); p.issued(true, 1)
            p.update(sample(1, false), 2); p.update(changed, 3)
            assertNull(p.update(sample(2, false), 4))
        }
    }

    @Test fun userRestartAndFiveMinuteDeadlineClearOwnership() {
        for (restart in listOf(false, true)) {
            val p = AppleWearPolicy()
            p.update(sample(2), 0); p.update(sample(0), 1); p.issued(true, 1)
            p.update(sample(0, false), 2)
            p.update(sample(0, restart), if (restart) 3 else 300002)
            assertNull(p.update(sample(2, false), 300003))
        }
    }

    @Test fun reinsertionBeforePauseAckDoesNotCreateDeferredPlay() {
        val p = AppleWearPolicy()
        p.update(sample(2), 0); p.update(sample(0), 1); p.issued(true, 1)
        assertNull(p.update(sample(2), 2))
        assertNull(p.update(sample(2, false), 3))
    }
}
