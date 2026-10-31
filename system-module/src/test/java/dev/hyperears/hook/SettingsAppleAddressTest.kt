package dev.hyperears.hook
import android.bluetooth.BluetoothAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import plugin.settings.java.airpods.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAppleAddressTest {
    @Test fun nativeAppleUsesBoundDeviceWithoutGenericSettingsMethods() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val first = adapter.getRemoteDevice("00:11:22:33:44:55")
        val second = adapter.getRemoteDevice("00:11:22:33:44:66")
        val method = SettingsHeadsetHook::class.java.getDeclaredMethod("address", Any::class.java).apply { isAccessible = true }
        val hook = SettingsHeadsetHook()
        assertEquals(first.address, method.invoke(hook, MiuiAirpodsFragment(AppleTestActivity(first), first)))
        assertNull(method.invoke(hook, MiuiAirpodsFragment(AppleTestActivity(first), second)))
    }
}
