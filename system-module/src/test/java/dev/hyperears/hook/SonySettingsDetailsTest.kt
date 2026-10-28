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
class SonySettingsDetailsTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, lifecycle = DeviceLifecycle(
        systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
        protocolHandshake = ProtocolHandshakeState.CONFIRMED,
    )).withFeature(SonyAmbientFeatureState(10, false, true, false))

    @Test fun pendingMultipointAssociationDisablesInverseToggleBeforeAlertAndRefreshesAtDeadline() {
        val fixture = Fixture()
        val definition = SonyMultipointDefinition(0xD2)
        fixture.current = fixture.current.withFeature(SonyMultipointFeatureState(definition, true, true,
            pendingTransaction = 4, pendingExpiresAtMillis = 0))
        val details = fixture.open()
        val toggle = descendants(popup(details).contentView).filterIsInstance<CompoundButton>()
            .single { it.contentDescription == "同时连接两台设备" }
        assertTrue(toggle.isChecked)
        assertFalse(toggle.isEnabled)
        toggle.isChecked = false
        assertEquals(listOf(StandardControlRequest.Refresh), fixture.sent)
        fixture.current = fixture.current.withFeature(SonyMultipointFeatureState(definition, true, true))
        fixture.observer!!(fixture.current)
        assertTrue(toggle.isEnabled)
        assertTrue(toggle.isChecked)
        details.dismiss()
    }
    @Test fun expiredMultipointAlertRequestsOneGuardedRefreshAndDismissCancelsDeadline() {
        val fixture = Fixture()
        val definition = SonyMultipointDefinition(0xD2)
        val expired = SonyMultipointAlert(2, 7, true, 0)
        fixture.current = fixture.current.withFeature(SonyMultipointFeatureState(definition, false, true, expired))
        val details = fixture.open()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(StandardControlRequest.Refresh), fixture.sent)
        fixture.observer!!(fixture.current)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fixture.sent.size)
        assertFalse(descendants(popup(details).contentView).single { it.contentDescription == "确认双设备变更" }.isEnabled)
        fixture.current = fixture.current.withFeature(SonyMultipointFeatureState(definition, false, true,
            expired.copy(transaction = 3, expiresAtMillis = System.nanoTime() / 1_000_000 + 60_000)))
        fixture.observer!!(fixture.current)
        details.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2))
        assertEquals(1, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
    }
    @Test fun multipointConfirmationUsesDisplayedAlertAndWaitsForReportedValue() {
        val fixture = Fixture()
        val definition = SonyMultipointDefinition(0xD2)
        val confirmed = SonyMultipointFeatureState(definition, false, true)
        fixture.current = fixture.current.withFeature(confirmed)
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val toggle = views.filterIsInstance<CompoundButton>().single { it.contentDescription == "同时连接两台设备" }
        toggle.isChecked = true
        assertEquals(SonyControlRequest.SetMultipoint(definition, true), fixture.sent.single())
        assertFalse(toggle.isChecked)
        val first = SonyMultipointAlert(1, 7, true)
        fixture.current = fixture.current.withFeature(confirmed.copy(alert = first))
        fixture.observer!!(fixture.current)
        assertFalse(toggle.isEnabled)
        val accept = views.single { it.contentDescription == "确认双设备变更" }
        // Provider changed before the observer: do not confirm a different, unseen warning.
        val second = SonyMultipointAlert(1, 6, true)
        fixture.current = fixture.current.withFeature(confirmed.copy(alert = second))
        accept.performClick()
        assertEquals(1, fixture.sent.size)
        accept.performClick()
        assertEquals(SonyControlRequest.ReplyMultipointAlert(definition, second, true), fixture.sent.last())
        assertFalse(toggle.isChecked)
        fixture.current = fixture.current.withFeature(confirmed.copy(enabled = true))
        fixture.observer!!(fixture.current)
        assertTrue(toggle.isChecked)
        fixture.token = "new-session"
        toggle.isChecked = false
        assertEquals(2, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
    }

    @Test fun audioControlsNeedFreshAvailabilityAndWaitForConfirmation() {
        val fixture = Fixture()
        val definition = SonyAudioDefinition(2, 11)
        val audio = SonyAudioSettingsFeatureState(dseeDefinition = definition, dsee = false, dseeAvailable = true,
            qualityDefinition = SonyAudioDefinition(2, 5), quality = 2, qualityAvailable = false)
        fixture.current = fixture.current.withFeature(audio)
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val toggle = views.filterIsInstance<CompoundButton>().first { it.contentDescription == "DSEE" }
        toggle.isChecked = true
        assertEquals(listOf(SonyControlRequest.SetDsee(definition, true)), fixture.sent)
        assertFalse(toggle.isChecked)
        val quality = views.first { it.contentDescription == "音质优先" }
        assertFalse(quality.isEnabled)
        fixture.current = fixture.current.withFeature(audio.copy(dsee = true, dseeAvailable = false, qualityAvailable = true))
        fixture.observer!!(fixture.current)
        assertTrue(toggle.isChecked); assertFalse(toggle.isEnabled); assertTrue(quality.isEnabled)
        quality.performClick()
        assertEquals(SonyControlRequest.SetConnectionQuality(SonyAudioDefinition(2, 5), 0), fixture.sent.last())
        fixture.token = "replacement"; quality.performClick()
        assertEquals(2, fixture.sent.size)
        assertEquals(1, fixture.unsubscribed)
        assertFalse(popup(details).isShowing)
    }
    @Test fun gesturesWaitForReadbackAndUseFreshStateAndSession() {
        val fixture = Fixture()
        val details = fixture.open()
        val content = popup(details).contentView
        val toggle = descendants(content).filterIsInstance<CompoundButton>().first { it.visibility == View.VISIBLE && it.isEnabled }
        toggle.isChecked = true
        assertEquals(listOf(SonyControlRequest.SetAmbient(10, true)), fixture.sent)
        assertFalse(toggle.isChecked)
        fixture.current = fixture.current.withFeature(SonyAmbientFeatureState(10, true, true, false))
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
        val details = SonySettingsDetails.open(fixture.anchor, address, environment)
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
        val details = SonySettingsDetails.open(fixture.anchor, address, environment)
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
        val failure = SonySettingsDetails.open(View(fixture.activity), address, fixture.environment)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixture.unsubscribed)
        failure.dismiss()
        assertEquals(2, fixture.unsubscribed)
    }

    @Test fun equalizerDraftPreservesFreshClearBassAndWaitsForReport() {
        val fixture = Fixture()
        val eq = SonyEqFeatureState(version = 1, parameterType = 1, enabled = true, preset = 0,
            rawSteps = listOf(10, 11), bands = listOf(SonyEqBand(16, 0), SonyEqBand(1, 400)))
        fixture.current = fixture.current.withFeature(eq)
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val slider = views.filterIsInstance<SeekBar>().single { it.contentDescription == "400 Hz" }
        assertEquals(20, slider.max)
        assertEquals(11, slider.progress)
        val listener = org.robolectric.util.ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
        listener.onProgressChanged(slider, 15, true)
        assertTrue(fixture.sent.isEmpty())
        fixture.current = fixture.current.withFeature(eq.copy(rawSteps = listOf(14, 11)))
        views.filterIsInstance<android.widget.TextView>().single { it.text == "应用 EQ 草稿" }.performClick()
        assertEquals(SonyControlRequest.SetEqBands(listOf(4, 5), requireNotNull(eq.definition)), fixture.sent.single())
        assertEquals(listOf(14, 11), fixture.current.features.get<SonyEqFeatureState>()!!.rawSteps)
        details.dismiss()
    }

    @Test fun disabledAdaptiveAmbientNeverSendsAndProviderFailureReleases() {
        val fixture = Fixture()
        fixture.current = fixture.current.withFeature(SonyAmbientFeatureState(10, false, true, true))
        val details = fixture.open()
        val views = descendants(popup(details).contentView)
        val voice = views.filterIsInstance<CompoundButton>().single { it.contentDescription == "关注语音" }
        assertFalse(voice.isEnabled)
        voice.isChecked = true
        assertTrue(fixture.sent.isEmpty())
        fixture.providerFails = true
        voice.isChecked = true
        assertEquals(1, fixture.unsubscribed)
        assertFalse(popup(details).isShowing)
    }

    @Test fun oldDraftCannotApplyAfterSameGeometryProtocolDefinitionChanges() {
        val fixture = Fixture()
        val eq = SonyEqFeatureState(version = 1, parameterType = 1, enabled = true, preset = 0,
            rawSteps = listOf(10, 11), bands = listOf(SonyEqBand(16, 0), SonyEqBand(1, 400)))
        fixture.current = fixture.current.withFeature(eq)
        val details = fixture.open()
        val apply = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
            .single { it.text == "应用 EQ 草稿" }
        fixture.current = fixture.current.withFeature(eq.copy(version = 2, parameterType = 0))
        apply.performClick()
        assertTrue(fixture.sent.isEmpty())
        val updated = descendants(popup(details).contentView).filterIsInstance<android.widget.TextView>()
            .single { it.text == "应用 EQ 草稿" }
        updated.performClick()
        assertEquals(eq.copy(version = 2, parameterType = 0).definition,
            (fixture.sent.single() as SonyControlRequest.SetEqBands).expectedDefinition)
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
        fun open(): MiLinkToggleDetails = SonySettingsDetails.open(anchor, address, environment).also {
            shadowOf(Looper.getMainLooper()).idle()
            it.render(current)
        }
    }
}
