package dev.hyperears.runtime

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class WrittenControlEffectsTest {
    @Test fun locallyPublishedWorkflowLeaseIsVisibleWithoutOptimisticDeviceResult() {
        val adapter = object : StandardEarbudAdapter() {
            override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state -> state is SamsungDailySettingsFeatureState }
            override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
                val fit = request as SamsungDailyControlRequest.FitTest
                val feature = SamsungDailySettingsFeatureState(model = "BUDS2", revision = 1, fitLease = fit.lease.takeIf { fit.start })
                adoptRuntimeState(runtimeState().copy(features = runtimeState().features.update(feature)))
                if (fit.start) scope.scheduleProtocolCleanup("samsung.fit", fit.lease, 30_000)
                else scope.cancelProtocolCleanup("samsung.fit")
            }
        }
        val request = SamsungDailyControlRequest.FitTest("BUDS2", 1, 5, true)
        val started = writtenControlEffects(adapter, request)
        assertTrue(started.localStateChanged)
        assertEquals(listOf(AdapterEffect.ScheduleProtocolCleanup("samsung.fit", 5, 30_000)), started.effects)
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitResult)
        val stopped = writtenControlEffects(adapter, request.copy(start = false))
        assertTrue(stopped.localStateChanged)
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitLease)
    }
}
