package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class HuaweiPreferenceControlsTest {
    private fun report(command:Int,value:Int,field:Int=2)=Wire.packet(command,listOf(field to byteArrayOf(value.toByte())))
    @Test fun exactExecutableHandlerMembershipQualifiesReadQueriesAndResponses() {
        val cases=listOf(HuaweiDailyProfile.PRO3 to (true to true),HuaweiDailyProfile.BUDS5I to (true to true),
            HuaweiDailyProfile.BUDS6I to (true to true),HuaweiDailyProfile.FREECLIP2 to (true to true),
            HuaweiDailyProfile.LACE_PRO2 to (true to true),HuaweiDailyProfile.PRO2 to (false to true),
            HuaweiDailyProfile.PRO to (false to false),HuaweiDailyProfile.BUDS4I to (false to false),HuaweiDailyProfile.STUDIO to (false to false))
        cases.forEach { (profile,expected) ->
            val session=HuaweiDailySettingsSession(profile)
            val queries=session.queries().map { Wire.parseFrame(it)!! }
            assertEquals(expected.first,queries.any{it.command==0x2B6C && it.params.keys==setOf(2)})
            assertEquals(expected.second,queries.any{it.command==0x2BA3 && it.params.keys==setOf(1)})
            session.offer(Wire.parseFrame(report(0x2B6C,1))!!);session.offer(Wire.parseFrame(report(0x2BA3,0))!!)
            assertEquals(expected.first,session.state.lowLatency!=null);assertEquals(expected.second,session.state.preferSoundQuality!=null)
        }
    }
    @Test fun latencyWaitsOneSecondAfterWriteAndNeverPublishesEchoOrRequestedValue() {
        val adapter=HuaweiFreebudsPro3Adapter();val request=HuaweiDailyControlRequest.SetLowLatency(true)
        assertFalse(adapter.supportsControl(request));adapter.receive(report(0x2B6C,0))
        val result=adapter.executeControl(request);assertTrue(result.accepted);assertTrue(result.readback.isEmpty())
        assertEquals(false,adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.lowLatency)
        adapter.receive(report(0x2B6C,1,1));assertEquals(false,adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.lowLatency)
        assertEquals(listOf(AdapterEffect.RequestState(HuaweiDailySettingsFeatureState.LOW_LATENCY_QUERY,1000)),adapter.controlWritten(request))
        val delayed=adapter.queryState(HuaweiDailySettingsFeatureState.LOW_LATENCY_QUERY).map {Wire.parseFrame(it)!!}.single()
        assertEquals(0x2B6C,delayed.command);assertEquals(setOf(2),delayed.params.keys)
        adapter.receive(report(0x2B6C,1));assertEquals(true,adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.lowLatency)
        adapter.resetProtocolSession();assertFalse(adapter.supportsControl(request))
    }
    @Test fun qualityUsesAuthoritativeReadAndIpcRoundTripsBothPreferences() {
        val adapter=HuaweiFreebudsPro3Adapter()
        val quality=HuaweiDailyControlRequest.SetSoundQualityPreference(true)
        adapter.receive(report(0x2BA3,0));val result=adapter.executeControl(quality)
        assertEquals(0x2BA2,Wire.parseFrame(result.commands.single())!!.command)
        assertEquals(0x2BA3,Wire.parseFrame(result.readback.single())!!.command)
        adapter.receive(report(0x2BA2,1));assertEquals(false,adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.preferSoundQuality)
        adapter.receive(report(0x2BA3,1));assertEquals(true,adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.preferSoundQuality)
        listOf(quality,HuaweiDailyControlRequest.SetLowLatency(true)).forEach { assertEquals(it,ControlRequestTransport.decode(ControlRequestTransport.encode(it))) }
        val unsupported=EarbudAdapterRegistry.resolve(EarbudIdentity("HUAWEI FreeBuds 4i",true))!!
        unsupported.receive(report(0x2B6C,1));unsupported.receive(report(0x2BA3,1));assertFalse(unsupported.supportsControl(quality))
    }
}
