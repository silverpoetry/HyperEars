package dev.hyperears.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/** Runs under the Bluetooth/Xiaomi host's permissions, not the module application's manifest. */
internal object BluetoothHostIdentity {
    @SuppressLint("MissingPermission")
    fun isPaired(device: BluetoothDevice): Boolean = try {
        device.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: SecurityException) {
        false
    }
}
