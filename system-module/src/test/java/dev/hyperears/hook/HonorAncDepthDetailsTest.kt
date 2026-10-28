package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
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
class HonorAncDepthDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, adapter = HonorX5sProAdapter().snapshot().copy(
        presentationId = HonorX5sProAdapter.PRESENTATION_ID, supportedNoiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)),
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
        .withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        .withFeature(HonorAncDepthFeatureState(HonorAncDepth.SMART, HonorAncDepth.entries.toSet()))
    @Test fun depthClickKeepsReportSelectionAndStaleOffCannotEnableAnc() {
        val f = Fixture(); val details = f.open()
        val deep = views(popup(details).contentView).single { it.contentDescription == "降噪强度 深度" }
        val smart = views(popup(details).contentView).single { it.contentDescription == "降噪强度 智能" }
        assertTrue(smart.isSelected)
        deep.performClick()
        assertEquals(listOf(HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP)), f.sent)
        assertTrue(smart.isSelected); assertFalse(deep.isSelected)
        f.current = f.current.withFeature(HonorAncDepthFeatureState(HonorAncDepth.DEEP, HonorAncDepth.entries.toSet()))
        f.observer!!(f.current); assertTrue(deep.isSelected)
        f.current = f.current.withFeature(NoiseModeFeatureState(NoiseMode.OFF))
            .withFeature(HonorAncDepthFeatureState(null, HonorAncDepth.entries.toSet()))
        smart.performClick() // Provider changed before the observer.
        assertEquals(1, f.sent.size)
        assertFalse(smart.isEnabled)
        details.dismiss(); assertEquals(1, f.released)
    }
    @Test fun unsupportedCurrentAndReducedChoicesDoNotInventDepth() {
        val f = Fixture()
        f.current = f.current.withFeature(HonorAncDepthFeatureState(HonorAncDepth.SMART, setOf(HonorAncDepth.LIGHT)))
        val details = f.open()
        val light = views(popup(details).contentView).single { it.contentDescription == "降噪强度 轻度" }
        assertFalse(light.isEnabled)
        light.performClick(); assertTrue(f.sent.isEmpty())
        details.dismiss()
    }
    @Test fun sessionOwnerAdapterAndProviderChangesCleanUpWithoutSending() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.token = "new" },
            { it.current = it.current.copy(address = "other") },
            { it.current = it.current.copy(adapter = it.current.adapter!!.copy(presentationId = null)) },
            { it.current = it.current.copy(lifecycle = it.current.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = ControlAppSpec("test", "Test"))) },
            { it.failProvider = true },
        )) {
            val f = Fixture(); val details = f.open()
            val item = views(popup(details).contentView).single { it.contentDescription == "降噪强度 深度" }
            change(f); item.performClick()
            assertTrue(f.sent.isEmpty()); assertEquals(1, f.released); assertFalse(popup(details).isShowing)
            details.dismiss(); assertEquals(1, f.released)
        }
    }
    @Test fun detachAndConstructorFailureReleaseSubscription() {
        val f = Fixture(); val details = f.open()
        f.activity.setContentView(View(f.activity))
        assertEquals(1, f.released); assertFalse(popup(details).isShowing)
        val broken = Fixture(); broken.failProvider = true
        broken.open(); assertEquals(1, broken.released)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun popup(details: MiLinkToggleDetails) = details.javaClass.getDeclaredField("popup").let { it.isAccessible = true; it.get(details) as PopupWindow }
    private inner class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        var current = state()
        var token: String? = "session"
        var failProvider = false
        val sent = mutableListOf<ControlRequest>()
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val env = MiLinkCardEnvironment(javaClass.classLoader!!, { if (failProvider) error("provider") else current },
            { _, request -> sent += request }, { _, listener -> observer = listener; { released++; observer = null } }, { token })
        fun open() = HonorAncDepthDetails.open(anchor, address, env).also { shadowOf(Looper.getMainLooper()).idle() }
    }
}
