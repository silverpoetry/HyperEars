package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
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
class RoseRfcommSettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(RoseRfcommSettingsFeatureState(mapOf(RoseRfcommSetting.GAME to 0, RoseRfcommSetting.NOISE to 5)))
    @Test fun sixModesStayDistinctAndWaitForReportThenRejectOldSession() {
        val fixture = Fixture(); val details = fixture.open()
        val extreme = button(details, "噪声模式 极限降噪")
        val adaptive = button(details, "噪声模式 自适应降噪")
        assertTrue(adaptive.isSelected); assertFalse(extreme.isSelected)
        extreme.performClick()
        assertEquals(listOf(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.NOISE, 6)), fixture.sent)
        assertTrue(adaptive.isSelected)
        fixture.current = fixture.current.withFeature(RoseRfcommSettingsFeatureState(mapOf(RoseRfcommSetting.NOISE to 6)))
        fixture.observer!!(fixture.current)
        assertTrue(extreme.isSelected)
        fixture.token = "next"; adaptive.performClick()
        assertEquals(1, fixture.sent.size); assertEquals(1, fixture.released)
        assertFalse(popup(details).isShowing)
    }
    @Test fun everyVisibleValueRoutesThroughTypedControlAndInvalidOwnershipDismisses() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(RoseRfcommSettingsFeatureState(RoseRfcommSetting.entries.associateWith { it.values.first() }))
        val details = fixture.open()
        listOf("游戏模式 开启" to (RoseRfcommSetting.GAME to 1), "均衡器 ROSE 经典" to (RoseRfcommSetting.EQ to 3),
            "降噪深度 深度" to (RoseRfcommSetting.ANC_DEPTH to 5), "通透深度 深度" to (RoseRfcommSetting.TRANSPARENCY_DEPTH to 5),
            "提示音语言 英文" to (RoseRfcommSetting.PROMPT_LANGUAGE to 1), "提示音音量 5" to (RoseRfcommSetting.PROMPT_LEVEL to 5)).forEach { (name, value) ->
            button(details, name).performClick()
            assertEquals(RoseRfcommControlRequest.SetValue(value.first, value.second), fixture.sent.last())
        }
        fixture.current = fixture.current.copy(lifecycle = fixture.current.lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = ControlAppSpec("test.controller", "Test")))
        fixture.observer!!(fixture.current)
        assertEquals(1, fixture.released); assertFalse(popup(details).isShowing)
    }
    @Test fun timeoutDetachAndRepeatedDismissReleaseCallbacksWithoutPolling() {
        val fixture = Fixture(); val details = fixture.open()
        button(details, "游戏模式 开启").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(1, fixture.sent.size)
        assertTrue(children(popup(details).contentView).filterIsInstance<TextView>().any { it.text.contains("尚未收到确认") })
        fixture.activity.setContentView(View(fixture.activity))
        details.dismiss(); details.dismiss(); assertEquals(1, fixture.released)
    }
    @Test fun malformedLifecycleAndMissingEvidenceCloseThePanel() {
        listOf<(EarbudState)->EarbudState>({ it.copy(address = "other") },
            { it.copy(features = DeviceFeatureSnapshot()) },
            { it.copy(lifecycle = it.lifecycle.copy(protocolHandshake = ProtocolHandshakeState.PENDING)) },
            { it.copy(lifecycle = it.lifecycle.copy(privateTransport = PrivateTransportState.IDLE)) }).forEach { change ->
            val fixture = Fixture(); val details = fixture.open()
            fixture.current = change(fixture.current); fixture.observer!!(fixture.current)
            assertEquals(1, fixture.released); assertFalse(popup(details).isShowing)
        }
    }
    private fun button(details: MiLinkToggleDetails, description: String) = children(popup(details).contentView)
        .filterIsInstance<TextView>().single { it.contentDescription == description }
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
        fun open() = RoseRfcommSettingsDetails.open(anchor, address, environment).also { shadowOf(Looper.getMainLooper()).idle() }
    }
}
