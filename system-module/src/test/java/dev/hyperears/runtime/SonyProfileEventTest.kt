package dev.hyperears.runtime
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import dev.hyperears.integration.SonyLeAudioIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SonyProfileEventTest {
    @Test fun actualManagerInterceptsClassicDisconnectOnlyWhileLeStillExists() {
        val manager=EarbudConnectionManager(RuntimeEnvironment.getApplication())
        try {
            val field=manager.javaClass.getDeclaredField("sonyPresence").apply{isAccessible=true}
            val presence=field.get(manager) as SonyLeAudioPresence
            val classic="AA:BB:CC:DD:EE:01";val le="AA:BB:CC:DD:EE:02"
            presence.learn(classic,SonyLeAudioIdentity(classic,listOf(le)))
            presence.a2dp(classic,true);presence.leAudio(le,true)
            val device=BluetoothAdapter.getDefaultAdapter().getRemoteDevice(classic)
            assertTrue(manager.onSonyA2dpState(device,BluetoothProfile.STATE_DISCONNECTING))
            assertTrue(manager.onSonyA2dpState(device,BluetoothProfile.STATE_DISCONNECTED))
            assertEquals(le,presence.route(classic)?.controlAddress)
            presence.leAudio(le,false)
            assertFalse(manager.onSonyA2dpState(device,BluetoothProfile.STATE_DISCONNECTED))
            manager.forgetSonyBond(device)
            assertNull(presence.classicFor(le))
        } finally {manager.close()}
    }
}
