package dev.hyperears.bridge

import dev.hyperears.integration.StandardControlRequest
import org.junit.Assert.*
import org.junit.Test

class VendorHandoffReturnLeasesTest {
    private val address="AA:BB:CC:DD:EE:FF"
    @Test fun failedReturnRemainsRetryableButSuccessfulReturnIsConsumed() {
        val leases=VendorHandoffReturnLeases()
        leases.record(address,"token","lease")
        assertFalse(leases.returnControl(address,{"token"}) { _,_->error("transport unavailable") })
        assertTrue(leases.contains(address.lowercase(),"token"))
        assertTrue(leases.returnControl(address,{"token"}) { _,request ->
            assertEquals(StandardControlRequest.EndVendorHandoff("lease"),request)
            assertFalse(leases.contains(address,"token"))
            assertFalse(leases.returnControl(address,{"token"}) { _,_->fail("duplicate return") })
        })
        assertFalse(leases.contains(address,"token"))
    }
    @Test fun staleSessionCannotReturnAndDoesNotConsumeAReplacementLease() {
        val leases=VendorHandoffReturnLeases()
        leases.record(address,"old","old-lease")
        assertFalse(leases.returnControl(address,{
            leases.record(address,"new","new-lease"); "new"
        }) { _,_->fail("old session sent") })
        assertTrue(leases.contains(address,"new"))
        assertTrue(leases.returnControl(address,{"new"}) { _,request ->
            assertEquals(StandardControlRequest.EndVendorHandoff("new-lease"),request)
        })
    }
    @Test fun synchronousNewHandoffSurvivesCompletionOfThePreviousReturn() {
        val leases=VendorHandoffReturnLeases()
        leases.record(address,"token","first")
        assertTrue(leases.returnControl(address,{"token"}) { _,_->leases.record(address,"token","second") })
        assertTrue(leases.returnControl(address,{"token"}) { _,request ->
            assertEquals(StandardControlRequest.EndVendorHandoff("second"),request)
        })
    }
}
