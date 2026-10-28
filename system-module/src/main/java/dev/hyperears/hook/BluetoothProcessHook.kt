package dev.hyperears.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import dev.hyperears.integration.EarbudAdapterRegistry
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
                    handleA2dpState(device, currentState)
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
        if (state == BluetoothProfile.STATE_DISCONNECTING ||
            state == BluetoothProfile.STATE_DISCONNECTED
        ) {
            EarbudSessionService.unregisterDevice(device)
            return
        }
        if (state != BluetoothProfile.STATE_CONNECTED) return
        runCatching { device.address }.getOrNull()?.let(EarbudSessionService::onPhysicalConnected)
        val identity = device.toEarbudIdentity()
        val address = runCatching { device.address }.getOrNull()
        if (PlatformReservedHeadsetPolicy.isAppleCandidate(identity)) {
            EarbudSessionService.observeDevice(device, identity)
            return
        }
        if (ModuleRuntimeGate.paused) return
        if (PlatformReservedHeadsetPolicy.reserves(identity)) {
            ModuleLog.debug(
                "Bluetooth",
                "platform-reserved address=${maskBluetoothAddress(address)}",
            )
            return
        }
        val earbudAdapter = EarbudAdapterRegistry.forIntegration(
            identity = identity,
            disabledAdapterIds = ModuleSettingsRuntime.current.disabledAdapterIds,
        )
        if (earbudAdapter == null) {
            EarbudSessionService.observeDevice(device, identity)
            ModuleLog.debug(
                "Bluetooth",
                "A2DP state=$state no eligible adapter; observed address=" +
                    maskBluetoothAddress(address),
            )
            return
        }
        ModuleLog.debug(
            "Bluetooth",
            "A2DP state=$state adapter=${earbudAdapter.id} " +
                "address=${maskBluetoothAddress(address)}",
        )
        EarbudSessionService.registerDevice(device, identity, earbudAdapter)
    }
}
