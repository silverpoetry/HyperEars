package dev.hyperears.hook
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class OppoFitWorkflowTest {
    @Test fun explicitStartTimeoutAndOwnershipLossCannotSendAcrossLease() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val feature=OppoFitFeatureState(0x061410,1,true,true,true)
        var state=EarbudState().withFeature(feature)
        var current=true
        val sent=mutableListOf<ControlRequest>()
        val environment=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request->sent+=request},{_,_->{}},{"token"})
        val workflow=OppoFitWorkflow(activity,"AA",environment){current}
        workflow.render(state);assertTrue(sent.isEmpty())
        fun button(id:String)=descendants(workflow.view).filterIsInstance<Button>().single{it.contentDescription==id}
        button("oppo.fit.start").performClick();assertTrue(sent.single() is OppoFitControlRequest.Start)
        val start=sent.single() as OppoFitControlRequest.Start
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(OppoFitControlRequest.Stop(feature.productId,start.lease),sent.last())
        button("oppo.fit.start").performClick();val size=sent.size
        current=false;workflow.close();assertEquals(size,sent.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));assertEquals(size,sent.size)
        activity.finish()
    }
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
