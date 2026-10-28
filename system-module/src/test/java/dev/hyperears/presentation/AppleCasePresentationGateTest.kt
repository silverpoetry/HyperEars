package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test

class AppleCasePresentationGateTest {
    private val metadata = NativePresentationMetadata(HeadsetConnection("physical", 100), NativeAdmission.HYPEREARS)
    @Test fun initialConnectionPopupIsNotDuplicatedByTheFirstCaseEdge() {
        val gate = AppleCasePresentationGate()
        assertFalse(gate.accept("device", "session", 1, true, metadata, 200))
        assertFalse(gate.accept("device", "session", 1, true, metadata, 20_000))
        assertTrue(gate.accept("device", "session", 2, true, metadata, 20_001))
        assertFalse(gate.accept("device", "session", 2, true, metadata, 20_002))
    }
    @Test fun replayNativeOwnerAndClosedCaseNeverOpenButConsumeOldEdges() {
        val gate = AppleCasePresentationGate()
        assertFalse(gate.accept("device", "session", 0, true, metadata, 20_000))
        assertFalse(gate.accept("device", "session", 1, true, metadata.copy(replay = true), 20_000))
        assertFalse(gate.accept("device", "session", 1, true, metadata, 20_001))
        assertFalse(gate.accept("device", "session", 2, true, metadata.copy(admission = NativeAdmission.SYSTEM), 20_001))
        assertFalse(gate.accept("device", "session", 3, false, metadata, 20_001))
        assertTrue(gate.accept("device", "new-session", 1, true, metadata, 20_002))
    }
}
