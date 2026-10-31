package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class AppleNativeDecisionScopeTest {
    @Test fun onlyMatchingGateInSuccessfulScopeCanDecline() {
        val scope = AppleNativeDecisionScope()
        scope.gate("A", false, true)
        assertNull(scope.current())
        val frame = scope.enter("A")
        scope.gate("B", false, true)
        assertNull(scope.leave(frame, true))
        val next = scope.enter("A")
        scope.gate("A", false, true)
        assertEquals(false, scope.leave(next, true))
    }
    @Test fun errorsAndUnhealthyReadsRemainUnknownButPositiveWins() {
        val scope = AppleNativeDecisionScope()
        val failed = scope.enter("A")
        scope.gate("A", false, false)
        assertNull(scope.leave(failed, true))
        val thrown = scope.enter("A")
        scope.gate("A", false, true)
        assertNull(scope.leave(thrown, false))
        val positive = scope.enter("A")
        scope.gate("A", true, false)
        scope.gate("A", false, true)
        assertEquals(true, scope.leave(positive, false))
    }
    @Test fun interceptorPreservesOriginalReturnAndExceptionEvenIfObserverFails() {
        val scope = AppleNativeDecisionScope()
        val value = Any()
        assertSame(value, scope.observe("A", original = {
            scope.gate("A", false, true)
            value
        }, observer = { throw IllegalStateException("observer") }))
        val failure = IllegalArgumentException("original")
        val actual = runCatching {
            scope.observe("A", original = {
                scope.gate("A", false, true)
                throw failure
            }, observer = { fail("negative after original exception") })
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertNull(scope.current())
    }

    @Test fun nestedCallsRestoreOuterScope() {
        val scope = AppleNativeDecisionScope()
        val outer = scope.enter("A")
        val inner = scope.enter("B")
        scope.gate("B", false, true)
        assertEquals(false, scope.leave(inner, true))
        assertSame(outer, scope.current())
        scope.gate("A", true, true)
        assertEquals(true, scope.leave(outer, true))
        assertNull(scope.current())
    }
}
