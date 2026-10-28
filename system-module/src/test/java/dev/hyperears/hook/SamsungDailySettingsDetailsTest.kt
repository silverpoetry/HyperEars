package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SamsungDailySettingsDetailsTest {
    private class Fixture(model: SamsungBudsCodec.Model = SamsungBudsCodec.Model.BUDS4_PRO) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val address = "00:11:22:33:44:55"
        val adapter = SamsungBuds2ProAdapter(model).apply {
            receive(SamsungBudsCodec.packet(0x61, ByteArray(50).apply { this[0] = 13; this[9] = 3; this[11] = 0x22 }))
        }
        var state = EarbudState(adapter = adapter.snapshot(), address = address, lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(SamsungDailySettingsFeatureState(model.name,13,true,2,customEqAvailable = model == SamsungBudsCodec.Model.BUDS4_PRO))
        var token: String? = "one"
        var providerFails = false
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val sent = mutableListOf<ControlRequest>()
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { check(!providerFails); state },
            { _, request -> sent += request }, { _, callback -> observer = callback; { observer = null; released++ } }, { token })
        fun open(): MiLinkToggleDetails = SamsungDailySettingsDetails.open(anchor,address,environment).also {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun curveRequiresExplicitApplyAndStaleModeIsRejectedBeforeObserver() {
        val f = Fixture(); val details = f.open(); val popup = popup(details)
        val slider = descendants(popup.contentView).filterIsInstance<SeekBar>().first()
        ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider,"mOnSeekBarChangeListener").onProgressChanged(slider,17,true)
        assertTrue(f.sent.isEmpty())
        button(popup,"应用自定义曲线").performClick()
        assertEquals(SamsungDailyControlRequest.SetCustomEqualizer(listOf(7)+List(8){0},"BUDS4_PRO",13,2,true),f.sent.single())
        f.state = f.state.withFeature(f.state.features.get<SamsungDailySettingsFeatureState>()!!.copy(equalizerPreset=3))
        button(popup,"应用自定义曲线").performClick()
        assertEquals(1,f.sent.size); details.dismiss()
    }
    @Test fun ringLeaseStopsAtSixtySecondsAndCloseButNeverAfterSessionOrOwnershipLoss() {
        val f=Fixture(); var details=f.open(); var popup=popup(details)
        assertTrue(f.sent.isEmpty())
        button(popup,"开始响铃").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(listOf(SamsungDailyControlRequest.Find(true),SamsungDailyControlRequest.Find(false)),f.sent)
        button(popup,"开始响铃").performClick(); details.dismiss()
        assertEquals(SamsungDailyControlRequest.Find(false),f.sent.last())
        details=f.open(); popup=popup(details); button(popup,"开始响铃").performClick()
        val count=f.sent.size; f.token="two"; details.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61)); assertEquals(count,f.sent.size)
        details=f.open(); popup=popup(details); button(popup,"开始响铃").performClick()
        val before=f.sent.size
        f.state=f.state.copy(lifecycle=f.state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.galaxyWearable))
        f.observer!!(f.state); assertEquals(before,f.sent.size); assertFalse(popup.isShowing)
    }
    @Test fun providerFailureClosesWithoutEscapingAndWrongAddressRejects() {
        val f=Fixture(); val details=f.open(); val popup=popup(details)
        f.providerFails=true; button(popup,"开始响铃").performClick()
        assertTrue(f.sent.isEmpty()); assertFalse(popup.isShowing); assertEquals(1,f.released)
        f.providerFails=false
        assertFalse(SamsungDailySettingsPolicy.available(f.state.copy(address="different"),f.address))
    }
    @Test @Config(qualifiers="night")
    fun nightColorsAndNoCurveConfirmationClaim() {
        val f=Fixture(); val details=f.open(); val popup=popup(details)
        val body=(popup.contentView as ScrollView).getChildAt(0) as LinearLayout
        assertEquals(0xff242424.toInt(),(body.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(android.graphics.Color.WHITE,(body.getChildAt(0) as TextView).currentTextColor)
        assertTrue(descendants(body).filterIsInstance<TextView>().any { it.text.contains("不能可靠回报曲线") })
        details.dismiss()
    }
    @Test fun originalVoiceDetectControlRemainsAvailableAndRechecksSupport() {
        val f=Fixture(SamsungBudsCodec.Model.BUDS2_PRO)
        val existing=f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertTrue(existing.voiceDetectSupported)
        f.state=f.state.withFeature(existing)
        val details=f.open(); val popup=popup(details)
        descendants(popup.contentView).filterIsInstance<Switch>().single { it.text=="语音检测" }.isChecked=true
        assertEquals(SamsungControlRequest.SetVoiceDetect(true),f.sent.single())
        val row=descendants(popup.contentView).filterIsInstance<Switch>().single { it.text=="语音检测" }
        f.state=f.state.withFeature(existing.copy(voiceDetectSupported=false))
        row.isChecked=true
        assertEquals(1,f.sent.size); details.dismiss()
    }
    @Test fun actualOptionalReportsPublishBothFeaturesAndReachNativeControls() {
        for (model in listOf(SamsungBudsCodec.Model.BUDS2_PRO,SamsungBudsCodec.Model.BUDS2,SamsungBudsCodec.Model.BUDS_FE)) {
            val f=Fixture(model)
            assertTrue(model.name,f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.optionalSettingsSupported)
            assertNotNull(model.name,f.adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>())
            assertEquals(SamsungDailyPresentationIds.SETTINGS,f.adapter.miLinkCardPresentationId)
            f.state=f.state.copy(features=f.adapter.runtimeState().features)
            val details=f.open();val popup=popup(details)
            descendants(popup.contentView).filterIsInstance<Switch>().single { it.contentDescription == "通话侧音" }.isChecked=true
            assertEquals(listOf(SamsungControlRequest.SetSidetone(true)),f.sent)
            details.dismiss()
        }
    }
    @Test fun fourReportedOptionalTogglesStayFalseUntilDeviceReportAndRouteExistingRequests() {
        val f = Fixture(SamsungBudsCodec.Model.BUDS2_PRO)
        val reported = f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.copy(
            noiseControlsWithOneEarbud=false, seamlessConnectionEnabled=false, sidetoneEnabled=false, extraHighAmbientEnabled=false)
        assertTrue(reported.optionalSettingsSupported); assertTrue(reported.extraHighAmbientSupported)
        f.state = f.state.withFeature(reported)
        val details = f.open(); val popup = popup(details)
        val cases = listOf("单耳噪声控制" to SamsungControlRequest.SetNoiseControlsWithOneEarbud(true),
            "无缝连接" to SamsungControlRequest.SetSeamlessConnection(true),
            "通话侧音" to SamsungControlRequest.SetSidetone(true),
            "增强通透音量" to SamsungControlRequest.SetExtraHighAmbient(true))
        cases.forEach { (label,request) ->
            fun toggle() = descendants(popup.contentView).filterIsInstance<Switch>().single { it.contentDescription == label }
            assertFalse(toggle().isChecked)
            toggle().isChecked = true
            assertEquals(request,f.sent.last())
            assertFalse(toggle().isChecked)
        }
        f.state = f.state.withFeature(reported.copy(noiseControlsWithOneEarbud=true,seamlessConnectionEnabled=true,
            sidetoneEnabled=true,extraHighAmbientEnabled=true))
        f.observer!!(f.state)
        cases.forEach { (label,_) -> assertTrue(descendants(popup.contentView).filterIsInstance<Switch>()
            .single { it.contentDescription == label }.isChecked) }
        details.dismiss()
    }
    @Test fun optionalSettingsWithoutReportsOrUnsupportedByProfileAreAbsent() {
        val f = Fixture(SamsungBudsCodec.Model.BUDS4_PRO)
        val details = f.open(); val popup = popup(details)
        val labels = setOf("单耳噪声控制","无缝连接","通话侧音","增强通透音量")
        assertFalse(descendants(popup.contentView).any { it.contentDescription?.toString().orEmpty() in labels })
        val unsupported = f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
        assertFalse(unsupported.optionalSettingsSupported); assertFalse(unsupported.extraHighAmbientSupported)
        f.state = f.state.withFeature(unsupported); f.observer!!(f.state)
        assertFalse(descendants(popup.contentView).any { it.contentDescription?.toString().orEmpty() in labels })
        assertNull(SamsungDailySettingsPolicy.request(f.state,f.address,SamsungControlRequest.SetSidetone(true)))
        details.dismiss()
    }
    @Test fun missingExtraHighFieldDoesNotHideOtherConfirmedSettingsAndOldSessionCannotWrite() {
        val f=Fixture(SamsungBudsCodec.Model.BUDS2_PRO)
        val reported=f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.copy(
            optionalSettingsSupported=true,extraHighAmbientSupported=false,sidetoneEnabled=false)
        f.state=f.state.withFeature(reported)
        val details=f.open();val popup=popup(details)
        assertFalse(descendants(popup.contentView).any { it.contentDescription == "增强通透音量" })
        val sidetone=descendants(popup.contentView).filterIsInstance<Switch>().single { it.contentDescription == "通话侧音" }
        f.token="replacement-session"
        sidetone.isChecked=true
        assertTrue(f.sent.isEmpty());assertFalse(popup.isShowing);assertEquals(1,f.released)
        details.dismiss()
    }
    @Test fun optionalToggleRechecksMissingSupportAndSessionOwnershipBeforeSending() {
        val f=Fixture(SamsungBudsCodec.Model.BUDS2_PRO)
        val reported=f.adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.copy(sidetoneEnabled=false)
        f.state=f.state.withFeature(reported)
        val details=f.open();val popup=popup(details)
        val stale=descendants(popup.contentView).filterIsInstance<Switch>().single { it.contentDescription == "通话侧音" }
        f.state=f.state.withFeature(reported.copy(optionalSettingsSupported=false))
        stale.isChecked=true
        assertTrue(f.sent.isEmpty())
        f.state=f.state.withFeature(reported);f.observer!!(f.state)
        val owned=descendants(popup.contentView).filterIsInstance<Switch>().single { it.contentDescription == "通话侧音" }
        f.state=f.state.copy(lifecycle=f.state.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,
            externalControlApp=ControlAppCatalog.galaxyWearable))
        owned.isChecked=true
        assertTrue(f.sent.isEmpty());assertFalse(popup.isShowing);assertEquals(1,f.released)
        details.dismiss()
    }

    @Test fun conversationTimeoutUsesRealReportAndRechecksRevokedField() {
        val f=Fixture(SamsungBudsCodec.Model.BUDS2_PRO)
        f.state=f.state.copy(features=f.adapter.runtimeState().features)
        val details=f.open();val popup=popup(details)
        val fifteen=button(popup,"15 秒") as RadioButton
        assertFalse(fifteen.isChecked)
        fifteen.performClick()
        assertEquals(listOf(SamsungDailyControlRequest.SetConversationTimeout(15)),f.sent)
        assertTrue((button(popup,"5 秒") as RadioButton).isChecked)
        f.state=f.state.withFeature(f.state.features.get<SamsungDailySettingsFeatureState>()!!.copy(conversationTimeoutSeconds=null))
        (button(popup,"10 秒") as RadioButton).performClick()
        assertEquals(1,f.sent.size)
        assertFalse(descendants(popup.contentView).filterIsInstance<Button>().any { it.text=="10 秒" })
        details.dismiss()
    }

    private fun button(popup: PopupWindow,text:String)=descendants(popup.contentView).filterIsInstance<Button>().single { it.text==text }
    private fun popup(details:MiLinkToggleDetails)=details.javaClass.getDeclaredField("popup").apply { isAccessible=true }.get(details) as PopupWindow
    private fun descendants(view:View):Sequence<View> = sequence { yield(view); if(view is ViewGroup) for(i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i))) }
}
