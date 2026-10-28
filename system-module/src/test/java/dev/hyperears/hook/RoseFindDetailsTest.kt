package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec
import dev.hyperears.protocol.rose.RoseZtWireCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],manifest=Config.NONE)
class RoseFindDetailsTest {
    private class Fixture(val model:String,val zt:Boolean=false) {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val address="00:11:22:33:44:55"
        val adapter=if(zt)RoseZtAdapter(RoseZtWireCodec.Model.valueOf(model))else RoseLegacyAdapter(RoseLegacyIdentityCodec.Model.valueOf(model))
        var state=EarbudState(address=address,adapter=adapter.snapshot().let{if(zt)it.copy(presentationId=RoseZtAdapter.PRESENTATION_ID)else it},
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        var token:String?="one"
        var writes=true
        var released=0
        var observer:((EarbudState)->Unit)?=null
        val sent=mutableListOf<ControlRequest>()
        init {setIntent(null)}
        fun setIntent(intent:RoseFindWrittenIntent?) {
            state=if(zt)state.withFeature(RoseZtSettingsFeatureState(model,findAvailable=true,findIntent=intent))
                else state.withFeature(RoseLegacySettingsFeatureState(model,findAvailable=true,findIntent=intent))
        }
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request ->
            sent+=request
            if(writes) {
                val pair=when(request){is RoseLegacyControlRequest.Find->request.lease to request.target;is RoseZtControlRequest.Find->request.lease to request.target;else->null}
                pair?.let{setIntent(if(it.second==RoseFindTarget.STOP)null else RoseFindWrittenIntent(it.first,it.second));observer?.invoke(state)}
            }
        },{_,callback->observer=callback;{observer=null;released++}},{token})
        fun open()=(if(zt)RoseZtSettingsDetails.open(anchor,address,env)else RoseLegacySettingsDetails.open(anchor,address,env)).also{shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun exactEightProfilesShowOnlySourceTargetsAndNeverStartOnOpen() {
        val cases=listOf("EXPLORER" to false,"CAMBRIAN" to false,"OPENFUN" to false,"OPENLINK" to false,"SPORTFEEL" to false,"SWIFT" to false,"HEADFEEL" to true,"I5_SOURCE" to true)
        cases.forEach{(model,zt)->
            val f=Fixture(model,zt);val d=f.open()
            val expected=when(model){"CAMBRIAN","SWIFT","HEADFEEL"->setOf("HEADSET");"I5_SOURCE"->setOf("LEFT","RIGHT");else->setOf("LEFT","RIGHT","BOTH")}
            val actual=descendants(popup(d).contentView).filterIsInstance<Button>().mapNotNull{it.contentDescription?.toString()?.takeIf{it.startsWith("rose.find.")}?.removePrefix("rose.find.")}.toSet()
            assertEquals(model,expected,actual);assertTrue(f.sent.isEmpty());d.dismiss();assertTrue(f.sent.isEmpty())
        }
    }
    @Test fun successfulWriteIsOnlyLocalIntentAndCloseStopsMatchingLeaseExactlyOnce() {
        for(zt in listOf(false,true)) {
            val f=Fixture(if(zt)"HEADFEEL"else"EXPLORER",zt);val d=f.open()
            val start=button(d,if(zt)"HEADSET"else"BOTH");start.performClick();start.performClick()
            assertEquals(1,f.sent.size)
            assertTrue(descendants(popup(d).contentView).filterIsInstance<TextView>().any{it.text=="请求已写入，设备未确认"})
            d.dismiss();d.dismiss();assertEquals(2,f.sent.size)
            val first=f.sent.first();val stop=f.sent.last()
            if(zt)assertEquals((first as RoseZtControlRequest.Find).copy(target=RoseFindTarget.STOP),stop)
            else assertEquals((first as RoseLegacyControlRequest.Find).copy(target=RoseFindTarget.STOP),stop)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));assertEquals(2,f.sent.size)
        }
    }
    @Test fun manualStopConsumesOwnLeaseBeforeSynchronousObserverAndOldButtonRepeat() {
        val f=Fixture("I5_SOURCE",true);val d=f.open();button(d,"LEFT").performClick()
        val stop=button(d,"stop");stop.performClick();stop.performClick()
        assertEquals(2,f.sent.size);assertEquals(RoseFindTarget.STOP,(f.sent.last() as RoseZtControlRequest.Find).target)
        assertTrue(descendants(popup(d).contentView).any{it.contentDescription=="rose.find.RIGHT"})
        d.dismiss();assertEquals(2,f.sent.size)
    }
    @Test fun timeoutStopsAndOldTokenExternalOwnerOrReplacementLeaseNeverStopsForeignTask() {
        val f=Fixture("OPENLINK");val d=f.open();button(d,"LEFT").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));assertEquals(2,f.sent.size)
        assertEquals(RoseFindTarget.STOP,(f.sent.last() as RoseLegacyControlRequest.Find).target);d.dismiss()
        for(reason in listOf("token","owner","lease","model")) {
            val other=Fixture("I5_SOURCE",true);val panel=other.open();button(panel,"RIGHT").performClick()
            when(reason){
                "token"->other.token="new"
                "owner"->other.state=other.state.copy(lifecycle=other.state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.roseLink))
                "lease"->other.setIntent(RoseFindWrittenIntent(999,RoseFindTarget.LEFT))
                "model"->other.state=other.state.withFeature(RoseZtSettingsFeatureState("HEADFEEL",findAvailable=true,findIntent=RoseFindWrittenIntent(999,RoseFindTarget.HEADSET)))
            }
            other.observer?.invoke(other.state);panel.dismiss()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));assertEquals(reason,1,other.sent.size)
        }
    }
    @Test fun pendingWriteAndExternallyObservedIntentNeverBecomeDeviceConfirmedOrOwnedStop() {
        val f=Fixture("EXPLORER");f.writes=false;val d=f.open();button(d,"LEFT").performClick()
        assertTrue(descendants(popup(d).contentView).filterIsInstance<TextView>().any{it.text=="查找请求已提交，等待写入结果"})
        d.dismiss();assertEquals(1,f.sent.size)
        val foreign=Fixture("HEADFEEL",true);foreign.setIntent(RoseFindWrittenIntent(55,RoseFindTarget.HEADSET));val panel=foreign.open()
        assertFalse(descendants(popup(panel).contentView).any{it.contentDescription=="rose.find.stop"})
        panel.dismiss();assertTrue(foreign.sent.isEmpty())
    }
    private fun button(d:MiLinkToggleDetails,target:String)=descendants(popup(d).contentView).filterIsInstance<Button>().single{it.contentDescription=="rose.find.$target"}
    private fun popup(d:MiLinkToggleDetails)=d.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(d) as PopupWindow
    private fun descendants(view:View):Sequence<View> = sequence{yield(view);if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))}
}
