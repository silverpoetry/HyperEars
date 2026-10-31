package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.HeadsetFormFactor
import java.lang.reflect.Method

/** Reuses the native AirPods connection notification, with a call-scoped read-only state source. */
internal class NativeConnectionHint : HookContext() {
    private val current = ThreadLocal<NativeHeadsetProjection?>()
    private lateinit var notificationType: Class<*>
    private var singleBatteryResource: Method? = null

    override fun install() {
        notificationType = findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
        NativeMembers.method(notificationType, "showConnectToastForAirPods",
            Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
        val getter = findMethod("com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider",
            "getAirPodsState", String::class.java, Context::class.java)
        hookBefore(getter) {
            val state = current.get() ?: return@hookBefore
            if (args[0] != state.address) return@hookBefore
            // This native getter uses a different ordering from the popup Controller's getter.
            result = arrayOf("false", state.left.percent?.toString() ?: "-",
                "false", state.right.percent?.toString() ?: "-",
                state.chargingCase.percent?.toString() ?: "-", state.left.charging.toString(),
                state.right.charging.toString(), state.chargingCase.charging.toString(), "", "true")
        }
        runCatching {
            val resource = NativeMembers.method(notificationType, "getResourceUri",
                String::class.java, String::class.java)
            hookBefore(resource) {
                val state = current.get() ?: return@hookBefore
                if (state.singleBattery && state.form == HeadsetFormFactor.TWS &&
                    args[0] == "single_battery_head" && args[1] == null) {
                    result = resource.invoke(instance, state.singleBatteryHintArtwork, null)
                }
            }
            singleBatteryResource = resource
        }.onFailure { ModuleLog.debug("NativeHint", "single-battery TWS resource entry unavailable") }
    }

    fun show(projection: NativeHeadsetProjection, device: BluetoothDevice): Boolean {
        if (!projection.canShowConnectionHint) {
            ModuleLog.debug("NativeHint", "not submitted: usable ear battery unavailable " +
                "form=${projection.form} single=${projection.singleBattery}")
            return false
        }
        val native = NativeMembers.field(notificationType, "mMiuiBluetoothNotification").get(null) ?: return false
        if (projection.singleBattery && projection.form == HeadsetFormFactor.TWS &&
            runCatching { singleBatteryResource?.invoke(native, projection.singleBatteryHintArtwork, null) }
                .getOrNull() == null) {
            ModuleLog.debug("NativeHint", "not submitted: single-battery TWS resource unavailable")
            return false
        }
        val previous = current.get()
        current.set(projection)
        return try {
            NativeMembers.method(notificationType, "showConnectToastForAirPods",
                Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
                .invoke(native, 2, device, projection.nativeWearType)
            ModuleLog.debug("NativeHint", "submitted address=${maskBluetoothAddress(projection.address)} " +
                "left=${projection.left.percent} right=${projection.right.percent} single=${projection.singleBattery}")
            true
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun flowOwnsPresentation(device: BluetoothDevice): Boolean {
        val native = NativeMembers.field(notificationType, "mMiuiBluetoothNotification").get(null) ?: return true
        val managers = NativeMembers.call(native, "getConnectManagerList") as? Map<*, *> ?: return false
        val record = managers[device] ?: return false
        return NativeMembers.call(record, "getRequestFlag") == 1 ||
            NativeMembers.call(record, "getIsShowFastConnectDialog") == true ||
            NativeMembers.call(record, "getAlreadyShowFastConnectDiaolog") == true
    }
}
