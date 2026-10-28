package dev.hyperears.hook
import android.app.Activity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SettingsBoseAudioRowsTest {
    @Test fun nativeSpatialWindAndRenamePinReportsWithoutOptimism() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val snapshot=BoseLiveAudioSettings(0x4082,listOf(7,1,0,1,0))
        var state=EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
            privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED))
            .withFeature(BoseDailySettingsFeatureState(liveAudio=snapshot))
        val sent=mutableListOf<ControlRequest>()
        SettingsBoseAudioRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})
        val spatial=screen.findPreference<ListPreference>("hyperears.bose.spatial")!!
        assertFalse(spatial.callChangeListener("2"));assertEquals("0",spatial.value)
        assertEquals(BoseDailyControlRequest.SetSpatial(snapshot,2),sent.single())
        val wind=screen.findPreference<SwitchPreferenceCompat>("hyperears.bose.wind")!!
        assertFalse(wind.callChangeListener(false));assertTrue(wind.isChecked)
        assertEquals(BoseDailyControlRequest.SetWind(snapshot,false),sent.last())
        state=state.withFeature(BoseDailySettingsFeatureState(liveAudio=snapshot.copy(raw=listOf(7,0,0,1,0))))
        spatial.callChangeListener("2");wind.callChangeListener(false);assertEquals(2,sent.size)
        state=state.copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        spatial.callChangeListener("1");assertEquals(2,sent.size)
        state=state.copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.CONNECTED))
            .withFeature(BoseDailySettingsFeatureState(deviceName=BoseDeviceName(0x4039,0,"Fargo")))
        SettingsBoseAudioRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})
        val name=screen.findPreference<EditTextPreference>("hyperears.bose.device_name")!!
        assertTrue(name.isEnabled);assertEquals("Fargo",name.text)
        assertFalse(name.callChangeListener("é".repeat(16)));assertEquals(2,sent.size)
        assertFalse(name.callChangeListener("New"));assertEquals(3,sent.size);assertEquals("Fargo",name.text)
        assertEquals(BoseDailyControlRequest.Rename(BoseDeviceName(0x4039,0,"Fargo"),"New"),sent.last())
        state=state.withFeature(BoseDailySettingsFeatureState(deviceName=BoseDeviceName(0x4039,0,"Other")))
        name.callChangeListener("Again");assertEquals(3,sent.size)
        state=state.copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        name.callChangeListener("Again");assertEquals(3,sent.size)
        spatial.callChangeListener("2");assertEquals(3,sent.size)
    }
}
