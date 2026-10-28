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
class ConnectionVolumeSettingsTest {
    @Test fun defaultsOffAndRoundTripsEveryVisibleChoiceWithoutChangingOtherSettings() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("connection-volume-test",0)
        prefs.edit().clear().commit()
        assertEquals(ConnectionVolumePreset.OFF,ModuleSettingsStore.read(prefs).connectionVolume)
        assertNull(ConnectionVolumePreset.OFF.percent)
        assertEquals((0..100 step 10).toList(),ConnectionVolumePreset.entries.mapNotNull{it.percent})
        assertEquals(ConnectionVolumePreset.OFF,ConnectionVolumePreset.fromStoredValue("unexpected"))
        ConnectionVolumePreset.entries.forEach {choice->
            val settings=ModuleSettings(connectionVolume=choice,headsetDetailNotification=true,
                headsetConnectionPresentation=HeadsetConnectionPresentation.SYSTEM_HINT)
            assertTrue(ModuleSettingsStore.write(prefs,settings))
            assertEquals(settings,ModuleSettingsStore.read(prefs))
            assertTrue(choice.title.isNotBlank())
        }
        prefs.edit().putString("connection_volume","invalid").commit()
        assertEquals(ConnectionVolumePreset.OFF,ModuleSettingsStore.read(prefs).connectionVolume)
    }
}
