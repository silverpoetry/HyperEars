package dev.hyperears.presentation

import dev.hyperears.integration.AppleConversationActivity.*
import org.junit.Assert.*
import org.junit.Test

class AppleConversationLeaseTest {
    @Test fun consumedStartSurvivesPauseOwnershipLossAndUnknownSnapshots() {
        val history = AppleConversationHistory()
        assertFalse(history.consume("earbuds", "session", START))
        history.consume("earbuds", "session", UNKNOWN)
        assertTrue(history.consume("earbuds", "session", START))
        val replacement = AppleConversationLease()
        replacement.invalidate()
        assertFalse(replacement.report(START, 10, true))
        history.consume("earbuds", "session", RELEASE)
        assertFalse(history.consume("earbuds", "session", START))
        assertFalse(history.consume("earbuds", "new-session", START))
    }
    @Test fun repeatedStartCannotExtendAbsoluteDeadline() {
        val lease = AppleConversationLease()
        assertTrue(lease.report(START, 100, true))
        assertTrue(lease.report(START, 59_000, true))
        assertEquals(60_100L, lease.deadline)
        assertFalse(lease.report(START, 60_100, true))
        assertFalse(lease.report(START, 100_000, true))
        lease.report(RELEASE, 100_001, true)
        assertTrue(lease.report(START, 100_002, true))
    }

    @Test fun ineligibleStartCannotBeReplayedWhenRouteReturns() {
        val lease = AppleConversationLease()
        assertFalse(lease.report(START, 0, false))
        assertFalse(lease.report(START, 1, true))
        lease.report(RELEASE, 2, true)
        assertTrue(lease.report(START, 3, true))
        lease.invalidate()
        assertFalse(lease.report(START, 4, true))
    }

    @Test fun unknownReportsNeitherStartNorRefreshAnEnvelope() {
        val lease = AppleConversationLease()
        assertFalse(lease.report(UNKNOWN, 0, true))
        assertTrue(lease.report(START, 1, true))
        assertTrue(lease.report(UNKNOWN, 30_000, true))
        assertEquals(60_001L, lease.deadline)
        assertFalse(lease.report(UNKNOWN, 60_001, true))
    }

    @Test fun losingEligibilityReleasesImmediately() {
        val lease = AppleConversationLease()
        assertTrue(lease.report(START, 1, true))
        assertFalse(lease.report(START, 2, false))
        assertNull(lease.deadline)
    }
}
