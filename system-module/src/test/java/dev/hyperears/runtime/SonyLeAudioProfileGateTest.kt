package dev.hyperears.runtime

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SonyLeAudioProfileGateTest {
    private class Backend : SonyLeAudioProfileGate.Backend {
        var enabled=true; var supported=true; var accepted=true
        var bindings=0
        var listener: BluetoothProfile.ServiceListener?=null
        val released=mutableListOf<BluetoothProfile>()
        override fun usable()=enabled && supported
        override fun bind(listener:BluetoothProfile.ServiceListener):Boolean { bindings++;this.listener=listener;return accepted }
        override fun release(profile:Int,proxy:BluetoothProfile) { released+=proxy }
    }
    private fun proxy()=object:BluetoothProfile {
        override fun getConnectedDevices()=emptyList<BluetoothDevice>()
        override fun getDevicesMatchingConnectionStates(states:IntArray)=emptyList<BluetoothDevice>()
        override fun getConnectionState(device:BluetoothDevice)=BluetoothProfile.STATE_DISCONNECTED
    }
    @Test fun readinessRequiresRealServiceCallbackAndAlwaysReleasesProxy() {
        val b=Backend();val result=mutableListOf<Boolean>()
        val gate=SonyLeAudioProfileGate(b,Handler(Looper.getMainLooper()),100)
        gate.request(result::add);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,b.bindings);assertTrue(result.isEmpty())
        val p=proxy();b.listener!!.onServiceConnected(BluetoothProfile.LE_AUDIO,p)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true),result);assertEquals(listOf(p),b.released)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(listOf(true),result)
    }
    @Test fun cancellationTimeoutAndLateCallbacksNeverReleaseCommands() {
        val b=Backend();val result=mutableListOf<Boolean>()
        val gate=SonyLeAudioProfileGate(b,Handler(Looper.getMainLooper()),100)
        val cancel=gate.request(result::add);shadowOf(Looper.getMainLooper()).idle()
        cancel.close();shadowOf(Looper.getMainLooper()).idle()
        b.listener!!.onServiceConnected(BluetoothProfile.LE_AUDIO,proxy());shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.isEmpty());assertEquals(1,b.released.size)
        gate.request(result::add);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(false),result)
        b.listener!!.onServiceConnected(BluetoothProfile.LE_AUDIO,proxy());shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false),result);assertEquals(2,b.released.size)
    }
    @Test fun ownerClosureCancelsQueuedAndInflightChecks() {
        val b=Backend();val result=mutableListOf<Boolean>()
        val gate=SonyLeAudioProfileGate(b,Handler(Looper.getMainLooper()),100)
        gate.request(result::add);shadowOf(Looper.getMainLooper()).idle()
        gate.request(result::add)
        gate.close()
        b.listener!!.onServiceConnected(BluetoothProfile.LE_AUDIO,proxy())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(result.isEmpty());assertEquals(1,b.bindings);assertEquals(1,b.released.size)
        gate.request(result::add);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,b.bindings)
    }
    @Test fun bluetoothTurningOffBeforeCallbackCannotReportReady() {
        val b=Backend();val result=mutableListOf<Boolean>()
        SonyLeAudioProfileGate(b,Handler(Looper.getMainLooper()),100).request(result::add)
        shadowOf(Looper.getMainLooper()).idle();b.enabled=false
        b.listener!!.onServiceConnected(BluetoothProfile.LE_AUDIO,proxy())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false),result);assertEquals(1,b.released.size)
    }
    @Test fun unsupportedOffOrRejectedBindingFailsWithoutPolling() {
        for (mode in 0..2) {
            val b=Backend().apply { supported=mode!=0; enabled=mode!=1;accepted=mode!=2 }
            val result=mutableListOf<Boolean>()
            SonyLeAudioProfileGate(b,Handler(Looper.getMainLooper()),100).request(result::add)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(listOf(false),result);assertEquals(if(mode==2)1 else 0,b.bindings)
        }
    }
}
