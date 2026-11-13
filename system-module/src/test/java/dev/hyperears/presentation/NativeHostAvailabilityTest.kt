package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test

class NativeHostAvailabilityTest {
    @Test
    fun enablingLogsAfterStartupCanReportTheOriginalInitializationFailure() {
        val status = NativeHostAvailability()
        val failure = IllegalStateException("registration reader expected one match, found 0")
        status.complete("sdk=35 unlockedAtInitialization=false", mapOf("ownership" to Result.failure(failure)))
        status.report(false, "initialization") { _, _ -> fail("Diagnostics emitted while disabled") }
        val events = mutableListOf<Pair<String, Throwable?>>()
        status.report(true, "logging-enabled") { message, error -> events += message to error }
        assertTrue(events.first().first.contains("unlockedAtInitialization=false"))
        assertTrue(events.last().first.contains("ownership ready=false"))
        assertSame(failure, events.last().second)
    }

    @Test
    fun failedPreviewCanRecoverDiagnosticContextAfterTheOriginalLogHasRolledOut() {
        val status = NativeHostAvailability()
        val failure = ClassNotFoundException("native operation")
        status.complete("sdk=35", mapOf("ownership" to Result.failure(failure), "circulation" to Result.success(Unit)))
        repeat(2) {
            val events = mutableListOf<Pair<String, Throwable?>>()
            status.report(true, "preview") { message, error -> events += message to error }
            assertEquals(3, events.size)
            assertSame(failure, events[1].second)
            assertEquals("host capability=circulation ready=true", events[2].first)
        }
    }

    @Test
    fun pendingAndCompletedAreDistinguishableAndFailuresDoNotClearOtherCapabilities() {
        val status = NativeHostAvailability()
        val events = mutableListOf<String>()
        status.report(true, "preview") { message, _ -> events += message }
        assertTrue(events.single().contains("not completed"))
        status.complete("sdk=36", mapOf("ownership" to Result.success(Unit)))
        events.clear()
        status.report(true, "preview") { message, _ -> events += message }
        assertTrue(events.last().contains("ownership ready=true"))
    }

    @Test
    fun contractDiagnosticsStillRejectMissingAndAmbiguousMatches() {
        assertEquals("only", listOf("only").singleNativeContract("reader"))
        for (matches in listOf(emptyList(), listOf("one", "two"))) {
            val error = runCatching { matches.singleNativeContract("registration") }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("registration"))
            assertTrue(error.message!!.contains("found ${matches.size}"))
        }
    }
}
