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
class RoseZtSettingsDetailsTest {
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val address = "02:00:00:00:00:11"
        val adapter = RoseZtAdapter(dev.hyperears.protocol.rose.RoseZtWireCodec.Model.HEADFEEL).apply { receive(report(8, 1, 0)) }
        var state = EarbudState(adapter = adapter.snapshot(), address = address, features = adapter.runtimeState().features,
            lifecycle = DeviceLifecycle(SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED))
        var token: String? = "one"
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val sent = mutableListOf<ControlRequest>()
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { state }, { _, request -> sent += request },
            { _, callback -> observer = callback; { observer = null; released++ } }, { token })
        fun open() = RoseZtSettingsDetails.open(anchor, address, environment).also { shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun promptOnlyHeadfeelPanelUsesReportedLevelAndBlocksOldOwnerCallback() {
        val f=Fixture();f.adapter.receive(report(14,6,5))
        f.state=f.state.copy(features=f.adapter.runtimeState().features)
        val d=f.open();val body=popup(d).contentView
        val level=descendants(body).filterIsInstance<RadioButton>().single {it.text=="级别 2"}
        level.performClick()
        assertEquals(RoseZtControlRequest.SetValue("HEADFEEL","PROMPT",2),f.sent.single())
        assertEquals(5,f.state.features.get<RoseZtSettingsFeatureState>()!!.values["PROMPT"])
        f.token="changed";level.performClick();assertEquals(1,f.sent.size);d.dismiss()
    }
    @Test fun audioRequiresConfirmationAndRepeatedOldButtonCannotSendAgain() {
        val f = Fixture(); val popup = popup(f.open())
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "LHDC" }.performClick()
        assertTrue(f.sent.isEmpty())
        val confirm = button(popup)
        confirm.performClick(); confirm.performClick()
        assertEquals(listOf(RoseZtControlRequest.SetValue("HEADFEEL", "AUDIO", 2)), f.sent)
        assertEquals(0, f.state.features.get<RoseZtSettingsFeatureState>()!!.values["AUDIO"])
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "LDAC" }.performClick()
        f.token = "new"
        button(popup).performClick()
        assertEquals(1, f.sent.size); assertFalse(popup.isShowing); assertEquals(1, f.released)
    }
    @Test fun externalOwnerRejectsOldGameControl() {
        val f = Fixture()
        f.state = f.state.withFeature(RoseZtSettingsFeatureState("HEADFEEL", mapOf("GAME" to 1)))
        val popup = popup(f.open())
        val on = descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "开启" }
        f.state = f.state.copy(lifecycle = f.state.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.roseLink))
        on.performClick()
        assertTrue(f.sent.isEmpty()); assertFalse(popup.isShowing)
    }
    @Test fun changedAudioReportInvalidatesUnsentConfirmation() {
        val f = Fixture(); val popup = popup(f.open())
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "LHDC" }.performClick()
        val confirm = button(popup)
        f.state = f.state.withFeature(RoseZtSettingsFeatureState("HEADFEEL", mapOf("AUDIO" to 1)))
        confirm.performClick()
        assertTrue(f.sent.isEmpty())
    }
    companion object {
        private fun report(group: Int, command: Int, value: Int): ByteArray {
            val body = listOf(9, 255, 0, 0, 1, group, command, 11, 0, value)
            return (body + (body.sum() and 255)).map(Int::toByte).toByteArray()
        }
    }
    private fun button(p: PopupWindow) = descendants(p.contentView).filterIsInstance<Button>().single { it.text == "确认更改音频模式" }
    private fun popup(d: MiLinkToggleDetails) = d.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(d) as PopupWindow
    private fun descendants(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i))) }
}
