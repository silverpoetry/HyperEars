package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class AppleNativeEpochLedgerTest {
    @Test fun decisionBeforeA2dpAndA2dpReconnectReuseTheSameLiveHfpEpoch() {
        val ledger = AppleNativeEpochLedger()
        val owner = Any()
        val work = ledger.queued("A", owner)
        ledger.observe(work, false)
        // A2DP requests and cancellation do not change the independent native HFP epoch.
        assertEquals(false, ledger.decision("A")?.accepted)
        assertEquals(work, ledger.queued("A", owner))
        assertEquals(false, ledger.decision("A")?.accepted)
    }
    @Test fun delayedWorkAfterHfpDisconnectCannotAuthorizeReconnectedDevice() {
        val ledger = AppleNativeEpochLedger()
        val owner = Any()
        val old = ledger.queued("A", owner)
        ledger.invalidate("A")
        val fresh = ledger.queued("A", owner)
        assertNotEquals(old, fresh)
        assertNull(ledger.observe(old, false))
        assertNull(ledger.decision("A"))
        assertEquals(false, ledger.observe(fresh, false)?.accepted)
    }
    @Test fun replacementProducerRetainsOnlyWorkAndDecisionsBornInItsLifetime() {
        val ledger = AppleNativeEpochLedger()
        val owner = Any()
        ledger.observe(ledger.queued("old", owner, 10), false)
        ledger.observe(ledger.queued("fresh", owner, 30), false)
        ledger.observe(ledger.queued("disconnected", owner, 30), false)
        // The fresh native decision ran before A2DP delivered the new producer request.
        ledger.replaceProducer(20) { it.address != "disconnected" }
        assertNull(ledger.decision("old"))
        assertNull(ledger.decision("disconnected"))
        assertEquals(false, ledger.decision("fresh")?.accepted)
    }

    @Test fun requestArrivalDuringFreshNativeCallDoesNotRetireItsInFlightEpoch() {
        val ledger = AppleNativeEpochLedger()
        val owner = Any()
        val old = ledger.queued("A", owner, 10)
        val fresh = ledger.queued("A", owner, 30)
        ledger.replaceProducer(20) { true }
        assertNull(ledger.observe(old, false))
        assertEquals(false, ledger.observe(fresh, false)?.accepted)
    }

    @Test fun nativeAcceptanceWinsAndProducerResetDiscardsEveryDecision() {
        val ledger = AppleNativeEpochLedger()
        val work = ledger.queued("A", Any())
        ledger.observe(work, true)
        assertEquals(true, ledger.observe(work, false)?.accepted)
        ledger.clear()
        assertNull(ledger.decision("A"))
        assertNull(ledger.observe(work, false))
    }
}
