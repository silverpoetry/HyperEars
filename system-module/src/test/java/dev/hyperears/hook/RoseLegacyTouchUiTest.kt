package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RoseLegacyTouchUiTest {
    @Test fun mappingAloneHasEntryAndOldCallbacksPinFullSnapshotAndOwner() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val adapter=RoseLegacyAdapter(Model.OPENLINK)
        val mapping=List(16){0}
        val raw=(listOf(0xdd,0,17,1)+mapping).map(Int::toByte).toByteArray()
        adapter.receive(raw+byteArrayOf(raw.sumOf{it.toInt()and 255}.toByte(),0xaa.toByte()))
        val address="02:00:00:00:00:11"
        var state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>()
        var token="one"
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request->sent+=request},{_,_->{}},{token})
        assertTrue(RoseLegacySettingsPolicy.available(state,address))
        val details=RoseLegacySettingsDetails.open(anchor,address,env)
        shadowOf(Looper.getMainLooper()).idle()
        val popup=details.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(details) as PopupWindow
        val choices=descendants(popup.contentView).filterIsInstance<RadioButton>().toList()
        assertEquals(36,choices.size)
        val action=choices.single{it.contentDescription=="rose.legacy.touch.1.1"}
        action.performClick()
        assertEquals(RoseLegacyControlRequest.SetTouch(Model.OPENLINK.name,mapping,1,1),sent.single())
        val feature=state.features.get<RoseLegacySettingsFeatureState>()!!
        state=state.withFeature(feature.copy(touch=mapping.toMutableList().apply{this[15]=2}))
        action.performClick();assertEquals(1,sent.size)
        state=state.withFeature(feature)
        token="two";action.performClick();assertEquals(1,sent.size);assertFalse(popup.isShowing)
        val request=sent.single() as RoseLegacyControlRequest.SetTouch
        state=state.copy(lifecycle=state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.roseLink))
        assertNull(RoseLegacySettingsPolicy.request(state,address,request))
        details.dismiss();activity.finish()
    }
    @Test fun openFeelReportedNoneHasReadonlyCaptionInBothSurfaces() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val adapter=RoseLegacyAdapter(Model.OPENFEEL)
        val body=(listOf(0xdd,0,17,1)+List(16){0}).map(Int::toByte).toByteArray()
        adapter.receive(body+byteArrayOf(body.sumOf{it.toInt()and 255}.toByte(),0xaa.toByte()))
        val address="02:00:00:00:00:11"
        val state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,_->fail("readonly value sent")},{_,_->{}},{"one"})
        val details=RoseLegacySettingsDetails.open(anchor,address,env)
        shadowOf(Looper.getMainLooper()).idle()
        val popup=details.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(details) as PopupWindow
        assertEquals(6,descendants(popup.contentView).filterIsInstance<TextView>().count{it.text=="当前：无功能（耳机回报）"})
        assertFalse(descendants(popup.contentView).filterIsInstance<RadioButton>().any{it.text=="无功能"})
        val screen=androidx.preference.PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val rows=mutableListOf<Any>()
        SettingsLegacyRows.add(host,state,address,rows::add,{state},{fail("readonly value sent")},{_,_->})
        val choices=rows.filterIsInstance<androidx.preference.ListPreference>()
        assertEquals(6,choices.size)
        assertTrue(choices.all{it.summary=="当前：无功能（耳机回报）" && "0" !in it.entryValues.map(CharSequence::toString)})
        choices.first().callChangeListener("0")
        details.dismiss();activity.finish()
    }
    private fun descendants(v:View):Sequence<View> = sequence {yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
