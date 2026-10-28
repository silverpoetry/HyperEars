package dev.hyperears.hook

import android.app.Activity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RoseRsV2NativeRowsTest {
    private val address="00:11:22:33:44:55"
    private fun state(feature:RoseRsV2SettingsFeatureState)=EarbudState(address=address,
        adapter=RoseRsV2Adapter(Model.OPENFEEL_L).snapshot().copy(presentationId=RoseRsV2PresentationIds.SETTINGS),
        lifecycle=DeviceLifecycle(SystemProfileState.CONNECTED,PrivateTransportState.CONNECTED,ProtocolHandshakeState.CONFIRMED))
        .withFeature(feature)
    @Test fun hscGainOnlyAndPerProfileEqLabelsKeepSourceOrdering() {
        val f=Fixture();f.current=state(RoseRsV2SettingsFeatureState(Model.CERAMICS_U_HSC_2710.name,gain=1))
        f.render();assertNotNull(f.row("gain"));assertNull(f.row("hearing"))
        f.row("gain")!!.callChangeListener("2")
        assertEquals(RoseRsV2ControlRequest.SetGain(Model.CERAMICS_U_HSC_2710.name,2),f.sent.single())
        assertEquals(listOf("经典调音","流行","高保真","摇滚"),Model.CERAMICS_U_HSC_2710.eqPresets.map { RoseRsV2DailyLabels.equalizer(Model.CERAMICS_U_HSC_2710,it) })
        assertEquals(listOf("高保真","流行"),Model.FIRST_ANGEL.eqPresets.map { RoseRsV2DailyLabels.equalizer(Model.FIRST_ANGEL,it) })
    }
    @Test fun actualNativeChoicesUseSourceLabelsAndKeepReportedSelection() {
        val f=Fixture();f.render()
        val gain=f.row("gain")!!;val hearing=f.row("hearing")!!
        assertEquals(listOf("关闭","75 dB","80 dB","85 dB","90 dB","95 dB","100 dB"),hearing.entries.map(CharSequence::toString))
        assertFalse(gain.callChangeListener("2"));assertEquals("0",gain.value)
        assertEquals(RoseRsV2ControlRequest.SetGain(Model.OPENFEEL_L.name,2),f.sent.last())
        assertFalse(hearing.callChangeListener("6"));assertEquals("1",hearing.value)
        assertEquals(RoseRsV2ControlRequest.SetHearingProtection(Model.OPENFEEL_L.name,6),f.sent.last())
        assertFalse(hearing.callChangeListener("7"));assertEquals(2,f.sent.size)
    }
    @Test fun staleModelOrExternalOwnerCannotReuseOldChoices() {
        val f=Fixture();f.render();val old=f.row("gain")!!
        f.current=state(RoseRsV2SettingsFeatureState(Model.ASTRO_X.name,gain=0,hearingProtection=1))
        old.callChangeListener("2");assertTrue(f.sent.isEmpty())
        f.current=state(RoseRsV2SettingsFeatureState(Model.OPENFEEL_L.name,gain=0,hearingProtection=1)).let {
            it.copy(lifecycle=it.lifecycle.copy(controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=ControlAppCatalog.roseLink))
        }
        old.callChangeListener("2");assertTrue(f.sent.isEmpty())
    }
    @Test fun unreportedOrWrongProfileDoesNotCreateSettingsRows() {
        val f=Fixture();f.current=state(RoseRsV2SettingsFeatureState(Model.OPENFEEL_L.name));f.render()
        assertNull(f.row("gain"));assertNull(f.row("hearing"))
        val wrong=Fixture();wrong.current=state(RoseRsV2SettingsFeatureState(Model.MIRROR.name,gain=0,hearingProtection=1));wrong.render()
        assertNull(wrong.row("gain"));assertNull(wrong.row("hearing"))
    }
    private inner class Fixture {
        val context=Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen=PreferenceManager(context).createPreferenceScreen(context)
        val host=SettingsPreferenceHost(javaClass.classLoader!!,context,screen)
        val group=host.category("test","Test").also {host.add(screen,it)}
        var current=state(RoseRsV2SettingsFeatureState(Model.OPENFEEL_L.name,gain=0,hearingProtection=1))
        val sent=mutableListOf<ControlRequest>()
        fun render()=SettingsRoseRsV2Rows.add(host,current,address,{host.add(group,it)},{current},{sent+=it})
        fun row(key:String):ListPreference?=screen.findPreference<Preference>("hyperears.rsv2.$key") as? ListPreference
    }
}
