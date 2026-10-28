package dev.hyperears.hook
import org.junit.Assert.*
import org.junit.Test
class AppleSpatialPoseGuardTest {
    @Test fun staleFutureRepeatedMalformedAndUnboundedPosesAreRejected() {
        val zero=listOf(0.0,0.0,0.0)
        assertTrue(AppleSpatialPoseGuard.accepts(1000,1000,1,0,zero))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,749,1,0,zero))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,1001,1,0,zero))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,1000,1,1,zero))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,1000,1,0,listOf(Double.NaN,0.0,0.0)))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,1000,1,0,listOf(0.0,0.0)))
        assertFalse(AppleSpatialPoseGuard.accepts(1000,1000,1,0,listOf(4.0,0.0,0.0)))
    }
}
