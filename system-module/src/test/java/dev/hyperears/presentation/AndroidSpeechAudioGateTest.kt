package dev.hyperears.presentation

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.google.common.collect.ImmutableList
import dev.hyperears.settings.BatterySpeechMode
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
class AndroidSpeechAudioGateTest {
    private val address="AA:BB:CC:DD:EE:01"
    private fun output(mac:String=address):AudioDeviceInfo {
        val result=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(result,"mPort"),"mAddress",mac)
        return result
    }
    @Test fun speechAndMediaRoutesBothMustMatchWhileDndMuteCallsRingerAndScreenModeAreRespected() {
        val context=RuntimeEnvironment.getApplication()
        val audio=context.getSystemService(AudioManager::class.java)
        val notifications=context.getSystemService(NotificationManager::class.java)
        val power=context.getSystemService(PowerManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.mode=AudioManager.MODE_NORMAL;audio.ringerMode=AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0)
        val media=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val speech=AndroidSpeechAudioGate.speechAttributes()
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,speech.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,speech.contentType)
        shadowOf(audio).setAudioDevicesForAttributes(media,ImmutableList.of(output()))
        shadowOf(audio).setAudioDevicesForAttributes(speech,ImmutableList.of(output()))
        val gate=AndroidSpeechAudioGate(context,Handler(Looper.getMainLooper()))
        assertTrue(gate.allowed(address,BatterySpeechMode.ON))
        shadowOf(audio).setAudioDevicesForAttributes(speech,ImmutableList.of(output("AA:BB:CC:DD:EE:02")))
        assertFalse(gate.allowed(address,BatterySpeechMode.ON))
        shadowOf(audio).setAudioDevicesForAttributes(speech,ImmutableList.of(output()))
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertFalse(gate.allowed(address,BatterySpeechMode.ON))
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);assertFalse(gate.allowed(address,BatterySpeechMode.ON))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0)
        shadowOf(audio).setIsStreamMute(AudioManager.STREAM_MUSIC,true);assertFalse(gate.allowed(address,BatterySpeechMode.ON))
        shadowOf(audio).setIsStreamMute(AudioManager.STREAM_MUSIC,false)
        audio.mode=AudioManager.MODE_IN_COMMUNICATION;assertFalse(gate.allowed(address,BatterySpeechMode.ON));audio.mode=AudioManager.MODE_NORMAL
        audio.ringerMode=AudioManager.RINGER_MODE_VIBRATE;assertFalse(gate.allowed(address,BatterySpeechMode.ON));audio.ringerMode=AudioManager.RINGER_MODE_NORMAL
        shadowOf(power).setIsInteractive(true);assertFalse(gate.allowed(address,BatterySpeechMode.SCREEN_OFF))
        shadowOf(power).setIsInteractive(false);assertTrue(gate.allowed(address,BatterySpeechMode.SCREEN_OFF))
        assertFalse(gate.allowed(address,BatterySpeechMode.OFF))
        var callbacks=0;assertTrue(gate.watch{callbacks++})
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle()
        assertTrue(callbacks>0)
        gate.close();val before=callbacks
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before,callbacks)
    }
    @Test fun deniedFocusIsNotQueuedAndGrantedFocusIsExplicitlyReleased() {
        val context=RuntimeEnvironment.getApplication();val audio=context.getSystemService(AudioManager::class.java)
        val gate=AndroidSpeechAudioGate(context,Handler(Looper.getMainLooper()))
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(gate.requestFocus{})
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(gate.requestFocus{})
        gate.abandonFocus();gate.close()
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertTrue(shadowOf(audio).dispatchedMediaKeyEvents.isEmpty())
    }
}
