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
class RoseCoralSettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(RoseCoralSettingsFeatureState(gameMode = false, promptVolume = 3))

    @Test fun gesturesWaitForReadbackAndUseFreshStateAndSession() {
        val fixture = Fixture()
        val details = fixture.open()
        val content = popup(details).contentView
        val toggle = descendants(content).filterIsInstance<CompoundButton>().first { it.visibility == View.VISIBLE && it.isEnabled }
        toggle.isChecked = true
        assertEquals(listOf(RoseCoralControlRequest.SetGameMode(true)), fixture.sent)
        assertFalse(toggle.isChecked)
        fixture.current = fixture.current.withFeature(RoseCoralSettingsFeatureState(gameMode = true, promptVolume = 3))
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
        assertEquals(1, slider.min)
        assertEquals(5, slider.max)
        assertEquals(3, slider.progress)
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
        listener.onProgressChanged(slider, 4, true)
        assertEquals(listOf(RoseCoralControlRequest.SetPromptVolume(4)), fixture.sent)
        assertEquals(3, slider.progress)
        fixture.current = fixture.current.withFeature(RoseCoralSettingsFeatureState(gameMode = false, promptVolume = 4))
        fixture.observer!!(fixture.current)
        assertEquals(4, slider.progress)
        listener.onProgressChanged(slider, 4, true)
        assertEquals(1, fixture.sent.size)
        details.dismiss()
    }

    @Test fun constructorFailureAfterSubscriptionReleasesObserverAndReturnsInertDetails() {
        val fixture = Fixture()
        val listenersBefore = attachListenerCount(fixture.anchor)
        val environment = fixture.environment.copy(stateProvider = { error("synthetic state provider failure") })
        val details = RoseCoralSettingsDetails.open(fixture.anchor, address, environment)
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
        val details = RoseCoralSettingsDetails.open(fixture.anchor, address, environment)
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
        val failure = RoseCoralSettingsDetails.open(View(fixture.activity), address, fixture.environment)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixture.unsubscribed)
        failure.dismiss()
        assertEquals(2, fixture.unsubscribed)
    }

    @Test fun allTouchSlotsAndNineActionsUseTypedRequestsWithReadbackOnly() {
        val fixture = Fixture()
        val none = RoseCoralTouchAction.NONE
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot()).withFeature(
            RoseCoralTouchFeatureState(none, none, none, none, none, none, none, none))
        val details = fixture.open()
        val texts = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
        val captions = texts.filter { it.text.toString().endsWith("无操作 ›") }
        assertEquals(8, captions.size)
        captions.forEachIndexed { index, caption ->
            caption.performClick()
            val row = caption.parent as android.widget.LinearLayout
            val choices = row.getChildAt(1) as android.widget.LinearLayout
            assertEquals(android.widget.LinearLayout.VERTICAL, choices.orientation)
            assertEquals(9, choices.childCount)
            RoseCoralTouchAction.entries.forEachIndexed { actionIndex, action ->
                val count = fixture.sent.size
                choices.getChildAt(actionIndex).performClick()
                if (action == none) assertEquals(count, fixture.sent.size) else {
                    assertEquals(RoseCoralControlRequest.SetTouch(RoseCoralTouchSlot.entries[index], action), fixture.sent.last())
                    assertTrue(choices.getChildAt(0).isSelected)
                    assertFalse(choices.getChildAt(actionIndex).isSelected)
                }
            }
        }
        val count = fixture.sent.size
        fixture.token = "new-session"
        captions.first().performClick()
        assertEquals(count, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
        details.dismiss()
    }

    @Test fun findOnlyEvidenceShowsNullableIndicatorsAndAllExplicitTargets() {
        val fixture = Fixture()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot())
            .withFeature(RoseCoralFindFeatureState(left = true))
        val details = fixture.open()
        val texts = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
        val status = texts.first { it.text.toString().startsWith("寻找耳机 ·") }
        assertEquals("寻找耳机 · 左耳：响铃中 · 右耳：未知", status.text.toString())
        listOf("响铃左耳", "响铃右耳", "响铃双耳", "停止响铃").forEachIndexed { index, label ->
            texts.first { it.text == label }.performClick()
            assertEquals(RoseCoralControlRequest.SetFind(RoseCoralFindTarget.entries[index]), fixture.sent.last())
            assertEquals("寻找耳机 · 左耳：响铃中 · 右耳：未知", status.text.toString())
        }
        fixture.current = fixture.current.withFeature(RoseCoralFindFeatureState(false, true))
        fixture.observer!!(fixture.current)
        assertEquals("寻找耳机 · 左耳：已停止 · 右耳：响铃中", status.text.toString())
        details.dismiss()
    }

    @Test fun allProvenSettingsHaveExactTypedRequestsAndReadbackBounds() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(RoseCoralSettingsFeatureState(false, false, 0, 1, false))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        listOf("游戏模式", "双设备连接", "触控开关").forEach { label ->
            val toggle = views.filterIsInstance<CompoundButton>().first { it.contentDescription == label }
            toggle.isChecked = true
            assertFalse(toggle.isChecked)
        }
        assertEquals(listOf(RoseCoralControlRequest.SetGameMode(true), RoseCoralControlRequest.SetMultipoint(true),
            RoseCoralControlRequest.SetTouchEnabled(true)), fixture.sent)
        val bass = views.filterIsInstance<SeekBar>().first { it.contentDescription == "低音增强" }
        assertEquals(0, bass.min); assertEquals(2, bass.max)
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(bass, "mOnSeekBarChangeListener")
        listener.onProgressChanged(bass, 2, true)
        assertEquals(RoseCoralControlRequest.SetBassLevel(2), fixture.sent.last())
        assertEquals(0, bass.progress)
        details.dismiss()
    }

    @Test fun firmwareOnlyEvidenceOpensReadOnlyDetails() {
        val fixture = Fixture()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot()).withFeature(RoseCoralInfoFeatureState(1, 2, 3))
        assertTrue(RoseCoralCardPolicy.available(fixture.current, address))
        val details = fixture.open()
        val texts = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
        assertTrue(texts.any { it.text == "固件版本：123" })
        assertTrue(fixture.sent.isEmpty())
        details.dismiss()
    }

    @Test fun audioChoicesRequireConfirmationAndWaitForReportedSelection() {
        val fixture = Fixture()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot())
            .withFeature(RoseCoralAudioFeatureState(RoseCoralAudioMode.AAC_SBC))
        assertTrue(RoseCoralCardPolicy.available(fixture.current, address))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val texts = views.filterIsInstance<android.widget.TextView>()
        val current = texts.first { it.contentDescription == "音频模式 AAC/SBC" }
        val ldac = texts.first { it.contentDescription == "音频模式 LDAC" }
        val confirm = texts.first { it.text == "确认切换" }
        val cancel = texts.first { it.text == "取消切换" }
        current.performClick()
        assertTrue(fixture.sent.isEmpty())
        ldac.performClick()
        assertTrue(fixture.sent.isEmpty())
        assertTrue(current.isSelected)
        assertFalse(ldac.isSelected)
        cancel.performClick()
        confirm.performClick()
        assertTrue(fixture.sent.isEmpty())
        ldac.performClick()
        confirm.performClick()
        assertEquals(listOf(RoseCoralControlRequest.SetAudioMode(RoseCoralAudioMode.LDAC)), fixture.sent)
        assertTrue(current.isSelected)
        assertFalse(ldac.isSelected)
        fixture.current = fixture.current.withFeature(RoseCoralAudioFeatureState(RoseCoralAudioMode.LDAC))
        fixture.observer!!(fixture.current)
        assertTrue(ldac.isSelected)
        assertFalse(current.isSelected)
        val lhdc = texts.first { it.contentDescription == "音频模式 LHDC" }
        lhdc.performClick()
        fixture.token = "next"
        confirm.performClick()
        assertEquals(1, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
    }

    @Test fun allFourAudioRequestsAreTypedAndPendingConfirmationNeedsFreshEvidence() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(RoseCoralAudioFeatureState(RoseCoralAudioMode.LDAC))
        val details = fixture.open()
        val texts = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
        val confirm = texts.first { it.text == "确认切换" }
        val labels = listOf("AAC/SBC", "LDAC", "LHDC", "LC3")
        RoseCoralAudioMode.entries.forEachIndexed { index, mode ->
            val previous = if (mode == RoseCoralAudioMode.AAC_SBC) RoseCoralAudioMode.LDAC else RoseCoralAudioMode.AAC_SBC
            fixture.current = fixture.current.withFeature(RoseCoralAudioFeatureState(previous))
            fixture.observer!!(fixture.current)
            val option = texts.first { it.contentDescription == "音频模式 ${labels[index]}" }
            val count = fixture.sent.size
            option.performClick()
            assertEquals(count, fixture.sent.size)
            confirm.performClick()
            assertEquals(RoseCoralControlRequest.SetAudioMode(mode), fixture.sent.last())
            assertFalse(option.isSelected)
        }
        val option = texts.first { it.contentDescription == "音频模式 LC3" }
        option.performClick()
        fixture.current = fixture.current.copy(features = DeviceFeatureSnapshot())
            .withFeature(RoseCoralSettingsFeatureState(gameMode = false))
        confirm.performClick()
        assertEquals(4, fixture.sent.size)
        fixture.current = fixture.current.withFeature(RoseCoralAudioFeatureState(RoseCoralAudioMode.AAC_SBC))
        fixture.observer!!(fixture.current)
        option.performClick()
        fixture.current = fixture.current.copy(lifecycle = fixture.current.lifecycle.copy(
            controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppSpec("test.controller", "Test")))
        confirm.performClick()
        assertEquals(4, fixture.sent.size)
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
        fun open(): MiLinkToggleDetails = RoseCoralSettingsDetails.open(anchor, address, environment).also {
            shadowOf(Looper.getMainLooper()).idle()
            it.render(current)
        }
    }
}
