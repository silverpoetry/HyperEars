package plugin.settings.java.airpods
import android.bluetooth.BluetoothDevice
class AppleTestActivity(private val device: BluetoothDevice) { fun getDevice() = device }
class MiuiAirpodsFragment(private val activity: AppleTestActivity, val mDevice: BluetoothDevice) {
    fun getActivity() = activity
}
