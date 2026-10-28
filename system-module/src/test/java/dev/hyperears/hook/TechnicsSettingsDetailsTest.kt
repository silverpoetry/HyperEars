package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
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
class TechnicsSettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(TechnicsLevelsFeatureState(NoiseMode.ANC, 80, 23))
    @Test fun slidersPreserveReportsAndRejectStaleModeBeforeObserverRuns() {
        val f = Fixture(); val details = f.open()
        val anc = views(details).filterIsInstance<SeekBar>().single { it.contentDescription == "降噪强度" }
        val ambient = views(details).filterIsInstance<SeekBar>().single { it.contentDescription == "通透强度" }
        assertTrue(anc.isEnabled); assertFalse(ambient.isEnabled)
        anc.progress = 31; listener(anc).onStopTrackingTouch(anc)
        assertEquals(listOf(TechnicsControlRequest.SetLevel(false, 31, NoiseMode.ANC)), f.sent)
        assertEquals(80, anc.progress)
        f.current = f.current.withFeature(TechnicsLevelsFeatureState(NoiseMode.TRANSPARENCY, 80, 23))
        anc.progress = 40; listener(anc).onStopTrackingTouch(anc)
        assertEquals(1, f.sent.size)
        f.observer!!(f.current)
        assertFalse(anc.isEnabled); assertTrue(ambient.isEnabled)
        ambient.progress = 44; listener(ambient).onStopTrackingTouch(ambient)
        assertEquals(TechnicsControlRequest.SetLevel(true, 44, NoiseMode.TRANSPARENCY), f.sent.last())
        f.token = "changed"; listener(ambient).onStopTrackingTouch(ambient)
        assertEquals(2, f.sent.size); assertEquals(1, f.released)
        assertFalse(popup(details).isShowing)
    }
    @Test fun ownershipLossClosesDetailsAndOldGesturesSendNothing() {
        val f = Fixture(); val details = f.open()
        val slider = views(details).filterIsInstance<SeekBar>().first()
        f.current = f.current.copy(lifecycle = f.current.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = ControlAppCatalog.technicsAudioConnect))
        f.observer!!(f.current)
        listener(slider).onStopTrackingTouch(slider)
        assertTrue(f.sent.isEmpty()); assertEquals(1, f.released)
        details.dismiss(); assertEquals(1, f.released)
    }
    private fun listener(slider: SeekBar) = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
    private fun views(details: MiLinkToggleDetails) = children(popup(details).contentView)
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun popup(details: MiLinkToggleDetails) = details.javaClass.getDeclaredField("popup").let { it.isAccessible = true; it.get(details) as PopupWindow }
    private inner class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        var current = state(); var token: String? = "session"
        var released = 0; var observer: ((EarbudState)->Unit)? = null
        val sent = mutableListOf<ControlRequest>()
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { current }, { _, request -> sent += request },
            { _, callback -> observer = callback; { released++; observer = null } }, { token })
        fun open() = TechnicsSettingsDetails.open(anchor, address, environment).also { shadowOf(Looper.getMainLooper()).idle() }
    }
}
