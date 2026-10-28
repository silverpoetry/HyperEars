package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class AppleSpatialContractTest {
    @Test fun modesAndCalibrationUseExistingTypedTransport() {
        val requests=AppleSpatialMode.entries.map { AppleSpatialControlRequest.SetMode(it) }+
            listOf(AppleSpatialControlRequest.BeginCalibration,AppleSpatialControlRequest.CalibrationStep,AppleSpatialControlRequest.Recenter)
        requests.forEach { assertEquals(it,ControlRequestTransport.decode(ControlRequestTransport.encode(it))) }
        val snapshot=DeviceFeatureSnapshot().update(AppleSpatialFeatureState(true,true,AppleSpatialMode.TRACKING,calibrated=true))
        assertEquals(snapshot,FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
    }
    @Test fun hostPublicationRequiresPrivateSessionReceiver() {
        val adapter=AppleAirPodsAdapter()
        assertFalse(adapter.publishSpatialHostState(AppleSpatialFeatureState(true,true)))
        adapter.privateMotionReceiver={}
        assertTrue(adapter.publishSpatialHostState(AppleSpatialFeatureState(true,true)))
        assertTrue(adapter.controlRequestContract.supports(adapter,AppleSpatialControlRequest.SetMode(AppleSpatialMode.FIXED)))
        assertFalse(adapter.controlRequestContract.supports(adapter,AppleSpatialControlRequest.SetMode(AppleSpatialMode.TRACKING)))
    }
}
