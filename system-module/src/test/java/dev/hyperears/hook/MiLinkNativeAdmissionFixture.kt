package com.xiaomi.mxbluetoothsdk.service

import android.bluetooth.BluetoothDevice

/** Executes the real hook's query path without installing Xposed inside the JVM. */
class MxBluetoothService {
    @JvmField var mBluetoothHeadsetService: Any? = Any()
    @JvmField val mCallbacks = mutableListOf<Any>()
    var original = 0
    var queries = 0
    var aroundOriginal: ((BluetoothDevice, () -> Int) -> Int)? = null
    fun checkIsMiTWS(device: BluetoothDevice): Int {
        queries++
        return aroundOriginal?.invoke(device) { original } ?: original
    }
    companion object { @JvmField var mMxBluetoothService = MxBluetoothService() }
}
