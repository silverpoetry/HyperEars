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
class OppoSettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(OppoSettingsFeatureState(toggles = mapOf(OppoToggle.LOW_LATENCY to false)))

    @Test fun gesturesWaitForReadbackAndUseFreshStateAndSession() {
        val fixture = Fixture()
        val details = fixture.open()
        val content = popup(details).contentView
        val toggle = descendants(content).filterIsInstance<CompoundButton>().first { it.visibility == View.VISIBLE && it.isEnabled }
        toggle.isChecked = true
        assertEquals(listOf(OppoControlRequest.SetToggle(OppoToggle.LOW_LATENCY, true)), fixture.sent)
        assertFalse(toggle.isChecked)
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(toggles = mapOf(OppoToggle.LOW_LATENCY to true)))
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

    @Test fun constructorFailureAfterSubscriptionReleasesObserverAndReturnsInertDetails() {
        val fixture = Fixture()
        val listenersBefore = attachListenerCount(fixture.anchor)
        val environment = fixture.environment.copy(stateProvider = { error("synthetic state provider failure") })
        val details = OppoSettingsDetails.open(fixture.anchor, address, environment)
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
        val details = OppoSettingsDetails.open(fixture.anchor, address, environment)
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
        val failure = OppoSettingsDetails.open(View(fixture.activity), address, fixture.environment)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixture.unsubscribed)
        failure.dismiss()
        assertEquals(2, fixture.unsubscribed)
    }

    @Test fun customEditorPreservesReportedFrequenciesAndUsesDeviceRange() {
        val fixture = Fixture()
        val eq = OppoEqualizer(9, "Custom", -6, 6, listOf(62, 1000), listOf(-1, 2))
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(currentEqualizer = 9,
            equalizers = listOf(eq), editableEqualizers = setOf(9)))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val sliders = views.filterIsInstance<SeekBar>().filter { it.contentDescription != "提示音音量" }
        assertEquals(2, sliders.size)
        assertEquals(12, sliders[0].max)
        assertEquals(5, sliders[0].progress)
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(sliders[0], "mOnSeekBarChangeListener")
        listener.onProgressChanged(sliders[0], 12, true)
        assertTrue(fixture.sent.isEmpty())
        views.filterIsInstance<android.widget.TextView>().single { it.contentDescription == "应用均衡器 9 草稿" }.performClick()
        assertEquals(OppoControlRequest.UpdateEqualizer(9, listOf(6, 2), eq.definition()), fixture.sent.single())
        assertEquals(listOf(-1, 2), fixture.current.features.get<OppoSettingsFeatureState>()!!.equalizers!!.single().gains)
        details.dismiss()
        assertEquals(1, fixture.unsubscribed)
    }

    @Test fun timeoutDoesNotWriteOrPollAndUnknownFeaturesStayHidden() {
        val fixture = Fixture()
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val toggles = views.filterIsInstance<CompoundButton>().filter { it.contentDescription != "空间音效" }
        assertEquals(1, toggles.count { (it.parent as View).visibility == View.VISIBLE })
        toggles.single { it.isEnabled }.isChecked = true
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(1, fixture.sent.size)
        assertTrue(views.filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("尚未收到确认") })
        assertFalse(toggles.single { it.isEnabled }.isChecked)
        details.dismiss()
    }
    @Test fun staleEditorDoesNotApplyToReplacedDefinitionBeforeObserverRender() {
        val fixture = Fixture()
        val eq = OppoEqualizer(9, "Custom", -6, 6, listOf(62), listOf(0))
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(currentEqualizer = 9,
            equalizers = listOf(eq), editableEqualizers = setOf(9)))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val slider = views.filterIsInstance<SeekBar>().filter { it.contentDescription != "提示音音量" }.single()
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
        listener.onProgressChanged(slider, 12, true)
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(currentEqualizer = 9,
            equalizers = listOf(eq.copy(frequencies = listOf(125))), editableEqualizers = setOf(9)))
        views.filterIsInstance<android.widget.TextView>().single { it.contentDescription == "应用均衡器 9 草稿" }.performClick()
        assertTrue(fixture.sent.isEmpty())
        details.dismiss()
        assertEquals(1, fixture.unsubscribed)
    }

    @Test fun everyConfirmedOptionalToggleAndChoiceRoutesTypedRequests() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(
            toggles = OppoToggle.entries.associateWith { false }, currentEqualizer = 1,
            equalizers = listOf(OppoEqualizer(1, "Default", -6, 6, emptyList(), emptyList()),
                OppoEqualizer(9, "Custom", -6, 6, emptyList(), emptyList()))))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        views.filterIsInstance<CompoundButton>().filter { it.contentDescription != "空间音效" }.forEachIndexed { index, toggle ->
            toggle.isChecked = true
            assertEquals(OppoControlRequest.SetToggle(OppoToggle.entries[index], true), fixture.sent.last())
            assertFalse(toggle.isChecked)
        }
        val texts = views.filterIsInstance<android.widget.TextView>()
        texts.single { it.contentDescription == "选择均衡器 9" }.performClick()
        assertEquals(OppoControlRequest.SelectEqualizer(9), fixture.sent.last())
        assertFalse(texts.single { it.contentDescription == "选择均衡器 9" }.text.startsWith("✓"))
        details.dismiss()
    }

    @Test fun reloadProviderFailureClosesPanelAndReleasesObserver() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(currentEqualizer = 9,
            equalizers = listOf(OppoEqualizer(9, "Custom", -6, 6, listOf(62), listOf(0))),
            editableEqualizers = setOf(9)))
        val details = fixture.open()
        val reload = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
            .single { it.text == "从耳机回报重新载入" }
        fixture.providerFails = true
        reload.performClick()
        assertEquals(1, fixture.unsubscribed)
        assertFalse(popup(details).isShowing)
        assertTrue(fixture.sent.isEmpty())
    }

    @Test fun indexedNoiseChoicesWaitForReportsAndRejectStaleProductDefinitions() {
        val fixture = Fixture()
        val definition = OppoNoiseDefinition(0x065414,listOf(
            OppoNoiseOption(OppoNoiseMode.ANC,1,true), OppoNoiseOption(OppoNoiseMode.DEEP,4,true),
            OppoNoiseOption(OppoNoiseMode.TRANSPARENCY,2,true), OppoNoiseOption(OppoNoiseMode.TRANSPARENCY,8,false),
            OppoNoiseOption(OppoNoiseMode.LISTEN,3,false)))
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(noise=OppoNoiseSelection(definition,1)))
        val details = fixture.open()
        fun button(name: String) = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
            .single { it.contentDescription == "降噪与通透 $name" }
        val deep = button("深度降噪")
        deep.performClick()
        assertEquals(listOf(OppoControlRequest.SelectNoiseOption(definition,4)),fixture.sent)
        assertFalse(deep.isSelected)
        assertEquals(1,descendants(popup(details).contentView).count { it.contentDescription == "降噪与通透 标准通透" })
        assertFalse(descendants(popup(details).contentView).any { it.contentDescription == "降噪与通透 聆听" })
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(noise=OppoNoiseSelection(definition,4)))
        fixture.observer!!(fixture.current)
        assertTrue(deep.isSelected)
        fixture.current = fixture.current.withFeature(OppoSettingsFeatureState(noise=OppoNoiseSelection(definition.copy(productId=0x063810),1)))
        deep.performClick() // Provider changed before observer; old callback must not target new product.
        assertEquals(1,fixture.sent.size)
        details.dismiss()
    }

    @Test fun promptAndSpatialUseReportsAndRejectRemovedEvidenceOrChangedProduct() {
        val fixture=Fixture()
        fixture.current=fixture.current.withFeature(OppoSettingsFeatureState(promptVolume=5,spatialSound=false,spatialProductId=0x06F010))
        val details=fixture.open()
        val slider=descendants(popup(details).contentView).filterIsInstance<SeekBar>().single { it.contentDescription=="提示音音量" }
        val listener=org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider,"mOnSeekBarChangeListener")
        slider.progress=7;listener.onStopTrackingTouch(slider)
        assertEquals(OppoControlRequest.SetPromptVolume(8),fixture.sent.last())
        assertEquals(4,slider.progress)
        val spatial=descendants(popup(details).contentView).filterIsInstance<CompoundButton>().single { it.contentDescription=="空间音效" }
        spatial.isChecked=true
        assertEquals(OppoControlRequest.SetSpatialSound(true,0x06F010),fixture.sent.last())
        assertFalse(spatial.isChecked)
        fixture.current=fixture.current.withFeature(OppoSettingsFeatureState(spatialSound=false,spatialProductId=0x065414))
        spatial.isChecked=true
        assertEquals(2,fixture.sent.size)
        slider.progress=9;listener.onStopTrackingTouch(slider)
        assertEquals(2,fixture.sent.size)
        details.dismiss()
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
        var providerFails = false
        var token: String? = "session"
        val sent = mutableListOf<ControlRequest>()
        var observer: ((EarbudState) -> Unit)? = null
        var unsubscribed = 0
        val environment = MiLinkCardEnvironment(javaClass.classLoader!!, { if (providerFails) error("synthetic provider failure") else current }, { _, request -> sent += request },
            { _, callback -> observer = callback; { unsubscribed++; observer = null } }, { token })
        fun open(): MiLinkToggleDetails = OppoSettingsDetails.open(anchor, address, environment).also {
            shadowOf(Looper.getMainLooper()).idle()
            it.render(current)
        }
    }
}
