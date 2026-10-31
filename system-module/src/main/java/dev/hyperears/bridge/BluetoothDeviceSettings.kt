package dev.hyperears.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

internal object BluetoothDeviceSettings {
    const val ROUTE_ADDRESS = "dev.hyperears.extra.NATIVE_HEADSET_ADDRESS"
    fun open(context:Context,address:String):Boolean = openLegacy(context,address)
    fun openNative(context:Context,address:String):Boolean = openDetails(context,address,true)
    fun openLegacy(context:Context,address:String):Boolean = openDetails(context,address,false)
    private fun openDetails(context:Context,address:String,native:Boolean):Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val details = Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")
            .setPackage("com.android.settings")
            .putExtra("device_address", address)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString("device_address", address) })
            .addFlags(flags)
        if(native)details.putExtra(ROUTE_ADDRESS,address)
        return runCatching { context.startActivity(details) }.isSuccess ||
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .setPackage("com.android.settings").addFlags(flags)) }.isSuccess
    }
}
