package dev.hyperears.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import dev.hyperears.integration.PlatformReservedHeadsetPolicy
import dev.hyperears.bridge.ModuleRuntimeGate
import dev.hyperears.runtime.EarbudSessionService
import dev.hyperears.runtime.toEarbudIdentity
import dev.hyperears.settings.ModuleSettingsRuntime

internal class BluetoothProcessHook : HookContext() {
    override fun install() {
        runCatching {
            hookAfter(
                findMethod(
                    "com.android.bluetooth.btservice.AdapterService",
                    "onCreate",
                ),
            ) {
                EarbudSessionService.onCreate(instance as? Context)
            }
        }.onFailure {
            ModuleLog.warn("Bluetooth", "AdapterService.onCreate hook unavailable", it)
        }

        runCatching {
            hookBefore(
                findMethod(
                    "com.android.bluetooth.btservice.AdapterService",
                    "onDestroy",
                ),
            ) {
                EarbudSessionService.onDestroy()
            }
        }.onFailure {
            ModuleLog.warn("Bluetooth", "AdapterService.onDestroy hook unavailable", it)
        }

        runCatching {
            hookAfter(
                findMethodByParamCount(
                    "com.android.bluetooth.a2dp.A2dpService",
                    "handleConnectionStateChanged",
                    3,
                ),
            ) {
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookAfter
                val previousState = args.getOrNull(1) as? Int ?: return@hookAfter
                val currentState = args.getOrNull(2) as? Int ?: return@hookAfter
                if (previousState == currentState) return@hookAfter

                val dispatch = Runnable {
                    runBluetoothCallback({ error ->
                        ModuleLog.warn("Bluetooth", "Module profile callback failed; preserving Bluetooth host", error)
                    }) { handleA2dpState(device, currentState) }
                }
                val handler = runCatching { getObjectField(instance, "mHandler") as? Handler }
                    .getOrNull()
                if (handler != null) handler.post(dispatch) else dispatch.run()
            }
        }.onFailure {
            ModuleLog.warn("Bluetooth", "A2dpService state hook unavailable", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleA2dpState(
        device: BluetoothDevice,
        state: Int,
    ) {
        if (EarbudSessionService.onSonyA2dpState(device, state)) return
        if (state == BluetoothProfile.STATE_DISCONNECTING ||
            state == BluetoothProfile.STATE_DISCONNECTED
        ) {
            EarbudSessionService.unregisterDevice(device)
            return
        }
        if (state != BluetoothProfile.STATE_CONNECTED) return
        runCatching { device.address }.getOrNull()?.let(EarbudSessionService::onPhysicalConnected)
        if (ModuleRuntimeGate.paused) return

        val identity = device.toEarbudIdentity()
        val address = runCatching { device.address }.getOrNull()
        if (PlatformReservedHeadsetPolicy.reserves(identity)) {
            ModuleLog.debug(
                "Bluetooth",
                "platform-reserved address=${maskBluetoothAddress(address)}",
            )
            return
        }
        val registered = EarbudSessionService.registerDevice(device, identity)
        ModuleLog.debug(
            "Bluetooth",
            "A2DP state=$state registered=$registered " +
                "forced=${address?.let { ModuleSettingsRuntime.current.forcedAdapterIds[it] }} " +
                "address=${maskBluetoothAddress(address)}",
        )
    }
}
