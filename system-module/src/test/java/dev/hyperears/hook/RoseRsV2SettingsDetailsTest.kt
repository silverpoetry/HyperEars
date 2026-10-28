package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RoseRsV2SettingsDetailsTest {
    private class Fixture {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor=View(activity).also(activity::setContentView)
        val address="02:00:00:00:00:11"
        val adapter=RoseRsV2Adapter(RoseRsV2IdentityCodec.Model.OPENFEEL_L).apply {
            receive(byteArrayOf(0xDD.toByte(),0,2,0x0E,0,0xED.toByte(),0xAA.toByte()))
        }
        var state=EarbudState(adapter=adapter.snapshot(),address=address,lifecycle=DeviceLifecycle(
            systemProfile=SystemProfileState.CONNECTED,privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED))
            .withFeature(RoseRsV2SettingsFeatureState("OPENFEEL_L",eq=0,game=false,audio=0,findLeft=false,findRight=false))
        var token:String?="one";var failure=false;var observer:((EarbudState)->Unit)?=null;var released=0
        val sent=mutableListOf<ControlRequest>()
        val environment=MiLinkCardEnvironment(javaClass.classLoader!!,{check(!failure);state},{_,request->sent+=request},
            {_,callback->observer=callback;{observer=null;released++}},{token})
        fun open()=RoseRsV2SettingsDetails.open(anchor,address,environment).also {shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun popupDepthIsDisabledOutsideConfirmedModeAndWritesSourceLowFive() {
        val f=Fixture();f.state=f.state.withFeature(RoseRsV2SettingsFeatureState("ZIRCON",ancLevel=1)).withFeature(NoiseModeFeatureState(NoiseMode.OFF))
        val d=f.open();val body=popup(d).contentView
        assertFalse(descendants(body).filterIsInstance<RadioButton>().single {it.text=="低"}.isEnabled)
        f.state=f.state.withFeature(NoiseModeFeatureState(NoiseMode.ANC));d.render(f.state)
        val low=descendants(body).filterIsInstance<RadioButton>().single {it.text=="低"}
        assertTrue(low.isEnabled);low.performClick()
        assertEquals(RoseRsV2ControlRequest.SetNoiseLevel("ZIRCON",false,5),f.sent.single())
        assertEquals(1,f.state.features.get<RoseRsV2SettingsFeatureState>()!!.ancLevel)
        f.state=f.state.withFeature(NoiseModeFeatureState(NoiseMode.OFF));low.performClick()
        assertEquals(1,f.sent.size);d.dismiss()
    }
    @Test fun languageOnlyReportOffersNativeDetailsWithoutInventingWear() {
        val f=Fixture();f.state=f.state.withFeature(RoseRsV2SettingsFeatureState("HYACINTH",language=0))
        val d=f.open();val body=popup(d).contentView
        assertFalse(descendants(body).filterIsInstance<TextView>().any {it.text=="佩戴检测"})
        descendants(body).filterIsInstance<RadioButton>().single {it.text=="English"}.performClick()
        assertEquals(RoseRsV2ControlRequest.SetLanguage("HYACINTH",1),f.sent.single())
        assertEquals(0,f.state.features.get<RoseRsV2SettingsFeatureState>()!!.language)
        f.state=f.state.withFeature(RoseRsV2SettingsFeatureState("MAGICIAN",language=0))
        descendants(body).filterIsInstance<RadioButton>().single {it.text=="English"}.performClick()
        assertEquals(1,f.sent.size)
        d.dismiss()
    }
    @Test fun hscClassicEqSelectionUsesSemanticThreeNotListPosition() {
        val f=Fixture()
        f.state=f.state.withFeature(RoseRsV2SettingsFeatureState(RoseRsV2IdentityCodec.Model.CERAMICS_U_HSC_2710.name,eq=0,gain=1))
        val details=f.open();val popup=popup(details)
        descendants(popup.contentView).filterIsInstance<RadioButton>().single {it.text=="经典调音"}.performClick()
        assertEquals(RoseRsV2ControlRequest.SetEqualizer(3),f.sent.single())
        assertFalse(descendants(popup.contentView).filterIsInstance<RadioButton>().any {it.text=="75 dB"})
        assertEquals(0,f.state.features.get<RoseRsV2SettingsFeatureState>()!!.eq)
        details.dismiss()
    }
    @Test fun gainAndProtectionDetailsWaitForReportsAndRejectChangedModel() {
        val f=Fixture()
        f.state=f.state.withFeature(RoseRsV2SettingsFeatureState(RoseRsV2IdentityCodec.Model.OPENFEEL_L.name,gain=0,hearingProtection=1))
        val details=f.open();val popup=popup(details)
        val gain=descendants(popup.contentView).filterIsInstance<RadioButton>().single {it.text=="档位 2"}
        gain.performClick()
        assertEquals(RoseRsV2ControlRequest.SetGain(RoseRsV2IdentityCodec.Model.OPENFEEL_L.name,2),f.sent.single())
        assertEquals(0,f.state.features.get<RoseRsV2SettingsFeatureState>()!!.gain)
        val hearing=descendants(popup.contentView).filterIsInstance<RadioButton>().single {it.text=="100 dB"}
        f.state=f.state.withFeature(RoseRsV2SettingsFeatureState(RoseRsV2IdentityCodec.Model.ASTRO_X.name,gain=0,hearingProtection=1))
        hearing.performClick();assertEquals(1,f.sent.size)
        details.dismiss();assertEquals(1,f.released)
    }
    @Test fun audioRequiresExplicitConfirmationAndRechecksOwnerBeforeWrite() {
        val f=Fixture();val details=f.open();val popup=popup(details)
        descendants(popup.contentView).filterIsInstance<RadioButton>().single{it.text=="LDAC"}.performClick()
        assertTrue(f.sent.isEmpty())
        button(popup,"确认更改音频模式").performClick()
        assertEquals(RoseRsV2ControlRequest.SetAudio(1),f.sent.single())
        assertEquals(0,f.state.features.get<RoseRsV2SettingsFeatureState>()!!.audio)
        descendants(popup.contentView).filterIsInstance<RadioButton>().single{it.text=="LDAC"}.performClick()
        f.token="two";button(popup,"确认更改音频模式").performClick()
        assertEquals(1,f.sent.size);assertFalse(popup.isShowing)
    }
    @Test fun findLeaseStopsAtTimeoutAndDoesNotStopAnotherOwner() {
        val f=Fixture();val details=f.open();val popup=popup(details)
        button(popup,"查找双耳").performClick();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(listOf(RoseRsV2ControlRequest.Find(3),RoseRsV2ControlRequest.Find(4)),f.sent)
        button(popup,"查找左耳").performClick()
        f.state=f.state.copy(lifecycle=f.state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.roseLink))
        f.observer!!(f.state);assertEquals(3,f.sent.size);assertFalse(popup.isShowing)
    }
    @Test @Config(qualifiers="night") fun nightPaletteAndProviderExceptionContainment() {
        val f=Fixture();val details=f.open();val popup=popup(details)
        val body=(popup.contentView as ScrollView).getChildAt(0) as LinearLayout
        assertEquals(android.graphics.Color.WHITE,(body.getChildAt(0) as TextView).currentTextColor)
        f.failure=true;button(popup,"查找左耳").performClick()
        assertFalse(popup.isShowing);assertEquals(1,f.released);assertTrue(f.sent.isEmpty())
    }
    private fun button(p:PopupWindow,label:String)=descendants(p.contentView).filterIsInstance<Button>().single{it.text==label}
    private fun popup(d:MiLinkToggleDetails)=d.javaClass.getDeclaredField("popup").apply{isAccessible=true}.get(d) as PopupWindow
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
}
