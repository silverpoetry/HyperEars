package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class ProtocolCleanupEffectTest {
    @Test fun cleanupIsExplicitAndBoundedRatherThanTelemetry() {
        val adapter = object : StandardEarbudAdapter() {
            override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
                scope.scheduleProtocolCleanup("fixture.fit", 7, 16_000)
                scope.cancelProtocolCleanup("fixture.fit")
            }
        }
        assertEquals(listOf(AdapterEffect.ScheduleProtocolCleanup("fixture.fit", 7, 16_000),
            AdapterEffect.CancelProtocolCleanup("fixture.fit")), adapter.controlWritten(StandardControlRequest.Refresh))
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        assertTrue(adapter.expireProtocolWorkflow("fixture.fit", 7).isEmpty())
        for (delay in listOf(0L, -1L, 60_001L)) {
            assertTrue(runCatching { AdapterEffect.ScheduleProtocolCleanup("fit", 1, delay) }.isFailure)
        }
        assertTrue(runCatching { AdapterEffect.ScheduleProtocolCleanup("fit", 0, 10) }.isFailure)
    }
}
