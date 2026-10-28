package dev.hyperears.presentation

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AndroidConnectionVolumeHostTest {
    private val address="AA:BB:CC:DD:EE:01"
    private fun output(type:Int=AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,mac:String=address):AudioDeviceInfo {
        val device=AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port=ReflectionHelpers.getField<Any>(device,"mPort")
        ReflectionHelpers.setField(port,"mAddress",mac)
        return device
    }
    @Test fun routeRequiresEveryActualAudioOutputToHaveExactBluetoothAddress() {
        assertTrue(AndroidConnectionVolumeHost.exactRoute(listOf(output(mac=address.lowercase())),address))
        assertTrue(AndroidConnectionVolumeHost.exactRoute(listOf(output(),output(AudioDeviceInfo.TYPE_BLE_HEADSET)),address))
        assertFalse(AndroidConnectionVolumeHost.exactRoute(emptyList<Any>(),address))
        assertFalse(AndroidConnectionVolumeHost.exactRoute(listOf(output(),output(mac="AA:BB:CC:DD:EE:02")),address))
        assertFalse(AndroidConnectionVolumeHost.exactRoute(listOf(output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),address))
        assertFalse(AndroidConnectionVolumeHost.exactRoute(listOf(output(mac="Headset")),address))
        assertFalse(AndroidConnectionVolumeHost.exactRoute(listOf(output(),"wrong shape"),address))
    }
    @Test fun percentageConvertsWithinPlatformRangeWithoutBypassingLimits() {
        assertEquals(5,AndroidConnectionVolumeHost.volumeIndex(30,0,15))
        assertEquals(0,AndroidConnectionVolumeHost.volumeIndex(0,0,15))
        assertEquals(1,AndroidConnectionVolumeHost.volumeIndex(0,1,15))
        assertEquals(15,AndroidConnectionVolumeHost.volumeIndex(100,0,15))
        assertNull(AndroidConnectionVolumeHost.volumeIndex(101,0,15))
        assertNull(AndroidConnectionVolumeHost.volumeIndex(30,2,1))
    }
    @Test fun actualHostRechecksRouteModeManualVolumeAndCallerBeforeWritingOnlyMedia() {
        val context=RuntimeEnvironment.getApplication()
        val audio=context.getSystemService(AudioManager::class.java)
        val shadow=shadowOf(audio)
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        shadow.setStreamMaxVolume(15);shadow.setStreamMinVolume(0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,7,0)
        val ring=audio.getStreamVolume(AudioManager.STREAM_RING)
        shadow.setAudioDevicesForAttributes(attributes,ImmutableList.of(output()))
        val host=AndroidConnectionVolumeHost(context,Handler(Looper.getMainLooper()))
        var changes=0
        assertTrue(host.start{changes++})
        assertTrue(host.routeMatches(address))
        assertFalse(host.apply(address,30,7){false})
        assertEquals(7,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        assertFalse(host.apply(address,30,7){true})
        audio.mode=AudioManager.MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,8,0)
        assertFalse(host.apply(address,30,7){true})
        assertTrue(host.apply(address,30,8){true})
        assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(ring,audio.getStreamVolume(AudioManager.STREAM_RING))
        assertNull(shadow.lastAudioFocusRequest)
        assertTrue(shadow.dispatchedMediaKeyEvents.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        host.stop();val stoppedAt=changes
        shadow.addOutputDevice(output(mac="AA:BB:CC:DD:EE:02"),true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(stoppedAt,changes)
    }
}
