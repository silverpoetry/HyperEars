package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import dev.hyperears.protocol.rose.RoseZtWireCodec as Zt
import org.junit.Assert.*
import org.junit.Test

class RoseLegacyFindActionTest {
    private fun identity(prefix:String)=prefix.chunked(2).map{it.toInt(16)}.let {
        EarbudIdentity("renamed",true,manufacturerData=mapOf((it[0] or (it[1] shl 8)) to it.drop(2)))
    }
    private fun rs(type:Int,value:Int):ByteArray {
        val body=byteArrayOf(0xdd.toByte(),0,2,type.toByte(),value.toByte())
        return body+byteArrayOf(body.sumOf{it.toInt() and 255}.toByte(),0xaa.toByte())
    }
    private fun zt(group:Int,command:Int,vararg payload:Int):ByteArray {
        val body=listOf(9,255,0,0,0,group,command,10+payload.size,0)+payload.toList()
        return (body+(body.sum() and 255)).map(Int::toByte).toByteArray()
    }
    private fun local(a:EarbudAdapter)=a.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.findIntent ?:
        a.runtimeState().features.get<RoseZtSettingsFeatureState>()?.findIntent
    private fun request(a:EarbudAdapter,lease:Long,target:RoseFindTarget):ControlRequest =
        a.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.let{RoseLegacyControlRequest.Find(it.model,lease,target)} ?:
        RoseZtControlRequest.Find(a.runtimeState().features.get<RoseZtSettingsFeatureState>()!!.model,lease,target)
    @Test fun allEightExactProfilesGrantOnlyAfterOrdinaryReportAndOwnOnlyWrittenStarts() {
        val cases=Model.entries.filter{it.findTargets().isNotEmpty()}.map { model ->
            Triple(requireNotNull(EarbudAdapterRegistry.resolve(identity(model.prefixes.first()))),
                if(model.f8)byteArrayOf(0,0x27,2,0,3,8,1,1)else rs(14,1),RoseLegacyControlRequest.Find(model.name,41,model.findTargets().first()) as ControlRequest)
        }+listOf(Zt.Model.HEADFEEL,Zt.Model.I5_SOURCE).map { model ->
            Triple(requireNotNull(EarbudAdapterRegistry.resolve(identity(model.prefixes.first()))),zt(6,3,0),RoseZtControlRequest.Find(model.name,41,model.findTargets().first()) as ControlRequest)
        }
        assertEquals(8,cases.size)
        for((a,report,start) in cases) {
            val target=when(start){is RoseLegacyControlRequest.Find->start.target;is RoseZtControlRequest.Find->start.target;else->error("test case")}
            assertFalse(a.executeControl(start).accepted)
            assertNull(local(a));assertTrue(a.prepareProtocolRelease().isEmpty())
            a.receive(report.copyOf(report.size-1));assertNull(local(a));assertFalse(a.executeControl(start).accepted)
            a.resetProtocolSession();a.receive(report)
            assertTrue(a.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.findAvailable == true ||
                a.runtimeState().features.get<RoseZtSettingsFeatureState>()?.findAvailable == true)
            assertEquals(start,ControlRequestTransport.decode(ControlRequestTransport.encode(start)))
            val encoded=a.executeControl(start);assertTrue(a.id,encoded.accepted);assertTrue(encoded.readback.isEmpty())
            assertNull(local(a));assertTrue(a.prepareProtocolRelease().isEmpty())
            val timeout=a.controlWritten(start).filterIsInstance<AdapterEffect.ScheduleProtocolCleanup>().single()
            assertEquals(30_000L,timeout.delayMs);assertEquals(RoseFindWrittenIntent(41,target),local(a))
            assertFalse(a.executeControl(request(a,42,target)).accepted)
            assertFalse(a.executeControl(request(a,42,RoseFindTarget.STOP)).accepted)
            assertTrue(a.expireProtocolWorkflow(timeout.key,timeout.lease+1).isEmpty())
            assertEquals(1,a.expireProtocolWorkflow(timeout.key,timeout.lease).size);assertNull(local(a))
            assertTrue(a.prepareProtocolRelease().isEmpty())
            val second=request(a,42,target);assertTrue(a.executeControl(second).accepted);a.controlWritten(second)
            assertFalse(a.executeControl(request(a,41,RoseFindTarget.STOP)).accepted)
            val stop=request(a,42,RoseFindTarget.STOP);assertTrue(a.executeControl(stop).accepted);assertNotNull(local(a))
            assertTrue(a.controlWritten(stop).any{it is AdapterEffect.CancelProtocolCleanup});assertNull(local(a))
            assertTrue(a.executeControl(second).accepted);a.controlWritten(second)
            assertEquals(1,a.prepareProtocolRelease().size);assertNull(local(a));assertTrue(a.prepareProtocolRelease().isEmpty())
            a.resetProtocolSession();assertFalse(a.executeControl(second).accepted)
        }
    }
    @Test fun unsupportedProfilesAndNameOnlyI5CannotPromoteFindAndStartDoesNotProbe() {
        for(model in Model.entries.filter{it.findTargets().isEmpty()}) {
            val a=RoseLegacyAdapter(model)
            a.receive(if(model.f8)byteArrayOf(0,0x27,2,0,4,4,2,0,1)else rs(14,1))
            assertFalse(a.supportsControl(RoseLegacyControlRequest.Find(model.name,1,RoseFindTarget.LEFT)))
        }
        val old=RoseEarfreeI5Adapter();old.receive(zt(6,2,1,0,0,0))
        assertFalse(old.supportsControl(RoseZtControlRequest.Find(Zt.Model.I5_SOURCE.name,1,RoseFindTarget.LEFT)))
        val i3=RoseZtAdapter(Zt.Model.I3);i3.receive(zt(6,3,0))
        assertFalse(i3.supportsControl(RoseZtControlRequest.Find(Zt.Model.I3.name,1,RoseFindTarget.LEFT)))
        val i5=requireNotNull(EarbudAdapterRegistry.resolve(identity(Zt.Model.I5_SOURCE.prefixes.first())))
        i5.receive(zt(14,2,0,0))
        assertFalse(i5.supportsControl(RoseZtControlRequest.Find(Zt.Model.I5_SOURCE.name,1,RoseFindTarget.LEFT)))
        i5.receive(zt(6,3,0))
        assertFalse(i5.supportsControl(RoseZtControlRequest.Find("HEADFEEL",1,RoseFindTarget.LEFT)))
        assertFalse(i5.supportsControl(RoseZtControlRequest.Find(Zt.Model.I5_SOURCE.name,1,RoseFindTarget.BOTH)))
        assertTrue(i5.protocolSession.initialReadCommands().none{it.size>6 && it[5].toInt()==14 && it[6].toInt()==2})
    }
    @Test fun i5TerminalReportCancelsOnlyItsWrittenIntentAndOtherRepliesDoNotPretendStopped() {
        val a=requireNotNull(EarbudAdapterRegistry.resolve(identity(Zt.Model.I5_SOURCE.prefixes.first())))
        a.receive(zt(6,3,0));val start=request(a,77,RoseFindTarget.LEFT)
        a.executeControl(start);val timeout=a.controlWritten(start).filterIsInstance<AdapterEffect.ScheduleProtocolCleanup>().single()
        for(payload in listOf(listOf(0,1),listOf(1,0),listOf(2,0),listOf(3,0))) {
            assertFalse(a.receive(zt(14,2,*payload.toIntArray())).effects.any{it is AdapterEffect.CancelProtocolCleanup})
            assertNotNull(local(a))
        }
        a.receive(zt(6,3,1));assertNotNull(local(a))
        assertTrue(a.receive(zt(14,2,0,0)).effects.any{it is AdapterEffect.CancelProtocolCleanup})
        assertNull(local(a));assertTrue(a.expireProtocolWorkflow(timeout.key,timeout.lease).isEmpty())
        a.executeControl(start);a.controlWritten(start);a.resetProtocolSession()
        assertTrue(a.prepareProtocolRelease().isEmpty())
    }
}
