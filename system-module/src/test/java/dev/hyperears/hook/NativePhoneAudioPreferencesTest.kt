package dev.hyperears.hook
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class NativePhoneAudioPreferencesTest {
    @Test fun retainsTheContainerForLateNativeProfileCallbacks() {
        val context=RuntimeEnvironment.getApplication()
        val screen=PreferenceManager(context).createPreferenceScreen(context)
        val group=PreferenceCategory(context).apply{key="ldac_container"}
        screen.addPreference(group)
        NativePhoneAudioPreferences.replaceDevicePreferences(screen,javaClass.classLoader!!,true)
        val late=SwitchPreferenceCompat(context).apply{key="ldac_pre"}
        group.addPreference(late)
        assertSame(late,screen.findPreference<Preference>("ldac_pre"))
    }
    @Test fun keepsActualNativeCodecControlsAndTheirListenersAcrossRebinds() {
        val context=RuntimeEnvironment.getApplication()
        val screen=PreferenceManager(context).createPreferenceScreen(context)
        val group=PreferenceCategory(context).apply{key="ldac_container"}
        screen.addPreference(group)
        val ldac=SwitchPreferenceCompat(context).apply{key="ldac_pre"}
        val le=SwitchPreferenceCompat(context).apply{key="le_audio_pre";isEnabled=false}
        var clicks=0
        ldac.setOnPreferenceChangeListener{_,_->clicks++;true}
        group.addPreference(ldac);group.addPreference(le)
        group.addPreference(Preference(context).apply{key="xiaomi_private_command"})
        screen.addPreference(Preference(context).apply{key="vendor_ota"})
        repeat(2){NativePhoneAudioPreferences.replaceDevicePreferences(screen,javaClass.classLoader!!,true)}
        assertEquals("手机音频设置",group.title.toString())
        assertSame(ldac,screen.findPreference<Preference>("ldac_pre"))
        assertSame(le,screen.findPreference<Preference>("le_audio_pre"))
        assertFalse(le.isEnabled)
        assertTrue(ldac.callChangeListener(true));assertEquals(1,clicks)
        assertNull(screen.findPreference<Preference>("xiaomi_private_command"))
        assertNull(screen.findPreference<Preference>("vendor_ota"))
        NativePhoneAudioPreferences.replaceDevicePreferences(screen,javaClass.classLoader!!,false)
        assertEquals(0,screen.preferenceCount)
    }
}
