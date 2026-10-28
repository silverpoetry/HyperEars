package dev.hyperears.hook

import android.app.Activity
import android.os.Binder
import android.view.View
import androidx.preference.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real AndroidX preference objects exercise Settings' documented public preference contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SettingsHeadsetBindingTest {
    private val address = "00:11:22:33:44:55"
    private fun state() = EarbudState(address = address, adapter = HonorX5sProAdapter().snapshot().copy(
        presentationId = HonorX5sProAdapter.PRESENTATION_ID,
        supportedNoiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)),
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED, privateTransport = PrivateTransportState.CONNECTED,
            protocolHandshake = ProtocolHandshakeState.CONFIRMED))
        .withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        .withFeature(HonorAncDepthFeatureState(HonorAncDepth.SMART, HonorAncDepth.entries.toSet()))
    @Test fun nativeNoiseAndDepthWaitForReadbackAndLeaveStockPreferencesUntouched() {
        val f = Fixture(); f.binding.render()
        val noise = f.row("hyperears.noise") as ListPreference
        assertFalse(noise.isPersistent)
        assertFalse(noise.callChangeListener("OFF"))
        assertEquals("ANC", noise.value)
        assertEquals(StandardControlRequest.SetNoiseMode(NoiseMode.OFF), f.sent.single())
        val depth = f.row("hyperears.honor.depth") as ListPreference
        assertFalse(depth.callChangeListener("DEEP"))
        assertEquals("SMART", depth.value)
        assertEquals(HonorControlRequest.SetAncDepth(HonorAncDepth.DEEP), f.sent.last())
        f.current = f.current.withFeature(HonorAncDepthFeatureState(HonorAncDepth.DEEP, HonorAncDepth.entries.toSet()))
        f.binding.render()
        assertEquals("DEEP", (f.row("hyperears.honor.depth") as ListPreference).value)
        assertSame(f.screen, f.stock.parent)
        f.binding.close(); assertEquals(1, f.screen.preferenceCount)
        assertNull(depth.onPreferenceChangeListener)
    }
    @Test fun providerOffOrReplacedSessionBlocksOldDialogClicks() {
        for (mutate in listOf<(Fixture) -> Unit>(
            { it.current = it.current.withFeature(NoiseModeFeatureState(NoiseMode.OFF)) },
            { it.token = "next" }, { it.pageAddress = "another" }, { it.admitted = false }, { it.failProvider = true },
        )) {
            val f = Fixture(); f.binding.render()
            val depth = f.row("hyperears.honor.depth") as ListPreference
            mutate(f); assertFalse(depth.callChangeListener("DEEP")); assertTrue(f.sent.isEmpty())
            f.binding.render()
            if (f.current.noiseMode == NoiseMode.OFF && f.admitted && !f.failProvider && f.pageAddress == address && f.token == "token")
                assertFalse(f.row("hyperears.honor.depth")!!.isEnabled)
            f.binding.close()
        }
    }
    @Test fun reconstructedHostCanMoveCategoryAndCleanupRemovesItFromActualParent() {
        val f = Fixture(); f.binding.render()
        val original = f.row("hyperears.headset")!!
        val hidden = PreferenceCategory(f.activity).apply { key = "stock.hidden" }
        f.screen.addPreference(hidden); f.screen.removePreference(original); hidden.addPreference(original)
        f.binding.reconcile()
        assertEquals(0, hidden.preferenceCount)
        assertSame(f.screen, f.row("hyperears.headset")?.parent)
        assertEquals(1, (0 until f.screen.preferenceCount).count { f.screen.getPreference(it).key == "hyperears.headset" })
        f.admitted = false; f.binding.render()
        assertNull(f.row("hyperears.headset")); assertSame(f.screen, f.stock.parent)
    }
    @Test fun appleAdaptiveRemainsDistinctAndUsesTypedListeningControl() {
        val f = Fixture()
        f.current = f.current.withFeature(AppleListeningModeFeatureState(AppleListeningMode.ADAPTIVE, AppleListeningMode.entries.toSet()))
        f.binding.render()
        assertNull(f.row("hyperears.noise"))
        val row = f.row("hyperears.apple.listening") as ListPreference
        assertEquals(4, row.entries.size); assertEquals("ADAPTIVE", row.value)
        assertFalse(row.callChangeListener("TRANSPARENCY"))
        assertEquals(AppleControlRequest.SetListeningMode(AppleListeningMode.TRANSPARENCY), f.sent.single())
    }
    @Test fun moduleBatteryZeroShownWithoutInventingUnavailableOrAggregateComponents() {
        val f = Fixture()
        f.current = f.current.withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(0)))
        f.binding.render()
        assertEquals("0%", f.row("hyperears.battery.0")?.summary)
        assertNull(f.row("hyperears.battery.1"))
    }
    private inner class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = View(activity).also(activity::setContentView)
        val screen = PreferenceManager(activity).createPreferenceScreen(activity)
        val stock = Preference(activity).apply { key = "stock" }.also(screen::addPreference)
        var current = state(); var token = "token"; var pageAddress = address; var admitted = true; var failProvider = false
        val sent = mutableListOf<ControlRequest>()
        val env = MiLinkCardEnvironment(javaClass.classLoader!!, { current }, { _, request -> sent += request }, { _, _ -> {} }, { token })
        val host = SettingsPreferenceHost(javaClass.classLoader!!, activity, screen)
        val binding = SettingsHeadsetBinding(host, anchor, address, { pageAddress }, {
            if (failProvider) error("provider")
            if (!admitted) null else SettingsHeadsetState.Record(current, token,
                NativePresentationMetadata(HeadsetConnection("connection", 1), NativeAdmission.HYPEREARS), Binder())
        }, env)
        fun row(key: String): Preference? = screen.findPreference(key)
    }
}
