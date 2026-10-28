package dev.hyperears.hook
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RoseRsV2TouchSnapshotUiTest {
    @Test fun inlineAndPopupPreserveUnknownCurrentAndRejectOldMappingCallbacks() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val adapter=RoseRsV2Adapter(Model.KY01)
        adapter.receive("DD 00 11 01 14 C8 01 00 02 01 03 02 04 03 11 04 12 05 13 06 20 AA".split(" ").map{it.toInt(16).toByte()}.toByteArray())
        val address="02:00:00:00:00:11"
        var state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>();var token="one"
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request->sent+=request},{_,_->{}},{token})
        val details=RoseRsV2SettingsDetails.open(anchor,address,env)
        shadowOf(Looper.getMainLooper()).idle()
        val popup=details.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(details) as PopupWindow
        assertTrue(descendants(popup.contentView).filterIsInstance<TextView>().any{it.text=="当前：耳机功能 200（耳机回报）"})
        val buttons=descendants(popup.contentView).filterIsInstance<RadioButton>().toList()
        assertEquals(72,buttons.size)
        val screen=androidx.preference.PreferenceManager(activity).createPreferenceScreen(activity)
        val rows=mutableListOf<Any>()
        SettingsRoseRsV2Rows.add(SettingsPreferenceHost(javaClass.classLoader!!,activity,screen),state,address,rows::add,{state},{sent+=it})
        val prefs=rows.filterIsInstance<androidx.preference.ListPreference>()
        assertEquals(8,prefs.size)
        assertEquals("当前：耳机功能 200（耳机回报）",prefs.last().summary)
        assertFalse(prefs.last().entryValues.map(CharSequence::toString).contains("200"))
        prefs.first().callChangeListener("8")
        val request=sent.single() as RoseRsV2ControlRequest.SetTouch
        assertEquals(Model.KY01.name,request.expectedModel)
        val feature=state.features.get<RoseRsV2SettingsFeatureState>()!!
        state=state.withFeature(feature.copy(touchRaw=feature.touchRaw!!.toMutableList().apply{this[1]=201}))
        prefs.first().callChangeListener("7");buttons[7].performClick();assertEquals(1,sent.size)
        state=state.withFeature(feature);token="two";buttons[7].performClick();assertEquals(1,sent.size)
        details.dismiss();activity.finish()
    }
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
