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
class SettingsSonyVirtualRowsTest {
    @Test fun nativeChoicesPinWholePairAndPendingRefreshDoesNotWritePreset() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(activity).createPreferenceScreen(activity)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,activity,screen)
        val address="00:11:22:33:44:55"
        val feature=SonyVirtualSoundFeatureState(SonyVirtualSoundDefinition(types=listOf(1,2)),mapOf(1 to 0,2 to 3))
        var state=EarbudState(address=address,lifecycle=DeviceLifecycle(systemProfile=SystemProfileState.CONNECTED,
            privateTransport=PrivateTransportState.CONNECTED,protocolHandshake=ProtocolHandshakeState.CONFIRMED)).withFeature(feature)
        val sent=mutableListOf<ControlRequest>()
        fun bind(){screen.removeAll();SettingsSonyVirtualRows.add(host,state,address,{screen.addPreference(it as Preference)},{state},{sent+=it})}
        bind()
        val vpt=screen.findPreference<ListPreference>("hyperears.sony.virtual.1")!!
        val position=screen.findPreference<ListPreference>("hyperears.sony.virtual.2")!!
        assertEquals(listOf("0","1","2","3","17","18"),position.entryValues.map{it.toString()})
        assertFalse(vpt.callChangeListener("4"));assertEquals("0",vpt.value)
        assertEquals(SonyControlRequest.SetVirtualSound(feature.definition!!,feature.values,1,4),sent.single())
        state=state.withFeature(feature.copy(values=mapOf(1 to 0,2 to 0)))
        position.callChangeListener("17");assertEquals(1,sent.size)
        state=state.withFeature(feature.copy(awaitingReadback=true));bind()
        assertFalse(screen.findPreference<ListPreference>("hyperears.sony.virtual.1")!!.isEnabled)
        val refresh=screen.findPreference<Preference>("hyperears.sony.virtual.refresh")!!
        refresh.onPreferenceClickListener!!.onPreferenceClick(refresh)
        assertEquals(StandardControlRequest.Refresh,sent.last())
        state=state.copy(lifecycle=state.lifecycle.copy(privateTransport=PrivateTransportState.IDLE))
        refresh.onPreferenceClickListener!!.onPreferenceClick(refresh);assertEquals(2,sent.size)
    }
}
