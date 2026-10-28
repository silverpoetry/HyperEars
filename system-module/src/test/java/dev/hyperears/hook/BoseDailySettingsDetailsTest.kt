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
class BoseDailySettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(BoseDailySettingsFeatureState(multipoint = false))
    @Test fun togglesWaitForReadbackAndStaleSessionDismisses() {
        val f = Fixture(); val details = f.open()
        val toggle = views(details).filterIsInstance<CompoundButton>().single { it.isEnabled }
        toggle.isChecked = true
        assertEquals(listOf(BoseDailyControlRequest.SetMultipoint(true)), f.sent)
        assertFalse(toggle.isChecked)
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(multipoint = true))
        f.observer!!(f.current); assertTrue(toggle.isChecked)
        f.token = "next"; toggle.isChecked = false
        assertEquals(1, f.sent.size); assertEquals(1, f.released); assertFalse(popup(details).isShowing)
    }
    @Test fun allConfirmedOptionsIssueTheirTypedRequests() {
        val f = Fixture()
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(promptsRaw = 8, multipoint = false,
            autoPause = false, cnc = BoseCncLevel(10, true), anr = 1))
        val details = f.open()
        views(details).filterIsInstance<CompoundButton>().forEach { it.isChecked = true }
        assertEquals(listOf(BoseDailyControlRequest.SetVoicePrompts(true), BoseDailyControlRequest.SetMultipoint(true),
            BoseDailyControlRequest.SetAutoPause(true)), f.sent)
        val cnc = views(details).filterIsInstance<SeekBar>().single()
        cnc.progress = 7; listener(cnc).onStopTrackingTouch(cnc)
        assertEquals(BoseDailyControlRequest.SetCncLevel(3), f.sent.last())
        assertEquals(0, cnc.progress)
        views(details).filterIsInstance<TextView>().single { it.contentDescription == "降噪档位 低" }.performClick()
        assertEquals(BoseDailyControlRequest.SetAnr(3), f.sent.last())
        details.dismiss(); assertEquals(1, f.released)
    }
    @Test fun oldEqSliderCannotWriteChangedBandDefinitionBeforeRender() {
        val f = Fixture(); val definition = BoseEqBandDefinition(0, -10, 10)
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(eq = listOf(BoseEqBand(definition, 0))))
        val details = f.open()
        val slider = views(details).filterIsInstance<SeekBar>().single { it.contentDescription == "EQ 低音" }
        slider.progress = 15; listener(slider).onStopTrackingTouch(slider)
        assertEquals(BoseDailyControlRequest.SetEqBand(definition, 5), f.sent.single())
        assertEquals(10, slider.progress)
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(eq = listOf(BoseEqBand(definition.copy(minimum = -5, maximum = 5), 0))))
        slider.progress = 11; listener(slider).onStopTrackingTouch(slider)
        assertEquals(1, f.sent.size)
        details.dismiss()
    }
    @Test fun lossOfEvidenceOwnershipAndTransportReleasesObservers() {
        listOf<(EarbudState)->EarbudState>({ it.copy(address = "other") },
            { it.copy(features = DeviceFeatureSnapshot()) },
            { it.copy(lifecycle = it.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppSpec("test.controller", "Test"))) },
            { it.copy(lifecycle = it.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)) },
            { it.copy(lifecycle = it.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)) }).forEach { change ->
            val f = Fixture(); val details = f.open()
            f.current = change(f.current); f.observer!!(f.current)
            assertEquals(1, f.released); assertFalse(popup(details).isShowing)
        }
    }
    @Test fun timeoutAndDetachDoNotRetryWritesOrLeakObservers() {
        val f = Fixture(); val details = f.open()
        views(details).filterIsInstance<CompoundButton>().single { it.isEnabled }.isChecked = true
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(1, f.sent.size)
        assertTrue(views(details).filterIsInstance<TextView>().any { it.text.contains("尚未收到确认") })
        f.activity.setContentView(View(f.activity)); details.dismiss(); details.dismiss()
        assertEquals(1, f.released)
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
        fun open() = BoseDailySettingsDetails.open(anchor, address, environment).also { shadowOf(Looper.getMainLooper()).idle() }
    }
}
