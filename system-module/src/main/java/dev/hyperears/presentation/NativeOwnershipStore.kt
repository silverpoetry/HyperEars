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
 * A [resolve] failure keeps its reason: [contractClass] only reports a contract class as missing after the
 * enclosing service class and a re-probe on its loader confirm there is no ownership data plane at all, so
 * the runtime can tell that case apart from an unverified contract shape.
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
        private const val HEADSET_SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        private const val HEADSET_BINDER = "$HEADSET_SERVICE\$HeadsetBinder"
        private const val FRAGMENT_HANDLER = "com.android.bluetooth.ble.app.headset.FragmentHandler"

        fun affects(address: String, key: String?): Boolean = key == null ||
            key == "saved$address" || key == "$address-type" || key == "third_party_type$address"

        @Suppress("DEPRECATION")
        // These are the explicitly scoped host's classes, not Android framework hidden APIs.
        @SuppressLint("PrivateApi")
        fun resolve(context: Context, onChanged: (String?) -> Unit): NativeOwnershipStore {
            val loader = context.classLoader
            // Validate the semantic host contract before interpreting absence as module ownership.
            val binder = contractClass(HEADSET_BINDER, loader)
            listOf("isMiTWS", "checkIsMiTWS", "checkIsAirPods", "checkIsThirdPartyHeadset").forEach { name ->
                require(NativeMembers.method(binder, name, String::class.java).returnType ==
                    Boolean::class.javaPrimitiveType) { "Unexpected native ownership contract: $name" }
            }
            val handler = contractClass(FRAGMENT_HANDLER, loader)
            val deviceStore = NativeMembers.field(handler, "HEADSET_DEVICE_INFO").get(null) as String
            require(deviceStore == "DeviceIdCached") { "Unverified native registration store" }
            return NativeOwnershipStore(
                context.getSharedPreferences(deviceStore, Context.MODE_MULTI_PROCESS),
                context.getSharedPreferences("HEADSET_DEVICE_ID_INFO", Context.MODE_MULTI_PROCESS),
                context.getSharedPreferences("AirpodsModel", Context.MODE_MULTI_PROCESS),
                onChanged,
            )
        }

        /**
         * The injected loader is the hook process's loader, so a ClassNotFoundException there can also be a
         * class the host links lazily. The enclosing service class and a re-probe on its own loader decide
         * whether the data plane really is absent; only that verdict keeps the ClassNotFoundException, and any
         * unverifiable probe becomes a contract mismatch so ownership stays unknown.
         */
        @SuppressLint("PrivateApi")
        private fun contractClass(name: String, loader: ClassLoader): Class<*> = try {
            Class.forName(name, false, loader)
        } catch (absent: ClassNotFoundException) {
            val outer = runCatching { Class.forName(HEADSET_SERVICE, false, loader) }
            val reprobe = outer.getOrNull()?.classLoader
                ?.let { runCatching { Class.forName(name, false, it) } }
            if (isContractMissing(
                    outerClassMissing = outer.exceptionOrNull() is ClassNotFoundException,
                    reprobeClassMissing = reprobe?.exceptionOrNull() is ClassNotFoundException,
                )
            ) throw absent
            throw OwnershipContractUnverifiedException(
                "Native ownership contract class cannot be re-probed: $name", absent,
            )
        }
    }
}
