package dev.hyperears.hook
import android.content.Context
import android.os.Binder
import android.view.View
import androidx.preference.PreferenceManager
import androidx.preference.ListPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAppleBindingTest {
    @Test fun ordinarySettingsRetainsReportedThreeModeControl() = checkModes(false)
    @Test fun nativeAppleKeepsItsOwnThreeModeControl() = checkModes(true)
    private fun checkModes(nativeApple: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        context.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val screen = PreferenceManager(context).createPreferenceScreen(context)
        val address = "00:11:22:33:44:55"
        val state = EarbudState(adapter = AppleAirPodsAdapter().snapshot(), address = address,
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.CONNECTED, protocolHandshake = ProtocolHandshakeState.CONFIRMED))
            .withFeature(AppleListeningModeFeatureState(AppleListeningMode.ANC,
                setOf(AppleListeningMode.OFF, AppleListeningMode.ANC, AppleListeningMode.TRANSPARENCY)))
        val record = SettingsHeadsetState.Record(state, "session", NativePresentationMetadata(HeadsetConnection("connection", 1)), Binder())
        val loader = javaClass.classLoader!!
        val binding = SettingsHeadsetBinding(SettingsPreferenceHost(loader, context, screen), View(context), address,
            { address }, { record }, MiLinkCardEnvironment(loader, { state }, { _, _ -> }, { _, _ -> {} }), nativeApple = nativeApple)
        binding.render()
        val row = screen.findPreference<ListPreference>("hyperears.apple.listening")
        if (nativeApple) { assertNull(row); binding.close(); return }
        assertNotNull(row)
        assertArrayEquals(arrayOf("OFF", "ANC", "TRANSPARENCY"), row!!.entryValues.map { it.toString() }.toTypedArray())
        binding.close()
    }
}
