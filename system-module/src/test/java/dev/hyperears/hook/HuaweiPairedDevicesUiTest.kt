package dev.hyperears.hook
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class HuaweiPairedDevicesUiTest {
    @Test fun forgetNeedsOneConfirmationAndChangedListOrOwnerPreventsDispatch() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val adapter=EarbudAdapterRegistry.resolve(EarbudIdentity("HUAWEI FreeBuds Pro 3",true))!!
        fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        adapter.receive(hex("5a0021002b310201020301000406001122334455050109070101080101090550686f6e6576cc"))
        adapter.receive(hex("5a0022002b310201020301010406aabbccddeeff05010007010008010109064c6170746f707d4a"))
        val address="02:00:00:00:00:11"
        var state=EarbudState(adapter=adapter.snapshot(),address=address,features=adapter.runtimeState().features,
            lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        val sent=mutableListOf<ControlRequest>()
        val env=MiLinkCardEnvironment(javaClass.classLoader!!,{state},{_,request->sent+=request},{_,_->{}},{"one"})
        val details=HuaweiDailySettingsDetails.open(anchor,address,env);shadowOf(Looper.getMainLooper()).idle()
        val popup=details.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(details) as PopupWindow
        fun button(id:String)=descendants(popup.contentView).filterIsInstance<Button>().single{it.contentDescription==id}
        button("huawei.device.001122334455.FORGET").performClick();assertTrue(sent.isEmpty())
        val confirmation=button("huawei.device.confirm")
        val feature=state.features.get<HuaweiDailySettingsFeatureState>()!!
        state=state.withFeature(feature.copy(pairedDevices=feature.pairedDevices!!.copy(revision=999)))
        confirmation.performClick();assertTrue(sent.isEmpty())
        state=state.withFeature(feature);details.render(state)
        button("huawei.device.001122334455.FORGET").performClick()
        button("huawei.device.confirm").performClick();assertEquals(1,sent.size)
        assertEquals(HuaweiPairedAction.FORGET,(sent.single() as HuaweiDailyControlRequest.ManageDevice).action)
        val screen=androidx.preference.PreferenceManager(activity).createPreferenceScreen(activity)
        val rows=mutableListOf<Any>();var confirm:(()->Unit)?=null
        SettingsHuaweiPairedRows.add(SettingsPreferenceHost(javaClass.classLoader!!,activity,screen),state,address,rows::add,{state},{sent+=it},{_,yes->confirm=yes})
        val row=rows.filterIsInstance<androidx.preference.Preference>().single{it.key.endsWith(".FORGET") && it.key.contains("001122334455")}
        row.onPreferenceClickListener!!.onPreferenceClick(row);assertNotNull(confirm)
        state=state.copy(lifecycle=state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.huaweiAiLife))
        confirm!!.invoke();assertEquals(1,sent.size)
        details.dismiss();activity.finish()
    }
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
