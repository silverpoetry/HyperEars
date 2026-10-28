package dev.hyperears.widget

import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test

class BatteryWidgetProjectionTest {
    private fun state(percent:Int?,revision:Long=1)=EarbudState(address="aa:bb:cc:dd:ee:01",deviceName="Buds",revision=revision,
        lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED)).withFeature(BatteryFeatureState(EarbudBattery(left=BatteryReading(percent,false))))
    private val metadata=NativePresentationMetadata(HeadsetConnection("physical",10),NativeAdmission.HYPEREARS)
    @Test fun motionRevisionsDoNotEmitAndReplayNeverRefreshesObservedBatteryTime() {
        val projector=BatteryWidgetProjector();val producer=Any()
        assertEquals(100L,projector.accept(state(0),"session",metadata,producer,false,100)!!.recordedAtMillis)
        for(revision in 2L..2000L)assertNull(projector.accept(state(0,revision).withFeature(NoiseModeFeatureState(if(revision%2==0L)NoiseMode.ANC else NoiseMode.OFF)),"session",metadata,producer,false,200))
        assertEquals(100L,projector.accept(state(0,2000),"session",metadata,producer,true,900)!!.recordedAtMillis)
        assertNull(projector.accept(state(99,1),"session",metadata,producer,false,999))
        assertEquals(1000L,projector.accept(state(1,2001),"session",metadata,producer,false,1000)!!.recordedAtMillis)
        val cold=BatteryWidgetProjector()
        assertNull(cold.accept(state(80),"s",metadata,producer,true,3000)!!.recordedAtMillis)
        assertNull(cold.accept(state(80,2),"s",metadata,producer,false,4000))
    }
    @Test fun disconnectedStateRetainsOnlyHistoricalBatteryAndSystemStillErasesIt() {
        val projector=BatteryWidgetProjector();val producer=Any()
        projector.accept(state(60),"s",metadata,producer,false,100)
        val ended=state(null,2).copy(lifecycle=DeviceLifecycle())
        val history=projector.accept(ended,"s",metadata,producer,false,200)!!
        assertEquals(BatteryWidgetStatus.DISCONNECTED,history.status)
        assertEquals(60,history.battery.left.percent);assertEquals(100L,history.recordedAtMillis)
        assertFalse(projector.accept(ended.copy(revision=3),"s",metadata.copy(admission=NativeAdmission.SYSTEM),producer,false,300)!!.hasBattery)
    }
    @Test fun aggregateDoesNotInventBudsAndSystemAdmissionClearsPrivateValues() {
        val aggregate=state(70).copy(adapter=StandardEarbudAdapter().snapshot()).withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(80)))
        val projected=BatteryWidgetProjection.from(aggregate,metadata,100,true)!!
        assertEquals(80,projected.battery.overall.percent);assertNull(projected.battery.left.percent)
        val projector=BatteryWidgetProjector();val p=Any()
        projector.accept(state(70),"s",metadata,p,false,100)
        val system=projector.accept(state(70,2),"s",metadata.copy(admission=NativeAdmission.SYSTEM),p,false,200)!!
        assertFalse(system.hasBattery);assertNull(system.recordedAtMillis)
        assertEquals(BatteryWidgetStatus.SYSTEM,system.status)
    }
    @Test fun privateComponentsSurviveEqualOverallAndAggregateRetainsIndependentCase() {
        val readings=EarbudBattery(BatteryReading(80,false),BatteryReading(80,false),BatteryReading(50,true),BatteryReading(80,false))
        val adapter=StandardEarbudAdapter().snapshot().copy(batterySource=BatterySource.PRIVATE_PROTOCOL,formFactor=HeadsetFormFactor.HEADPHONES)
        val privateState=state(80).copy(adapter=adapter).withFeature(BatteryFeatureState(readings))
        assertEquals(readings,BatteryWidgetProjection.from(privateState,metadata,100,true)!!.battery)
        val aggregate=privateState.copy(adapter=adapter.copy(batterySource=BatterySource.SYSTEM_AGGREGATE))
        val display=BatteryWidgetProjection.from(aggregate,metadata,100,true)!!.battery
        assertFalse(display.left.available);assertFalse(display.right.available)
        assertEquals(80,display.overall.percent);assertEquals(50,display.case.percent)
    }
    @Test fun automaticSelectionUsesConnectionOrderNotMotionAndFixedDoesNotFallBack() {
        val first=BatteryWidgetProjection.from(state(40),metadata,100,true)!!
        val second=first.copy(address="AA:BB:CC:DD:EE:02",connectionOrder=20,recordedAtMillis=50)
        assertEquals(second,BatteryWidgetProjection.select(listOf(first,second),null))
        assertEquals(first,BatteryWidgetProjection.select(listOf(first,second),first.address.lowercase()))
        assertNull(BatteryWidgetProjection.select(listOf(first),second.address))
        val projector=BatteryWidgetProjector()
        for(i in 0..70)projector.accept(state(40).copy(address="AA:BB:CC:DD:EE:"+i.toString(16).padStart(2,'0')),"s",metadata,Any(),false,100)
        assertEquals(BatteryWidgetProjection.MAX_DEVICES,projector.size())
    }
}
