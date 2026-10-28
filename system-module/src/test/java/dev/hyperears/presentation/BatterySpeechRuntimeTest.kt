package dev.hyperears.presentation

import dev.hyperears.integration.*
import dev.hyperears.settings.*
import org.junit.Assert.*
import org.junit.Test

class BatterySpeechRuntimeTest {
    private val address="AA:BB:CC:DD:EE:01"
    private val producer=Any()
    private fun state(left:Int?=80,right:Int?=null,case:Int?=null,charging:Boolean=false)=
        EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery(left=BatteryReading(left,charging),right=BatteryReading(right,false),case=BatteryReading(case,false))))
    private fun meta(replay:Boolean=false,id:String="one",time:Long=100,admission:NativeAdmission=NativeAdmission.HYPEREARS)=
        NativePresentationMetadata(HeadsetConnection(id,time),admission,replay)
    private fun config(connect:Boolean=false)=ModuleSettings(speechMode=BatterySpeechMode.ON,speechOnConnection=connect,speechLowBattery=true)
    private class Host:BatterySpeechHost {
        var time=100L;var allowed=true;var stops=0;var guard:(()->Boolean)?=null;var finished:(()->Unit)?=null
        val spoken=mutableListOf<BatterySpeechMessage>()
        override fun now()=time
        override fun allowed(address:String,mode:BatterySpeechMode)=allowed
        override fun speak(address:String,mode:BatterySpeechMode,volume:Float,message:BatterySpeechMessage,valid:()->Boolean,finished:()->Unit):Boolean {
            if(!valid())return false
            spoken+=message;guard=valid;this.finished=finished;return true
        }
        override fun stop(){stops++;guard=null;finished=null}
        fun finish(){finished?.invoke();finished=null;guard=null}
    }
    @Test fun highSampleDoesNotConsumeThresholdAndZeroIsValidWhileUnknownCaseAndChargingAreSilent() {
        val host=Host();val runtime=BatterySpeechRuntime(host){config()}
        runtime.update(state(null,null,0),"s",meta(),producer)
        runtime.update(state(80),"s",meta(),producer)
        runtime.update(state(19,charging=true),"s",meta(),producer)
        assertTrue(host.spoken.isEmpty())
        runtime.update(state(0),"s",meta(),producer)
        assertEquals(listOf(SpeechBatteryPart.LEFT),host.spoken.single().low)
        host.finish();runtime.update(state(18,0),"s",meta(),producer)
        assertEquals(1,host.spoken.size)
    }
    @Test fun replayAndMotionNeverSpeakAndNewPhysicalConnectionResetsOpportunity() {
        val host=Host();val runtime=BatterySpeechRuntime(host){config(true)}
        runtime.update(state(10),"s",meta(replay=true),producer)
        runtime.update(state(10).withFeature(NoiseModeFeatureState(NoiseMode.ANC)),"s",meta(),producer)
        runtime.update(state(9),"s",meta(),producer)
        assertTrue(host.spoken.isEmpty())
        host.time=200;runtime.update(state(19,18),"t",meta(id="two",time=200),producer)
        assertTrue(host.spoken.single().connection);assertEquals(2,host.spoken.single().low.size)
        assertEquals("耳机已连接。左耳、右耳电量低",host.spoken.single().text())
        host.finish();runtime.update(state(18,17),"replacement",meta(id="two",time=200),producer)
        assertEquals(1,host.spoken.size)
    }
    @Test fun highReplayMayLaterCrossThresholdButSettingsDoNotSpeakCurrentLowState() {
        val host=Host();var settings=config();val runtime=BatterySpeechRuntime(host){settings}
        runtime.update(state(80),"s",meta(replay=true),producer)
        runtime.update(state(19),"s",meta(),producer);assertEquals(1,host.spoken.size)
        settings=settings.copy(speechMode=BatterySpeechMode.OFF);runtime.settingsChanged()
        assertNull(host.guard)
        settings=config(true);runtime.settingsChanged();runtime.update(state(18),"s",meta(),producer)
        assertEquals(1,host.spoken.size)
    }
    @Test fun connectionWaitsForFreshAdmissionButBlockedRouteNeverQueues() {
        val host=Host();val runtime=BatterySpeechRuntime(host){config(true)}
        runtime.update(state(),"s",meta(admission=NativeAdmission.UNKNOWN),producer)
        assertTrue(host.spoken.isEmpty())
        host.allowed=false;runtime.update(state(),"s",meta(),producer)
        host.allowed=true;runtime.update(state(),"s",meta(),producer)
        assertTrue(host.spoken.isEmpty())
        host.time=20_000;runtime.update(state(),"late",meta(id="two",time=200),producer)
        assertTrue(host.spoken.isEmpty())
    }
    @Test fun ownerSessionProducerCallConversationAndSettingsInvalidateOngoingUtterance() {
        for(reason in listOf("session","system","external","producer","disconnect","conversation","pause","settings")) {
            val host=Host();var settings=config(true);var alive=true
            val runtime=BatterySpeechRuntime(host,producerAlive={alive}){settings}
            runtime.update(state(),"s",meta(),producer)
            assertTrue(host.guard!!.invoke())
            when(reason) {
                "session"->runtime.update(state(),"new",meta(),producer)
                "system"->runtime.update(state(),"s",meta(admission=NativeAdmission.SYSTEM),producer)
                "external"->runtime.update(state().copy(lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
                    controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.andPods)),"s",meta(),producer)
                "producer"->{alive=false;runtime.update(state(),"s",meta(),producer)}
                "disconnect"->runtime.remove(address)
                "conversation"->runtime.update(state().withFeature(AppleConversationFeatureState(1,1)),"s",meta(),producer)
                "pause"->{settings=settings.copy(modulePaused=true);runtime.settingsChanged()}
                "settings"->{settings=settings.copy(speechVolume=BatterySpeechVolume.P25);runtime.settingsChanged()}
            }
            assertNull(reason,host.guard);assertEquals(reason,1,host.spoken.size)
        }
    }
    @Test fun lowAlertIsCancelledIfAnAnnouncedComponentRecoversOrStartsChargingBeforeSpeech() {
        for(recovery in listOf(state(20),state(19,charging=true),state(null))) {
            val host=Host();val runtime=BatterySpeechRuntime(host){config(true)}
            runtime.update(state(19),"s",meta(),producer)
            val initGuard=host.guard!!
            runtime.update(recovery,"s",meta(),producer)
            assertFalse(initGuard());assertNull(host.guard);assertEquals(1,host.spoken.size)
            runtime.update(state(18),"s",meta(),producer)
            assertEquals(1,host.spoken.size)
        }
    }
    @Test fun provenancePreservesTruePrivateEqualBudsAndSuppressesAggregateDuplicates() {
        val battery=EarbudBattery.fromAggregate(10).copy(case=BatteryReading(0,false))
        val aggregate=state().copy(adapter=StandardEarbudAdapter().snapshot()).withFeature(BatteryFeatureState(battery))
        assertEquals(listOf(SpeechBatteryPart.HEADSET),SpeechBatteryProjection.readings(aggregate).map{it.part})
        val private=aggregate.copy(adapter=aggregate.adapter!!.copy(batterySource=BatterySource.PRIVATE_PROTOCOL))
        assertEquals(listOf(SpeechBatteryPart.LEFT,SpeechBatteryPart.RIGHT),SpeechBatteryProjection.readings(private).map{it.part})
        assertTrue(SpeechBatteryProjection.readings(state(null,null,10)).isEmpty())
    }
}
