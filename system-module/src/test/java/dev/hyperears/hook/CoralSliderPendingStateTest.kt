package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class CoralSliderPendingStateTest {
    @Test fun confirmedTargetIgnoresLateOldValueWithoutBecomingPendingAgain() {
        val state = CoralSliderPendingState<String>()
        val lease = requireNotNull(state.submit("bass", 0, 2, "session", 0))
        assertTrue(state.project("bass", 2, "session", 100).enabled)
        val lateOld = state.project("bass", 0, "session", 200)
        assertEquals(2, lateOld.value)
        assertTrue(lateOld.enabled)
        assertNull(lateOld.pending)
        assertNull(lateOld.timedOut)
        assertEquals(2, state.project("bass", 2, "session", 300).value)
        assertNull(state.expire(lease, "session", 2500))
    }

    @Test fun successGuardEndsAtOriginalDeadlineAndIsNotExtendedByReports() {
        val state = CoralSliderPendingState<String>()
        state.submit("bass", 0, 2, "session", 0)
        state.project("bass", 2, "session", 100)
        assertEquals(2, state.project("bass", 0, "session", 2499).value)
        val after = state.project("bass", 0, "session", 2500)
        assertEquals(0, after.value)
        assertTrue(after.enabled)
        assertNull(after.timedOut)
    }

    @Test fun newSubmissionCanIntentionallyReturnToThePreviouslyGuardedValue() {
        val state = CoralSliderPendingState<String>()
        val first = requireNotNull(state.submit("bass", 0, 2, "session", 0))
        state.project("bass", 2, "session", 100)
        val second = requireNotNull(state.submit("bass", 2, 0, "session", 200))
        assertFalse(state.project("bass", 2, "session", 210).enabled)
        val accepted = state.project("bass", 0, "session", 220)
        assertTrue(accepted.enabled)
        assertEquals(0, accepted.value)
        assertNull(state.expire(first, "session", 2500))
        assertNull(state.expire(second, "session", 2700))
    }

    @Test fun successGuardDoesNotSuppressDifferentValuesOrLeakIntoAnotherSession() {
        val state = CoralSliderPendingState<String>()
        state.submit("bass", 0, 2, "session", 0)
        state.project("bass", 2, "session", 100)
        assertEquals(1, state.project("bass", 1, "session", 200).value)
        assertEquals(0, state.project("bass", 0, "new-session", 300).value)
    }

    @Test fun oldReportsNeverMoveTheThumbWhileAwaitingTarget() {
        val state = CoralSliderPendingState<String>()
        val lease = requireNotNull(state.submit("bass", 0, 2, "session", 100))
        val old = state.project("bass", 0, "session", 200)
        assertEquals(2, old.value)
        assertFalse(old.enabled)
        assertEquals(lease, old.pending)
        assertNull(old.timedOut)
        val success = state.project("bass", 2, "session", 300)
        assertEquals(2, success.value)
        assertTrue(success.enabled)
        assertNull(success.pending)
        assertNull(state.expire(lease, "session", 3000))
    }

    @Test fun timeoutRestoresConfirmedValueAndSignalsExactlyOnce() {
        val state = CoralSliderPendingState<String>()
        val lease = requireNotNull(state.submit("prompt", 3, 5, "session", 0))
        assertNull(state.expire(lease, "session", 2499))
        val expired = requireNotNull(state.expire(lease, "session", 2500))
        assertEquals(3, expired.value)
        assertTrue(expired.enabled)
        assertEquals(lease, expired.timedOut)
        assertNull(state.expire(lease, "session", 2501))
        assertNull(state.project("prompt", 3, "session", 2502).timedOut)
    }

    @Test fun reportAtDeadlineConsumesTheSameTimeoutAsTheTimer() {
        val state = CoralSliderPendingState<String>()
        val lease = requireNotNull(state.submit("bass", 1, 2, "session", 0))
        val report = state.project("bass", 2, "session", 2500)
        assertEquals(1, report.value)
        assertEquals(lease, report.timedOut)
        assertNull(state.expire(lease, "session", 2500))
    }

    @Test fun oldSameValueTimeoutDoesNotClearReplacementRequest() {
        val state = CoralSliderPendingState<String>()
        val first = requireNotNull(state.submit("bass", 0, 2, "session", 0))
        val second = requireNotNull(state.submit("bass", 0, 2, "session", 1000))
        assertNotEquals(first.id, second.id)
        assertNull(state.expire(first, "session", 2500))
        val stillWaiting = state.project("bass", 0, "session", 2500)
        assertEquals(second, stillWaiting.pending)
        assertEquals(2, stillWaiting.value)
        assertFalse(stillWaiting.enabled)
    }

    @Test fun sessionReplacementClearsAllKeysAndRejectsOldCallbacks() {
        val state = CoralSliderPendingState<String>()
        val first = requireNotNull(state.submit("bass", 0, 2, "old", 0))
        state.submit("prompt", 1, 5, "old", 0)
        val current = state.project("bass", 1, "new", 100)
        assertTrue(current.enabled)
        assertEquals(1, current.value)
        assertNull(state.expire(first, "old", 3000))
        val next = requireNotNull(state.submit("bass", 1, 2, "new", 200))
        assertNull(state.expire(first, "new", 3000))
        assertEquals(next, state.project("bass", 1, "new", 300).pending)
        assertNull(state.project("prompt", 2, "new", 300).pending)
    }

    @Test fun unchangedOrUnsentCancelledDragCreatesNoRequest() {
        val state = CoralSliderPendingState<String>()
        assertNull(state.submit("bass", 1, 1, "session", 0))
        assertTrue(state.project("bass", 1, "session", 0).enabled)
        // Drag previews stay inside the widget; no submit call is made on ACTION_CANCEL.
        assertNull(state.project("bass", 1, "session", 3000).timedOut)
    }

    @Test fun abortingFailedDispatchRollsBackWithoutTimeoutAndPreservesOtherKey() {
        val state = CoralSliderPendingState<String>()
        val bass = requireNotNull(state.submit("bass", 0, 2, "session", 0))
        val prompt = requireNotNull(state.submit("prompt", 1, 4, "session", 0))
        val aborted = requireNotNull(state.cancel(bass, "session"))
        assertEquals(0, aborted.value)
        assertTrue(aborted.enabled)
        assertNull(aborted.timedOut)
        assertEquals(prompt, state.project("prompt", 1, "session", 100).pending)
        assertNull(state.expire(bass, "session", 3000))
    }
}
