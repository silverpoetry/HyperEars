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
    @Test fun existingCeramicsUClassOffersSourceLanguageWithItsOwnPresentation() {
        val a=RoseCeramicsUAdapter(sourceQualified=true)
        val body=byteArrayOf(0xDD.toByte(),0,2,0x31,0)
        a.receive(body+byteArrayOf(body.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte()))
        val f=Fixture();f.current=state(a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!).copy(adapter=a.snapshot())
        f.render();f.row("language")!!.callChangeListener("1")
        assertEquals(RoseRsV2ControlRequest.SetLanguage(Model.CERAMICS_U_SOURCE.name,1),f.sent.single())
        assertEquals(RoseCeramicsUAdapter.ID,f.current.adapter!!.id)
        assertEquals(listOf("弱水经典","原声再现","古典乐器","日系甜美"),Model.EARFEEL_I7_SOURCE.eqPresets.map { RoseRsV2DailyLabels.equalizer(Model.EARFEEL_I7_SOURCE,it) })
    }
    @Test fun nativeDepthUsesFiveThreeOneAndBlocksStaleOffOrOtherModel() {
        val f=Fixture();f.current=state(RoseRsV2SettingsFeatureState(Model.CERAMICS_U_HSC_2710.name,ancLevel=1,transparencyLevel=5)).withFeature(NoiseModeFeatureState(NoiseMode.ANC))
        f.render();val anc=f.row("anc_level")!!;val trans=f.row("transparency_level")!!
        assertEquals(listOf("5","3","1"),anc.entryValues.map(CharSequence::toString))
        assertEquals(listOf("5","1"),trans.entryValues.map(CharSequence::toString))
        assertTrue(anc.isEnabled);assertFalse(trans.isEnabled)
        anc.callChangeListener("5");assertEquals(RoseRsV2ControlRequest.SetNoiseLevel(Model.CERAMICS_U_HSC_2710.name,false,5),f.sent.single())
        assertEquals("1",anc.value)
        f.current=f.current.withFeature(NoiseModeFeatureState(NoiseMode.OFF))
        anc.callChangeListener("3");assertEquals(1,f.sent.size)
        f.current=f.current.withFeature(NoiseModeFeatureState(NoiseMode.TRANSPARENCY))
        trans.callChangeListener("3");assertEquals(1,f.sent.size)
    }
    @Test fun languageAndWearNativeChoicesKeepReportedStateAndRejectChangedProfile() {
        val f=Fixture();f.current=state(RoseRsV2SettingsFeatureState(Model.ZIRCON.name,language=0,wearDetection=true))
        f.render();val language=f.row("language")!!;val wear=f.row("wear")!!
        assertEquals(listOf("中文","English"),language.entries.map(CharSequence::toString))
        assertFalse(language.callChangeListener("1"));assertEquals("0",language.value)
        assertEquals(RoseRsV2ControlRequest.SetLanguage(Model.ZIRCON.name,1),f.sent.last())
        assertFalse(wear.callChangeListener("0"));assertEquals("1",wear.value)
        assertEquals(RoseRsV2ControlRequest.SetWearDetection(Model.ZIRCON.name,false),f.sent.last())
        f.current=state(RoseRsV2SettingsFeatureState(Model.ASTRO_X.name,wearDetection=true))
        language.callChangeListener("1");wear.callChangeListener("0");assertEquals(2,f.sent.size)
    }
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
