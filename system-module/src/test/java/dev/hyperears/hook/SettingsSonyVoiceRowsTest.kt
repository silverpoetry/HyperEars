package dev.hyperears.hook
import android.app.Activity
import androidx.preference.*
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],manifest=Config.NONE)
class SettingsSonyVoiceRowsTest {
    @Test fun nativeSwitchStaysReportedAndRechecksCurrentOwnerDefinition() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val feature=SonyVoiceGuidanceFeatureState(SonyVoiceGuidanceDefinition(),false)
        var state=EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
            privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED)).withFeature(feature)
        val sent=mutableListOf<ControlRequest>()
        SettingsSonyVoiceRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})
        val toggle=screen.findPreference<SwitchPreferenceCompat>("hyperears.sony.voice_guidance")!!
        assertFalse(toggle.callChangeListener(true));assertFalse(toggle.isChecked)
        assertEquals(SonyControlRequest.SetVoiceGuidance(SonyVoiceGuidanceDefinition(),false,true),sent.single())
        state=state.withFeature(feature.copy(enabled=true));toggle.callChangeListener(true);assertEquals(1,sent.size)
        state=state.withFeature(feature).copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        toggle.callChangeListener(true);assertEquals(1,sent.size)
        state=state.withFeature(feature.copy(definition=SonyVoiceGuidanceDefinition(version=2)))
        assertFalse(SonyCardPolicy.available(state,address))
    }
}
