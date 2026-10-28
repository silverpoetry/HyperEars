package dev.hyperears.integration
import dev.hyperears.protocol.oppo.OppoWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class OppoFitTestTest {
    private fun f(command:Int,vararg p:Int)=Wire.Frame(command,0,p.map(Int::toByte).toByteArray(),byteArrayOf())
    private fun ready():OppoFitSession=OppoFitSession().apply{
        identify(0x061410);offer(f(0x8200,0,1,4));offer(f(0x8109,0,2,1,2,2,2))
    }
    @Test fun capabilityWearAndLeaseAreIndependentAndCompletionStopsOnce() {
        val s=OppoFitSession();s.identify(0x061410)
        assertFalse(s.state!!.ready)
        s.offer(f(0x8200,0,1,4));assertFalse(s.state!!.ready)
        s.offer(f(0x8109,0,2,1,2,2,2));assertTrue(s.state!!.ready)
        val request=OppoFitControlRequest.Start(s.state!!,7)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertArrayEquals(byteArrayOf(0xaa.toByte(),8,0,0,5,4,0xf0.toByte(),1,0,1),s.encode(request).single())
        assertTrue(s.encode(request).isEmpty())
        s.offer(f(0x0204,4,1,1,2,6))
        assertEquals(7L,s.state!!.resultLease);assertEquals(6,s.state!!.rightResult)
        assertArrayEquals(byteArrayOf(0xaa.toByte(),8,0,0,5,4,0xf0.toByte(),1,0,0),s.drain().single())
        assertTrue(s.release().isEmpty())
    }
    @Test fun adapterDeadlineIsArmedAfterWriteAndCleanupConsumesOnlyMatchingLease() {
        val adapter=OppoEarbudAdapter()
        adapter.receive(Wire.packet(0x8103,payload=byteArrayOf(0,0x10,0x14,6)))
        adapter.receive(Wire.packet(0x8200,payload=byteArrayOf(0,1,4)))
        adapter.receive(Wire.packet(0x8109,payload=byteArrayOf(0,2,1,2,2,2)))
        val feature=adapter.runtimeState().features.get<OppoFitFeatureState>()!!
        val request=OppoFitControlRequest.Start(feature,123)
        assertTrue(adapter.executeControl(request).accepted)
        val effects=adapter.controlWritten(request)
        assertTrue(effects.any{it is AdapterEffect.ScheduleProtocolCleanup && it.lease==123L})
        assertTrue(adapter.expireProtocolWorkflow(OppoFitFeatureState.FEATURE_ID,124).isEmpty())
        assertEquals(1,adapter.expireProtocolWorkflow(OppoFitFeatureState.FEATURE_ID,123).size)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        assertTrue(adapter.runtimeState().features.get<OppoFitFeatureState>()!!.timedOut)
        assertTrue(adapter.runtimeState().features.get<OppoFitFeatureState>()!!.ready)
        assertFalse(adapter.executeControl(request.copy(lease=125)).accepted)
    }
    @Test fun invalidFramesAndForeignStopCannotChangeOwnedWorkflow() {
        val s=ready();val before=s.state!!;s.encode(OppoFitControlRequest.Start(before,8))
        s.offer(f(0x0204,4,1,2,2,1));assertEquals(before,s.state)
        s.offer(f(0x8405,0));assertEquals(before,s.state)
        assertTrue(s.encode(OppoFitControlRequest.Stop(0x061410,9)).isEmpty())
        s.offer(f(0x8405,5));assertEquals(5,s.state!!.error);assertEquals(1,s.drain().size)
        s.reset();assertNull(s.state);assertTrue(s.release().isEmpty())
        s.identify(0x010203);s.offer(f(0x8200,0,1,4));s.offer(f(0x8109,0,2,1,2,2,2));assertNull(s.state)
    }
    @Test fun wearLossAndCapabilityRemovalCancelAndRequireFreshSnapshot() {
        val s=ready();val old=s.state!!;s.encode(OppoFitControlRequest.Start(old,10))
        s.offer(f(0x8109,0,2,1,0,2,2));assertFalse(s.state!!.ready);assertEquals(1,s.drain().size)
        assertTrue(s.encode(OppoFitControlRequest.Start(old,11)).isEmpty())
        s.offer(f(0x8109,0,2,1,2,2,2));s.encode(OppoFitControlRequest.Start(s.state!!,12))
        s.offer(f(0x8200,0,0));assertEquals(1,s.drain().size);assertFalse(s.state!!.ready)
    }
}
