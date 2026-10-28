package dev.hyperears.widget

import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BatteryWidgetStateStoreTest {
    private class Producer:Binder() {
        var alive=true
        var failLink=false
        val recipients=mutableListOf<IBinder.DeathRecipient>()
        override fun isBinderAlive()=alive
        override fun linkToDeath(recipient:IBinder.DeathRecipient,flags:Int){recipients+=recipient;if(failLink)error("dead during link")}
        override fun unlinkToDeath(recipient:IBinder.DeathRecipient,flags:Int)=recipients.remove(recipient)
        fun die(){alive=false;recipients.toList().forEach{it.binderDied()}}
    }
    private fun event(producer:IBinder,revision:Long=1,token:String="a",connection:String="one",order:Long=10,
        admission:NativeAdmission=NativeAdmission.HYPEREARS,active:Boolean=true):Intent {
        val state=EarbudState(address="AA:BB:CC:DD:EE:01",deviceName="Buds",revision=revision,
            lifecycle=DeviceLifecycle(systemProfile=if(active)SystemProfileState.CONNECTED else SystemProfileState.DISCONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery(left=BatteryReading(40,true))))
        return NativePresentationContract.attach(ModuleContract.stateChanged(state,token,ModuleContract.MODULE_PACKAGE),
            NativePresentationMetadata(HeadsetConnection(connection,order),admission),producer)
            .putExtra(BatteryWidgetPublisher.RECORDED_AT,100L)
    }
    @Test fun staleSessionsScopesAndDeadProducersCannotReplaceAcceptedState() {
        val p=Producer();val updates=mutableListOf<List<BatteryWidgetRecord>>()
        val store=BatteryWidgetStateStore {updates+=it}
        assertTrue(store.accept(event(p,2)))
        assertFalse(store.accept(event(p,1)))
        assertTrue(store.accept(event(p,1,"b","two",20)))
        assertFalse(store.accept(event(p,99,"a","one",10,active=false)))
        p.die();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.records().single().verified)
        assertEquals(BatteryWidgetStatus.UNCONFIRMED,store.records().single().status)
        assertFalse(store.accept(event(p,100,"c","three",30)))
        val next=Producer();assertTrue(store.accept(event(next,1,"d","four",40)))
        assertFalse(store.accept(event(p,200,"e","five",50)))
        store.close();assertTrue(next.recipients.isEmpty())
    }
    @Test fun failedOrRejectedProvisionalProducerNeverRetiresAcceptedToken() {
        val old=Producer();val store=BatteryWidgetStateStore {}
        assertTrue(store.accept(event(old,2)))
        val broken=Producer().apply{failLink=true}
        assertFalse(store.accept(event(broken,100,"new","two",20)))
        assertTrue(broken.recipients.isEmpty())
        assertTrue(store.accept(event(old,3)))
        assertTrue(store.accept(event(old,1,"b","two",20)))
        val rejected=Producer()
        assertFalse(store.accept(event(rejected,200,"a","three",30)))
        assertTrue(rejected.recipients.isEmpty())
        assertEquals(1,old.recipients.size)
        assertTrue(store.accept(event(old,2,"b","two",20)))
        store.close()
    }
    @Test fun systemClaimErasesCachedPrivateBatteryAndCannotBeDowngradedInSameScope() {
        val p=Producer();val store=BatteryWidgetStateStore {}
        assertTrue(store.accept(event(p)))
        assertTrue(store.records().single().hasBattery)
        assertTrue(store.accept(event(p,2,admission=NativeAdmission.SYSTEM)))
        assertFalse(store.records().single().hasBattery)
        assertNull(store.records().single().recordedAtMillis)
        assertTrue(store.accept(event(p,3)))
        assertFalse(store.records().single().hasBattery)
        store.close()
    }
}
