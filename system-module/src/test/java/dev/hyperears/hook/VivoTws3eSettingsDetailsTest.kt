package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RadioButton
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
class VivoTws3eSettingsDetailsTest {
    @Test fun controlsUseAcceptedStateAndDismissOnSessionChange() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity); activity.setContentView(anchor)
        val address = "00:11:22:33:44:55"
        val adapter = VivoTws3eAdapter()
        var state = EarbudState(adapter = adapter.snapshot(), address = address, lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(VivoTws3eFeatureState(deepX = VivoDeepX.BASS, wearDetection = true))
        var token = "one"
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val sent = mutableListOf<ControlRequest>()
        var providerFails = false
        val environment = MiLinkCardEnvironment(
            javaClass.classLoader!!, { check(!providerFails); state }, { _, request -> sent += request },
            { _, callback -> observer = callback; { observer = null; released++ } }, { token },
        )
        val details = VivoTws3eSettingsDetails.open(anchor, address, environment)
        shadowOf(Looper.getMainLooper()).idle()
        val popup = details.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(details) as PopupWindow
        val buttons = descendants(popup.contentView).filterIsInstance<RadioButton>().toList()
        val audiobook = buttons.single { it.text == "悠扬听书" }
        val bass = buttons.single { it.text == "超重低音" }
        audiobook.performClick()
        assertEquals(listOf(VivoTws3eControlRequest.SetDeepX(VivoDeepX.AUDIOBOOK)), sent)
        assertFalse(audiobook.isChecked); assertTrue(bass.isChecked)
        state = state.withFeature(VivoTws3eFeatureState(deepX = VivoDeepX.AUDIOBOOK, wearDetection = true))
        observer!!(state)
        assertTrue(audiobook.isChecked)
        token = "two"
        bass.performClick()
        assertEquals(1, sent.size); assertEquals(1, released); assertFalse(popup.isShowing)
        details.dismiss(); assertEquals(1, released)
        val retry = VivoTws3eSettingsDetails.open(anchor, address, environment)
        shadowOf(Looper.getMainLooper()).idle()
        val retryPopup = retry.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(retry) as PopupWindow
        providerFails = true
        descendants(retryPopup.contentView).filterIsInstance<RadioButton>().single { it.text == "超重低音" }.performClick()
        assertEquals(1, sent.size)
        assertEquals(2, released)
        assertFalse(retryPopup.isShowing)
    }
    @Test @Config(qualifiers = "night")
    fun nightPanelUsesContrastingHostPalette() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity); activity.setContentView(anchor)
        val address = "00:11:22:33:44:55"
        val state = EarbudState(adapter = VivoTws3eAdapter().snapshot(), address = address, lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(VivoTws3eFeatureState(wearDetection = true))
        val details = VivoTws3eSettingsDetails.open(anchor, address, MiLinkCardEnvironment(
            javaClass.classLoader!!, { state }, { _, _ -> }, { _, _ -> {} }, { "one" }))
        shadowOf(Looper.getMainLooper()).idle()
        val popup = details.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(details) as PopupWindow
        val body = (popup.contentView as android.widget.ScrollView).getChildAt(0) as android.widget.LinearLayout
        assertEquals(0xff242424.toInt(), (body.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(android.graphics.Color.WHITE, (body.getChildAt(0) as android.widget.TextView).currentTextColor)
        details.dismiss()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
