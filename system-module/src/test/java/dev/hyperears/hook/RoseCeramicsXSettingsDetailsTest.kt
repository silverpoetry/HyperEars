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
@Config(sdk = [35], manifest = Config.NONE)
class RoseCeramicsXSettingsDetailsTest {
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val address = "02:00:00:00:00:11"
        val adapter = RoseLuliXAdapter().apply { receive(byteArrayOf(0, 0x27, 2, 0, 3, 11, 1, 2)) }
        var state = EarbudState(adapter = adapter.snapshot(), address = address, features = adapter.runtimeState().features,
            lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
        var token: String? = "one"
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val sent = mutableListOf<ControlRequest>()
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { state }, { _, request -> sent += request },
            { _, callback -> observer = callback; { observer = null; released++ } }, { token })
        fun open() = RoseCeramicsXSettingsDetails.open(anchor, address, environment).also { shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun audioRequiresConfirmationAndRepeatedOldButtonCannotSendAgain() {
        val f = Fixture(); val popup = popup(f.open())
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "双设备连接" }.performClick()
        assertTrue(f.sent.isEmpty())
        val confirm = button(popup)
        confirm.performClick(); confirm.performClick()
        assertEquals(listOf(RoseCeramicsXControlRequest.SetValue(RoseCeramicsXSetting.AUDIO, 0)), f.sent)
        assertEquals(2, f.state.features.get<RoseCeramicsXSettingsFeatureState>()!!.values[RoseCeramicsXSetting.AUDIO])
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "LDAC" }.performClick()
        f.token = "new"
        button(popup).performClick()
        assertEquals(1, f.sent.size); assertFalse(popup.isShowing); assertEquals(1, f.released)
    }
    @Test fun unseenSettingsAndWrongModeDepthAreAbsentAndExternalOwnerCloses() {
        val f = Fixture()
        f.state = f.state.withFeature(RoseCeramicsXSettingsFeatureState(mapOf(RoseCeramicsXSetting.GAME to 0,
            RoseCeramicsXSetting.ANC_LEVEL to 1)))
        val popup = popup(f.open())
        val labels = descendants(popup.contentView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue("游戏模式" in labels); assertFalse("降噪强度" in labels); assertFalse("触控开关" in labels)
        val on = descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "开启" }
        f.state = f.state.copy(lifecycle = f.state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.roseLink))
        on.performClick()
        assertTrue(f.sent.isEmpty()); assertFalse(popup.isShowing)
    }
    @Test fun changedAudioReportInvalidatesUnsentConfirmation() {
        val f = Fixture(); val popup = popup(f.open())
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "双设备连接" }.performClick()
        val confirm = button(popup)
        f.state = f.state.withFeature(RoseCeramicsXSettingsFeatureState(mapOf(RoseCeramicsXSetting.AUDIO to 1)))
        confirm.performClick()
        assertTrue(f.sent.isEmpty())
    }
    private fun button(p: PopupWindow) = descendants(p.contentView).filterIsInstance<Button>().single { it.text == "确认更改音频模式" }
    private fun popup(d: MiLinkToggleDetails) = d.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(d) as PopupWindow
    private fun descendants(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i))) }
}
