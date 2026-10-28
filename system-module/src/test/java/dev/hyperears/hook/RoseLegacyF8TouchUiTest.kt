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
class RoseLegacyF8TouchUiTest {
    @Test fun fullEightChoicesUseF8LabelsAndUnknownReportedValueIsReadonly() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val raw="00 27 02 00 1A 05 18 01 01 A1 02 01 B5 03 01 C6 04 01 D3 05 01 E4 06 01 F7 07 01 A9 08 01 B2"
            .split(" ").map{it.toInt(16).toByte()}.toByteArray()
        val adapter=RoseLegacyAdapter(Model.EXPLORER);adapter.receive(raw)
        val address="02:00:00:00:00:11"
        var state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>()
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request->sent+=request},{_,_->{}},{"one"})
        val details=RoseLegacySettingsDetails.open(anchor,address,env)
        shadowOf(Looper.getMainLooper()).idle()
        val popup=details.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(details) as PopupWindow
        assertEquals(64,descendants(popup.contentView).filterIsInstance<RadioButton>().count())
        assertTrue(descendants(popup.contentView).filterIsInstance<TextView>().any{it.text=="当前：耳机功能 1（耳机回报）"})
        val action=descendants(popup.contentView).filterIsInstance<RadioButton>().single{it.contentDescription=="rose.legacy.touch.4.7"}
        assertEquals("播放 / 暂停",action.text.toString());action.performClick()
        assertEquals(4,(sent.single() as RoseLegacyControlRequest.SetTouch).slot)
        val screen=androidx.preference.PreferenceManager(activity).createPreferenceScreen(activity)
        val rows=mutableListOf<Any>()
        SettingsLegacyRows.add(SettingsPreferenceHost(javaClass.classLoader!!,activity,screen),state,address,rows::add,{state},{sent+=it},{_,_->})
        val first=rows.filterIsInstance<androidx.preference.ListPreference>().first()
        assertEquals("当前：耳机功能 1（耳机回报）",first.summary)
        assertFalse(first.entryValues.map(CharSequence::toString).contains("1"))
        first.callChangeListener("1");assertEquals(1,sent.size)
        state=state.withFeature(state.features.get<RoseLegacySettingsFeatureState>()!!.copy(touch=raw.drop(7).map{it.toInt()and 255}.toMutableList().apply{this[0]=99}))
        action.performClick();assertEquals(1,sent.size)
        details.dismiss();activity.finish()
    }
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
