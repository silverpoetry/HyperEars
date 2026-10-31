package dev.hyperears.hook
import org.junit.Assert.*
import org.junit.Test
class AppleSpatialAvailabilityLeaseTest {
    @Test fun routeAndCallReturnRetainEndpointWithoutRestartingRendering() {
        var releases=0;var notifications=0;var disposed=0
        val lease=AppleSpatialAvailabilityLease({releases++},{notifications++},{disposed++})
        lease.update(true,true)
        lease.update(true,false)
        lease.update(true,false)
        assertEquals(1,releases);assertEquals(0,disposed);assertEquals(2,notifications)
        lease.update(true,true)
        assertEquals(1,releases);assertEquals(0,disposed);assertEquals(3,notifications)
        lease.update(false,false)
        assertEquals(1,disposed)
        lease.update(true,true)
        assertEquals(3,notifications)
    }
}
