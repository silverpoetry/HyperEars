package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.hyperears.hook.ModuleLog
import java.io.Closeable

/**
 * Read-only view of the native host's ownership records. These are the stores read by its
 * isMiTWS/checkIsMiTWS, checkIsAirPods and checkIsThirdPartyHeadset implementations. Reading in
 * the owning process avoids the Binder caller allowlist and needs no MiLink process or binding.
 * Any native registration reserves the device, including IDs outside MiLink's narrower catalog.
 */
internal class NativeOwnershipStore private constructor(
    private val devices: SharedPreferences,
    private val fallbackDevices: SharedPreferences,
    private val apple: SharedPreferences,
    private val onChanged: (String?) -> Unit,
) : Closeable {
    private val stores = listOf(devices, fallbackDevices, apple)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChanged(key) }

    init { stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) } }

    fun read(address: String): NativeAdmission = runCatching {
        NativeOwnershipRecords(
            deviceId = devices.getString("saved$address", "").orEmpty(),
            fallbackDeviceId = fallbackDevices.getString("saved$address", "").orEmpty(),
            appleType = apple.getString("$address-type", "").orEmpty(),
            thirdPartyType = devices.getString("third_party_type$address", "").orEmpty(),
        ).admission
    }.getOrElse {
        ModuleLog.warn("NativeOwnership", "unable to read native registration", it)
        NativeAdmission.UNKNOWN
    }

    override fun close() {
        stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        fun affects(address: String, key: String?): Boolean = key == null ||
            key == "saved$address" || key == "$address-type" || key == "third_party_type$address"

        @Suppress("DEPRECATION")
        // These are the explicitly scoped host's classes, not Android framework hidden APIs.
        @SuppressLint("PrivateApi")
        fun resolve(context: Context, onChanged: (String?) -> Unit): NativeOwnershipStore {
            val loader = context.classLoader
            // Validate the semantic host contract before interpreting absence as module ownership.
            val binder = Class.forName(
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService\$HeadsetBinder", false, loader,
            )
            listOf("isMiTWS", "checkIsMiTWS", "checkIsAirPods", "checkIsThirdPartyHeadset").forEach { name ->
                require(NativeMembers.method(binder, name, String::class.java).returnType ==
                    Boolean::class.javaPrimitiveType) { "Unexpected native ownership contract: $name" }
            }
            val handler = Class.forName(
                "com.android.bluetooth.ble.app.headset.FragmentHandler", false, loader,
            )
            val deviceStore = NativeMembers.field(handler, "HEADSET_DEVICE_INFO").get(null) as String
            require(deviceStore == "DeviceIdCached") { "Unverified native registration store" }
            return NativeOwnershipStore(
                context.getSharedPreferences(deviceStore, Context.MODE_MULTI_PROCESS),
                context.getSharedPreferences("HEADSET_DEVICE_ID_INFO", Context.MODE_MULTI_PROCESS),
                context.getSharedPreferences("AirpodsModel", Context.MODE_MULTI_PROCESS),
                onChanged,
            )
        }
    }
}
