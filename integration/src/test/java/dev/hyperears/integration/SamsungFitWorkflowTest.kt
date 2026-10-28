package dev.hyperears.integration
import dev.hyperears.protocol.samsung.SamsungBudsCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SamsungFitWorkflowTest {
    private val model=Wire.Model.BUDS2_PRO
    private fun status()=Wire.packet(0x61,ByteArray(50).apply{this[0]=13;this[1]=1;this[2]=80;this[3]=70;this[9]=1},model)
    private fun frame(id:Int,vararg p:Int)=Wire.packet(id,p.map(Int::toByte).toByteArray(),model)
    @Test fun actualAdapterRequiresReportCorrelatesOnlyOwnedResultsAndStopsOnce() {
        val a=SamsungBuds2ProAdapter(model)
        val request=SamsungDailyControlRequest.FitTest(model.name,13,42,true)
        assertFalse(a.supportsControl(request));a.receive(status());assertTrue(a.supportsControl(request))
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val unowned=a.receive(frame(158,1,1));assertTrue(unowned.commands.isEmpty())
        val start=a.executeControl(request);assertTrue(start.accepted);a.controlWritten(request)
        assertEquals(157,Wire.parseFrame(start.commands.single())!!.id)
        assertArrayEquals(byteArrayOf(1),Wire.parseFrame(start.commands.single())!!.payload)
        assertFalse(a.executeControl(request.copy(lease=43)).accepted)
        a.receive(frame(0x22,157,0));assertNull(a.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitResult)
        val result=a.receive(frame(158,0,1))
        assertTrue(result.effects.any{it is AdapterEffect.CancelProtocolCleanup && it.key=="samsung.fit"})
        assertEquals(listOf(0,1),a.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitResult)
        assertEquals(42L,a.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitResultLease)
        assertEquals(1,result.commands.count{Wire.parseFrame(it)?.id==157})
        assertTrue(a.receive(frame(158,1,1)).commands.isEmpty())
        assertTrue(a.prepareProtocolRelease().isEmpty())
    }
    @Test fun gracefulReleaseConsumesLeaseAndResetNeverReplaysStop() {
        val a=SamsungBuds2ProAdapter(model);a.receive(status())
        val request=SamsungDailyControlRequest.FitTest(model.name,13,55,true)
        assertTrue(a.executeControl(request).accepted);a.controlWritten(request)
        assertFalse(a.executeControl(request.copy(start=false,lease=56)).accepted)
        val stop=a.prepareProtocolRelease().single()
        assertEquals(157,Wire.parseFrame(stop)!!.id);assertArrayEquals(byteArrayOf(0),Wire.parseFrame(stop)!!.payload)
        assertTrue(a.prepareProtocolRelease().isEmpty())
        a.resetProtocolSession();assertTrue(a.prepareProtocolRelease().isEmpty())
        assertFalse(a.supportsControl(request))
    }
    @Test fun transportDeadlineStopsMatchingLeaseOnlyAndOldDeadlineCannotStopReplacement() {
        val a=SamsungBuds2ProAdapter(model);a.receive(status())
        val start=SamsungDailyControlRequest.FitTest(model.name,13,71,true)
        assertTrue(a.executeControl(start).accepted)
        val effects=a.controlWritten(start)
        assertTrue(effects.any{it is AdapterEffect.ScheduleProtocolCleanup && it.key=="samsung.fit" && it.lease==71L && it.delayMs==30_000L})
        assertTrue(a.expireProtocolWorkflow("samsung.fit",72).isEmpty())
        assertEquals(1,a.expireProtocolWorkflow("samsung.fit",71).size)
        assertNull(a.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.fitLease)
        assertTrue(a.expireProtocolWorkflow("samsung.fit",71).isEmpty())
        val next=start.copy(lease=72)
        assertTrue(a.executeControl(next).accepted);a.controlWritten(next)
        assertTrue(a.expireProtocolWorkflow("samsung.fit",71).isEmpty())
        assertEquals(1,a.prepareProtocolRelease().size)
    }

}
