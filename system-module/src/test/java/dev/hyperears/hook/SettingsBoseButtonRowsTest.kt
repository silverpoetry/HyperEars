package dev.hyperears.hook
import android.app.Activity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SettingsBoseButtonRowsTest {
    @Test fun actualNativeChoicePinsReportAndKeepsConfirmationReadOnly() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val button=BoseButtonSetting(0x4020,16,4,1,listOf(6,0,0,0))
        var state=EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
            privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED))
            .withFeature(BoseDailySettingsFeatureState(buttons=listOf(button)))
        val sent=mutableListOf<ControlRequest>()
        SettingsBoseButtonRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})
        val row=screen.findPreference<ListPreference>("hyperears.bose.button.16.4")!!
        assertEquals(listOf("1","2"),row.entryValues.map{it.toString()})
        assertFalse(row.callChangeListener("2"))
        assertEquals(BoseDailyControlRequest.SetButton(button,2),sent.single())
        assertEquals("1",row.value)
        state=state.withFeature(BoseDailySettingsFeatureState(buttons=listOf(button.copy(mask=listOf(2,0,0,0)))))
        row.callChangeListener("2");assertEquals(1,sent.size)
        state=state.withFeature(BoseDailySettingsFeatureState(buttons=listOf(button))).copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        row.callChangeListener("2");assertEquals(1,sent.size)
    }
    @Test fun reportedUnconfiguredValueIsLabeledWithoutMakingItWritable() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val button=BoseButtonSetting(0x4020,16,4,0,listOf(6,0,0,0))
        val state=EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
            privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED))
            .withFeature(BoseDailySettingsFeatureState(buttons=listOf(button)))
        SettingsBoseButtonRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{fail("unsupported choice")})
        val row=screen.findPreference<ListPreference>("hyperears.bose.button.16.4")!!
        assertTrue(row.summary.toString().contains("当前：未配置"))
        assertFalse(row.entryValues.any{it.toString()=="0"})
        row.callChangeListener("0")
    }
}
