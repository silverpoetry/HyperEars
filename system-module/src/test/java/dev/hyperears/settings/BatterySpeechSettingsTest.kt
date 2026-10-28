package dev.hyperears.settings

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class BatterySpeechSettingsTest {
    @Test fun allSpeechActionsDefaultOffAndEveryVisibleChoicePersistsWithoutChangingVolumePreset() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("speech-test",0)
        prefs.edit().clear().commit()
        val defaults=ModuleSettingsStore.read(prefs)
        assertEquals(BatterySpeechMode.OFF,defaults.speechMode)
        assertFalse(defaults.speechLowBattery);assertFalse(defaults.speechOnConnection)
        assertEquals(50,defaults.speechVolume.percent)
        for(mode in BatterySpeechMode.entries)for(volume in BatterySpeechVolume.entries) {
            val next=defaults.copy(speechMode=mode,speechVolume=volume,speechOnConnection=true,speechLowBattery=true,
                connectionVolume=ConnectionVolumePreset.P20)
            assertTrue(ModuleSettingsStore.write(prefs,next));assertEquals(next,ModuleSettingsStore.read(prefs))
        }
        assertEquals("屏幕关闭时",BatterySpeechMode.SCREEN_OFF.title)
        assertEquals(BatterySpeechMode.OFF,BatterySpeechMode.fromStoredValue("unknown"))
        assertEquals(BatterySpeechVolume.P50,BatterySpeechVolume.fromStoredValue("unknown"))
    }
}
