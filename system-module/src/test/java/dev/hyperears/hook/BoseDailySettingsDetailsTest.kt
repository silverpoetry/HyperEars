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
    @Test fun actual4062ReportsExposeExistingNativeCallControlsAndWaitForReadback() {
        val family=BoseEarbudAdapter()
        val result=family.receive(byteArrayOf(0,3,3,3,0x40,0x62,0))
        val adapter=(result.handshake as HandshakeResult.Replace).adapter
        adapter.receive(byteArrayOf(1,11,3,3,1,2,15,1,27,3,1,0))
        val f=Fixture()
        f.current=state().copy(adapter=adapter.snapshot(),features=adapter.runtimeState().features)
        val details=f.open()
        val high=views(details).single{it.contentDescription=="通话侧音 高"}
        high.performClick();assertEquals(BoseDailyControlRequest.SetSidetone(1),f.sent.single())
        assertEquals(2,f.current.features.get<BoseDailySettingsFeatureState>()!!.sidetone)
        val answer=views(details).filterIsInstance<CompoundButton>().single{it.isEnabled}
        answer.isChecked=true;assertEquals(BoseDailyControlRequest.SetAutoAnswer(true),f.sent.last())
        assertFalse(answer.isChecked)
        adapter.receive(byteArrayOf(1,27,3,1,1))
        f.current=f.current.copy(features=adapter.runtimeState().features);f.observer!!(f.current)
        assertTrue(answer.isChecked)
        f.token="replacement";high.performClick();assertEquals(2,f.sent.size);assertEquals(1,f.released)
        details.dismiss()
    }
    @Test fun actual4062ButtonReportUsesExistingNativeMaskEditorWithoutOptimism() {
        val adapter=(BoseEarbudAdapter().receive(byteArrayOf(0,3,3,3,0x40,0x62,0)).handshake as HandshakeResult.Replace).adapter
        adapter.receive(byteArrayOf(1,9,3,7,16,4,1,6,0,0,0))
        val f=Fixture();f.current=state().copy(adapter=adapter.snapshot(),features=adapter.runtimeState().features)
        val button=f.current.features.get<BoseDailySettingsFeatureState>()!!.buttons.single()
        val details=f.open()
        val choice=views(details).single{it.contentDescription=="bose.button.16.4.2"}
        choice.performClick();assertEquals(BoseDailyControlRequest.SetButton(button,2),f.sent.single())
        assertEquals(1,f.current.features.get<BoseDailySettingsFeatureState>()!!.buttons.single().current)
        adapter.receive(byteArrayOf(1,9,3,7,16,4,1,2,0,0,0))
        f.current=f.current.copy(features=adapter.runtimeState().features)
        choice.performClick();assertEquals(1,f.sent.size)
        f.token="replacement";choice.performClick();assertEquals(1,f.sent.size);assertEquals(1,f.released)
        details.dismiss()
    }
    @Test fun callSettingsWriteOnceWaitForReportAndRejectRemovedField() {
        val f = Fixture()
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(sidetone = 3, autoAnswer = false))
        val details = f.open()
        val medium = views(details).single { it.contentDescription == "通话侧音 中" }
        medium.performClick()
        assertEquals(listOf(BoseDailyControlRequest.SetSidetone(2)), f.sent)
        assertFalse(medium.isSelected)
        views(details).filterIsInstance<CompoundButton>().single { it.isEnabled }.isChecked = true
        assertEquals(BoseDailyControlRequest.SetAutoAnswer(true), f.sent.last())
        f.current = f.current.withFeature(BoseDailySettingsFeatureState(autoAnswer = false))
        medium.performClick()
        assertEquals(2, f.sent.size)
        details.dismiss()
    }
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
    @Test fun buttonChoiceUsesDisplayedMaskSnapshotAndNewSessionDismisses() {
        val f=Fixture()
        val button=BoseButtonSetting(0x4020,16,4,1,listOf(6,0,0,0))
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(buttons=listOf(button)))
        val details=f.open()
        fun option()=views(details).single{it.contentDescription=="bose.button.16.4.2"}
        val stale=option()
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(buttons=listOf(button.copy(mask=listOf(2,0,0,0)))))
        stale.performClick();assertTrue(f.sent.isEmpty())
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(buttons=listOf(button)));f.observer!!(f.current)
        option().performClick();assertEquals(BoseDailyControlRequest.SetButton(button,2),f.sent.single())
        assertEquals(1,f.current.features.get<BoseDailySettingsFeatureState>()!!.buttons.single().current)
        val old=option();f.token="replacement";old.performClick();assertEquals(1,f.sent.size);assertEquals(1,f.released)
    }

    @Test fun spatialControlsPinAllFieldsAndKeepReportedSelection() {
        val f=Fixture();val snapshot=BoseLiveAudioSettings(0x4082,listOf(7,1,0,1,0))
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(liveAudio=snapshot))
        val details=f.open()
        fun option()=views(details).single{it.contentDescription=="bose.spatial.2"}
        val old=option()
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(liveAudio=snapshot.copy(raw=listOf(6,1,0,1,0))))
        old.performClick();assertTrue(f.sent.isEmpty())
        f.observer!!(f.current);option().performClick()
        assertEquals(BoseDailyControlRequest.SetSpatial(snapshot.copy(raw=listOf(6,1,0,1,0)),2),f.sent.single())
        assertFalse((option() as android.widget.RadioButton).isChecked)
        val refresh=views(details).single{it.contentDescription=="bose.audio.refresh"}
        refresh.performClick();assertEquals(StandardControlRequest.Refresh,f.sent.last());assertEquals(2,f.sent.size)
        f.token="new-session";option().performClick();refresh.performClick();assertEquals(2,f.sent.size);assertEquals(1,f.released)
    }
    @Test fun renameDraftValidatesBytesRejectsStaleNameAndWaitsForFirmwareReport() {
        val f=Fixture();val original=BoseDeviceName(0x4039,0,"Fargo")
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(deviceName=original))
        val details=f.open()
        val draft=views(details).filterIsInstance<android.widget.EditText>().single{it.contentDescription=="bose.rename.draft"}
        val apply=views(details).single{it.contentDescription=="bose.rename.apply"}
        draft.setText("é".repeat(16));apply.performClick();assertTrue(f.sent.isEmpty());assertNotNull(draft.error)
        draft.setText("Cafe");apply.performClick()
        assertEquals(BoseDailyControlRequest.Rename(original,"Cafe"),f.sent.single())
        assertEquals("Fargo",f.current.features.get<BoseDailySettingsFeatureState>()!!.deviceName!!.name)
        f.current=f.current.withFeature(BoseDailySettingsFeatureState(deviceName=original.copy(name="Other")))
        draft.setText("Another");apply.performClick();assertEquals(1,f.sent.size)
        f.observer!!(f.current);assertEquals("Other",draft.text.toString())
        draft.setText("New");f.token="replacement";apply.performClick();assertEquals(1,f.sent.size);assertEquals(1,f.released)
    }

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
