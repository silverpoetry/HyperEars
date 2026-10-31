package dev.hyperears.hook

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class TozoNativeEqDetailsTest {
    private val address="00:11:22:33:44:55"
    private val baseline=TozoEqualizerFeatureState(List(10){TozoEqBand(100+it*100,it,3)})
    private fun state()=EarbudState(address=address,adapter=TozoNc20ProAdapter().snapshot(),
        lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        .withFeature(baseline)
    private fun sliders(view:View):List<SeekBar> = if(view is SeekBar) listOf(view)
        else if(view is ViewGroup) (0 until view.childCount).flatMap{sliders(view.getChildAt(it))} else emptyList()
    @Test fun dragOnlyEditsDraftAndApplySendsOneBaselineCheckedVector() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val sent=mutableListOf<ControlRequest>()
        val current=state()
        val environment=MiLinkCardEnvironment(javaClass.classLoader!!,{current},{_,r->sent+=r},{_,_->{}},{"token"})
        val editor=requireNotNull(TozoNativeEqDetails.open(anchor,address,environment))
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val controls=sliders(dialog.window!!.decorView)
        assertEquals(10,controls.size)
        val bar=controls.first()
        assertEquals(100,bar.max)
        val listener=SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply{isAccessible=true}.get(bar) as SeekBar.OnSeekBarChangeListener
        listener.onProgressChanged(bar,67,true)
        editor.render(current)
        assertTrue(sent.isEmpty())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val request=sent.single() as TozoControlRequest.SetEqualizer
        assertEquals(17,request.gainsTenths.first())
        assertEquals(baseline,request.expected)
        assertEquals(baseline,current.features.get<TozoEqualizerFeatureState>())
    }
    @Test fun requestRejectsChangedBaselineWrongDeviceAndOutOfRangeValue() {
        val values=List(10){0}
        assertNotNull(TozoNativeEqDetails.request(state(),address,baseline,values))
        assertNull(TozoNativeEqDetails.request(state(),"AA:BB:CC:DD:EE:FF",baseline,values))
        assertNull(TozoNativeEqDetails.request(state(),address,baseline,List(10){51}))
        val changed=baseline.copy(bands=baseline.bands.map{it.copy(qValue=4)})
        assertNull(TozoNativeEqDetails.request(state().withFeature(changed),address,baseline,values))
    }
    @Test fun cancelSendsNothing() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val sent=mutableListOf<ControlRequest>()
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state()},{_,r->sent+=r},{_,_->{}},{"token"})
        requireNotNull(TozoNativeEqDetails.open(View(activity),address,env))
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty())
    }
}
