package dev.hyperears.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.SeekBar
import dev.hyperears.integration.*
import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HuaweiDailySettingsDetailsTest {
    @Test fun buds3GestureAndPro5EqualizerLabelsFollowTheirOwnWireCodes() {
        val buds3 = EarbudAdapterRegistry.resolve(EarbudIdentity("HUAWEI FreeBuds 3", true))!!
        buds3.receive(HuaweiFreebudsSppCodec.packet(0x0120, listOf(1 to byteArrayOf(3), 2 to byteArrayOf(4))))
        val rows = HuaweiDailyChoices.rows(EarbudState(adapter = buds3.snapshot(), features = buds3.runtimeState().features))
        assertEquals("切换降噪", rows.first().choices.single { it.value == 3 }.label)
        assertEquals("下一首", rows.first().choices.single { it.value == 4 }.label)
        val pro5 = EarbudAdapterRegistry.resolve(EarbudIdentity("HUAWEI FreeBuds Pro 5", true))!!
        val state = EarbudState(adapter = pro5.snapshot()).withFeature(HuaweiDailySettingsFeatureState(
            equalizer = HuaweiEqualizerState(5, listOf(5, 1, 2, 9))))
        val eq = HuaweiDailyChoices.rows(state).single()
        assertEquals("默认", eq.choices.single { it.value == 5 }.label)
        assertEquals("低音增强", eq.choices.single { it.value == 1 }.label)
    }
    @Test fun sourceAliasModelsUseTheirOwnReportedEqualizerLabels() {
        for (name in listOf("HUAWEI FreeBuds Pro 4", "HUAWEI FreeClip")) {
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(name, true)))
            adapter.receive(HuaweiFreebudsSppCodec.packet(0x2B4A,
                listOf(1 to byteArrayOf(1), 2 to byteArrayOf(5), 3 to byteArrayOf(5, 1, 2, 9))))
            val rows = HuaweiDailyChoices.rows(EarbudState(adapter = adapter.snapshot(), features = adapter.runtimeState().features))
            val eq = rows.single()
            assertEquals("默认", eq.choices.single { it.value == 5 }.label)
            assertEquals("低音增强", eq.choices.single { it.value == 1 }.label)
        }
    }
    private class Fixture(levelOnly: Boolean = false) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val address = "00:11:22:33:44:55"
        private val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = if (levelOnly) "HUAWEI FreeBuds Pro 3" else "HUAWEI FreeBuds 6i", standardHeadset = true))).apply {
            receive(if (levelOnly) HuaweiFreebudsSppCodec.packet(0x2B2A, listOf(1 to byteArrayOf(0, 1)))
                else HuaweiFreebudsSppCodec.packet(0x2B11, listOf(1 to byteArrayOf(1))))
        }
        var state = EarbudState(adapter = adapter.snapshot(), address = address, lifecycle = DeviceLifecycle(
            systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED,
        )).withFeature(HuaweiDailySettingsFeatureState(wearDetection = true, multipoint = false,
            equalizer = HuaweiEqualizerState(100, listOf(1, 2, 100), listOf(HuaweiCustomEqualizer(100, "Daily", List(10) { 0 })), true)))
        init {
            if (levelOnly) state = state.copy(features = adapter.runtimeState().features)
        }
        var token = "one"
        var providerFails = false
        var observer: ((EarbudState) -> Unit)? = null
        var released = 0
        val sent = mutableListOf<ControlRequest>()
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { check(!providerFails); state },
            { _, request -> sent += request }, { _, callback -> observer = callback; { observer = null; released++ } }, { token })
        fun open(): MiLinkToggleDetails = HuaweiDailySettingsDetails.open(anchor, address, environment).also {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun levelOnlyEntryWritesTypedLevelAndLossClosesPanel() {
        val fixture = Fixture(levelOnly = true)
        assertNull(fixture.state.features.get<HuaweiDailySettingsFeatureState>())
        assertTrue(HuaweiDailySettingsPolicy.available(fixture.state, fixture.address))
        val details = fixture.open(); val popup = popup(details)
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "深度" }.performClick()
        assertEquals(listOf(HuaweiControlRequest.SetAncLevel(HuaweiAncLevel.ULTRA)), fixture.sent)
        fixture.state = fixture.state.withFeature(HuaweiAncLevelFeatureState(null, HuaweiAncLevel.entries.toSet()))
        fixture.observer!!(fixture.state)
        assertFalse(popup.isShowing)
        assertEquals(1, fixture.released)
        assertFalse(HuaweiDailySettingsPolicy.available(fixture.state, fixture.address))
    }
    @Test fun draftNeedsSaveAndDeviceReportThenSessionChangeRevokesControls() {
        val fixture = Fixture()
        val details = fixture.open()
        val popup = popup(details)
        val slider = descendants(popup.contentView).filterIsInstance<SeekBar>().first()
        val listener = ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
        listener.onProgressChanged(slider, 90, true)
        assertTrue(fixture.sent.isEmpty())
        descendants(popup.contentView).filterIsInstance<Button>().single { it.text == "保存自定义音效" }.performClick()
        assertEquals(HuaweiDailyControlRequest.SetCustomEqualizer(100, listOf(30) + List(9) { 0 }, HuaweiCustomEqualizer(100, "Daily", List(10) { 0 })), fixture.sent.single())
        assertEquals(0, fixture.state.features.get<HuaweiDailySettingsFeatureState>()!!.equalizer!!.custom.single().gains[0])
        val bass = descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "低音增强" }
        bass.performClick()
        assertEquals(HuaweiDailyControlRequest.SetEqualizer(2), fixture.sent.last())
        assertTrue(descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "Daily" }.isChecked)
        fixture.token = "two"
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "低音增强" }.performClick()
        assertEquals(2, fixture.sent.size); assertFalse(popup.isShowing); assertEquals(1, fixture.released)
    }
    @Test fun providerFailureDoesNotEscapeGestureAndReleasesObserver() {
        val fixture = Fixture(); val details = fixture.open(); val popup = popup(details)
        fixture.providerFails = true
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text == "低音增强" }.performClick()
        assertTrue(fixture.sent.isEmpty()); assertEquals(1, fixture.released); assertFalse(popup.isShowing)
        details.dismiss(); assertEquals(1, fixture.released)
    }
    @Test fun ownershipLossAndPartialFeatureRemovalCloseOrHideControls() {
        val fixture = Fixture(); val details = fixture.open(); val popup = popup(details)
        fixture.state = fixture.state.withFeature(HuaweiDailySettingsFeatureState(multipoint = true))
        fixture.observer!!(fixture.state)
        assertFalse(descendants(popup.contentView).filterIsInstance<RadioButton>().any { it.text == "低音增强" })
        fixture.state = fixture.state.copy(lifecycle = fixture.state.lifecycle.copy(
            controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppCatalog.huaweiAiLife))
        fixture.observer!!(fixture.state)
        assertFalse(popup.isShowing); assertEquals(1, fixture.released)
    }
    @Test @Config(qualifiers = "night")
    fun nightPanelUsesContrastingHostPalette() {
        val fixture = Fixture(); val details = fixture.open()
        val content = popup(details).contentView as android.widget.ScrollView
        val body = content.getChildAt(0) as android.widget.LinearLayout
        assertEquals(0xff242424.toInt(), (body.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(android.graphics.Color.WHITE, (body.getChildAt(0) as android.widget.TextView).currentTextColor)
        details.dismiss()
    }
    @Test fun clipDoubleTapSevenIsSpatialWhileTripleTapSevenIsPrevious() {
        val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = "HUAWEI FreeClip 2", standardHeadset = true)))
        val state = EarbudState(adapter = adapter.snapshot()).withFeature(HuaweiDailySettingsFeatureState(gestures = listOf(
            HuaweiGestureState(HuaweiGestureKind.DOUBLE, left = 7, available = listOf(7)),
            HuaweiGestureState(HuaweiGestureKind.TRIPLE, left = 7, available = listOf(7)),
        )))
        val rows = HuaweiDailyChoices.rows(state)
        assertEquals("切换空间音频", rows.single { it.key == "DOUBLE.LEFT" }.choices.single().label)
        assertEquals("上一首", rows.single { it.key == "TRIPLE.LEFT" }.choices.single().label)
    }
    @Test fun renamedSlotBeforeObserverCannotReceiveOldDraft() {
        val fixture = Fixture(); val details = fixture.open(); val popup = popup(details)
        val slider = descendants(popup.contentView).filterIsInstance<SeekBar>().first()
        ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
            .onProgressChanged(slider, 90, true)
        val old = fixture.state.features.get<HuaweiDailySettingsFeatureState>()!!
        fixture.state = fixture.state.withFeature(old.copy(equalizer = old.equalizer!!.copy(
            custom = listOf(HuaweiCustomEqualizer(100, "Replacement", List(10) { 0 })))))
        descendants(popup.contentView).filterIsInstance<Button>().single { it.text == "保存自定义音效" }.performClick()
        assertTrue(fixture.sent.isEmpty())
        details.dismiss()
    }
    @Test fun preferenceRowsRemainConfirmedAndRejectRevokedFieldsOrSession() {
        val fixture=Fixture()
        fixture.state=fixture.state.withFeature(HuaweiDailySettingsFeatureState(lowLatency=false,preferSoundQuality=false))
        val details=fixture.open();val popup=popup(details)
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text=="开启" }.performClick()
        assertEquals(HuaweiDailyControlRequest.SetLowLatency(true),fixture.sent.single())
        assertTrue(descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text=="关闭" }.isChecked)
        val quality=descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text=="音质优先" }
        fixture.state=fixture.state.withFeature(HuaweiDailySettingsFeatureState(lowLatency=false))
        quality.performClick();assertEquals(1,fixture.sent.size)
        assertFalse(descendants(popup.contentView).filterIsInstance<RadioButton>().any { it.text=="音质优先" })
        fixture.token="replacement"
        descendants(popup.contentView).filterIsInstance<RadioButton>().single { it.text=="开启" }.performClick()
        assertEquals(1,fixture.sent.size);assertFalse(popup.isShowing)
    }
    private fun popup(details: MiLinkToggleDetails) = details.javaClass.getDeclaredField("popup").apply { isAccessible = true }.get(details) as PopupWindow
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
