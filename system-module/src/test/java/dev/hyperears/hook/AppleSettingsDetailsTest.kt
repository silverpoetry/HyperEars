package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.PopupWindow
import android.widget.SeekBar
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
class AppleSettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(AppleSettingsFeatureState(oneBudAnc = false, chimeVolume = 50))

    @Test fun gesturesWaitForReadbackAndUseFreshStateAndSession() {
        val fixture = Fixture()
        val details = fixture.open()
        val content = popup(details).contentView
        val toggle = descendants(content).filterIsInstance<CompoundButton>().first { it.visibility == View.VISIBLE && it.isEnabled }
        toggle.isChecked = true
        assertEquals(listOf(AppleControlRequest.SetOneBudAnc(true)), fixture.sent)
        assertFalse(toggle.isChecked)
        fixture.current = fixture.current.withFeature(AppleSettingsFeatureState(oneBudAnc = true, chimeVolume = 50))
        fixture.observer!!(fixture.current)
        assertTrue(toggle.isChecked)
        fixture.token = "next"
        toggle.isChecked = false
        assertEquals(1, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
        assertFalse(popup(details).isShowing)
    }

    @Test fun changedAddressOwnershipHandshakeAndTransportDismissImmediately() {
        listOf<(EarbudState) -> EarbudState>(
            { it.copy(address = "00:11:22:33:44:66") },
            { it.copy(lifecycle = it.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppSpec("test.controller", "Test"))) },
            { it.copy(lifecycle = it.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)) },
            { it.copy(lifecycle = it.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)) },
            { it.copy(lifecycle = it.lifecycle.copy(systemProfile = SystemProfileState.DISCONNECTED)) },
        ).forEach { change ->
            val fixture = Fixture()
            val details = fixture.open()
            fixture.current = change(fixture.current)
            fixture.observer!!(fixture.current)
            assertEquals(1, fixture.unsubscribed)
            assertFalse(popup(details).isShowing)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test fun sliderBoundsAndKeyboardGestureWaitForReadback() {
        val fixture = Fixture()
        val details = fixture.open()
        val slider = descendants(popup(details).contentView).filterIsInstance<SeekBar>()
            .first { it.contentDescription == "提示音音量" }
        assertEquals(15, slider.min)
        assertEquals(100, slider.max)
        assertEquals(50, slider.progress)
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
        listener.onProgressChanged(slider, 70, true)
        assertEquals(listOf(AppleControlRequest.SetChimeVolume(70)), fixture.sent)
        assertEquals(50, slider.progress)
        fixture.current = fixture.current.withFeature(AppleSettingsFeatureState(oneBudAnc = false, chimeVolume = 70))
        fixture.observer!!(fixture.current)
        assertEquals(70, slider.progress)
        listener.onProgressChanged(slider, 70, true)
        assertEquals(1, fixture.sent.size)
        details.dismiss()
    }

    @Test fun constructorFailureAfterSubscriptionReleasesObserverAndReturnsInertDetails() {
        val fixture = Fixture()
        val listenersBefore = attachListenerCount(fixture.anchor)
        val environment = fixture.environment.copy(stateProvider = { error("synthetic state provider failure") })
        val details = AppleSettingsDetails.open(fixture.anchor, address, environment)
        assertEquals(1, fixture.unsubscribed)
        assertNull(fixture.observer)
        assertEquals(listenersBefore, attachListenerCount(fixture.anchor))
        details.render(fixture.current)
        details.dismiss()
        details.dismiss()
        fixture.activity.setContentView(View(fixture.activity))
        assertEquals(1, fixture.unsubscribed)
        assertTrue(fixture.sent.isEmpty())
    }

    @Test fun throwingObserverRegistrationReturnsInertDetailsWithoutLeavingAnchorListener() {
        val fixture = Fixture()
        val listenersBefore = attachListenerCount(fixture.anchor)
        val environment = fixture.environment.copy(observeState = { _, _ ->
            error("synthetic observer registration failure")
        })
        val details = AppleSettingsDetails.open(fixture.anchor, address, environment)
        assertNull(fixture.observer)
        assertEquals(listenersBefore, attachListenerCount(fixture.anchor))
        details.render(fixture.current)
        details.dismiss()
        fixture.activity.setContentView(View(fixture.activity))
        assertEquals(0, fixture.unsubscribed)
        assertTrue(fixture.sent.isEmpty())
    }

    @Test fun repeatedOpenCloseAndLossOfEvidenceReleaseObservers() {
        val fixture = Fixture()
        repeat(3) {
            val details = fixture.open()
            details.dismiss()
            details.dismiss()
        }
        assertEquals(3, fixture.unsubscribed)
        val details = fixture.open()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot())
        fixture.observer!!(fixture.current)
        assertFalse(popup(details).isShowing)
        assertEquals(4, fixture.unsubscribed)
    }

    @Test fun detachedAnchorAndShowFailureReleaseObserver() {
        val fixture = Fixture()
        val details = fixture.open()
        fixture.activity.setContentView(View(fixture.activity))
        assertEquals(1, fixture.unsubscribed)
        assertFalse(popup(details).isShowing)
        val failure = AppleSettingsDetails.open(View(fixture.activity), address, fixture.environment)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixture.unsubscribed)
        failure.dismiss()
        assertEquals(2, fixture.unsubscribed)
    }

    @Test fun behaviorOnlyReportsOpenTheDetailsPanel() {
        val fixture = Fixture()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot())
            .withFeature(AppleWearFeatureState(AppleWearPosition.IN_EAR, AppleWearPosition.IN_CASE))
        val details = fixture.open()
        assertTrue(popup(details).isShowing)
        assertTrue(descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
            .any { it.text.contains("主耳：佩戴中") })
        details.dismiss()
    }

    @Test fun behaviorControlsSendTypedRequestsAndRestoreAcceptedReadback() {
        val fixture = Fixture()
        fixture.current = fixture.current
            .withFeature(AppleGestureSettingsFeatureState(leftLongPress = AppleLongPressAction.NOISE_CONTROL,
                rightLongPress = AppleLongPressAction.DIGITAL_ASSISTANT, listeningCycleMask = 6))
            .withFeature(AppleListeningModeFeatureState(AppleListeningMode.ANC,
                setOf(AppleListeningMode.ANC, AppleListeningMode.TRANSPARENCY)))
            .withFeature(AppleEqualizerFeatureState(2, 40, 50, 60))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        views.first { it.contentDescription == "左耳长按：语音助手" }.performClick()
        assertEquals(AppleControlRequest.SetLongPress(AppleBud.LEFT, AppleLongPressAction.DIGITAL_ASSISTANT), fixture.sent.last())
        val cycle = views.filterIsInstance<CompoundButton>().first { it.contentDescription == "长按循环：降噪" }
        cycle.isChecked = false
        assertEquals(AppleControlRequest.SetListeningCycle(4), fixture.sent.last())
        assertTrue(cycle.isChecked)
        val low = views.filterIsInstance<SeekBar>().first { it.contentDescription == "低频" }
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(low, "mOnSeekBarChangeListener")
        listener.onProgressChanged(low, 80, true)
        assertEquals(AppleControlRequest.SetEqualizer(2, 80, 50, 60), fixture.sent.last())
        assertEquals(40, low.progress)
        fixture.current = fixture.current.withFeature(AppleEqualizerFeatureState(2, 80, 50, 60))
        fixture.observer!!(fixture.current)
        assertEquals(80, low.progress)
        fixture.token = "changed"
        listener.onProgressChanged(low, 90, true)
        assertEquals(3, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
    }

    private fun attachListenerCount(view: View): Int {
        val info = org.robolectric.util.ReflectionHelpers.getField<Any?>(view, "mListenerInfo") ?: return 0
        return org.robolectric.util.ReflectionHelpers.getField<List<*>?>(info, "mOnAttachStateChangeListeners")?.size ?: 0
    }

    private fun popup(details: MiLinkToggleDetails): PopupWindow = details.javaClass.getDeclaredField("popup").let {
        it.isAccessible = true
        it.get(details) as PopupWindow
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private inner class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also { activity.setContentView(it) }
        var current = state()
        var token: String? = "session"
        val sent = mutableListOf<ControlRequest>()
        var observer: ((EarbudState) -> Unit)? = null
        var unsubscribed = 0
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { current }, { _, request -> sent += request },
            { _, callback -> observer = callback; { unsubscribed++; observer = null } }, { token })
        fun open(): MiLinkToggleDetails = AppleSettingsDetails.open(anchor, address, environment).also {
            shadowOf(Looper.getMainLooper()).idle()
            it.render(current)
        }
    }
}
