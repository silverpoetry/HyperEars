package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.HeadsetFormFactor
import java.lang.reflect.Method

/**
 * Reuses the native AirPods connection notification, with a call-scoped read-only state source. The
 * MiuiBluetoothNotification class and its toast method are hard prerequisites, so a build without them
 * keeps the whole subsystem unavailable. The AirPods state source is optional: a build can render the
 * native toast while having no OfflineFindProvider at all, and there the hook is skipped instead of
 * losing the island entry too.
 */
internal class NativeConnectionHint : HookContext() {
    private val current = ThreadLocal<NativeHeadsetProjection?>()
    private lateinit var notificationType: Class<*>
    private var singleBatteryResource: Method? = null

    override fun install() {
        notificationType = findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
        NativeMembers.method(notificationType, "showConnectToastForAirPods",
            Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
        // The static singleton holder is a hard prerequisite as a member, not as a value: the host creates
        // that instance itself, so an absent value stays show()'s own "no instance yet" refusal. A build
        // without the field cannot support this subsystem at all, and install() must fail rather than let
        // the lookup throw later from inside the presentation path.
        NativeMembers.field(notificationType, "mMiuiBluetoothNotification")
        runCatching {
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
        }.onFailure { ModuleLog.debug("NativeHint", "airpods state source hook unavailable") }
        // Without that hook the native toast reads its own state source, so on such a build it may show
        // empty or stale battery, or refuse to render for a device it does not classify as AirPods. This is
        // deliberately a real-device probe and does not substitute another host data source.
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
            // The native toast can still reach one of its own missing classes on a partially available
            // build, so a failure is reported here instead of escaping into the presentation host.
            val submitted = runCatching {
                NativeMembers.method(notificationType, "showConnectToastForAirPods",
                    Int::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
                    .invoke(native, 2, device, if (projection.singleBattery) 3 else 0)
            }
            if (submitted.isFailure) {
                ModuleLog.debug("NativeHint", "not submitted: native toast failed " +
                    "${submitted.exceptionOrNull()?.javaClass?.name}")
                false
            } else {
                ModuleLog.debug("NativeHint", "submitted address=${maskBluetoothAddress(projection.address)} " +
                    "left=${projection.left.percent} right=${projection.right.percent} single=${projection.singleBattery}")
                true
            }
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /**
     * Whether the native circulation flow currently owns the display, or null when neither the legacy
     * record getters nor the stable wrappers can be read on this build (indeterminate, never a block).
     * The legacy names are tried first so old builds keep their exact verdict; the public wrappers are
     * the fallback for builds whose record class only exposes the renamed accessors (island-spec §8).
     */
    fun flowOwnsPresentation(device: BluetoothDevice): Boolean? {
        val native = NativeMembers.field(notificationType, "mMiuiBluetoothNotification").get(null) ?: return true
        val managers = NativeMembers.call(native, "getConnectManagerList") as? Map<*, *> ?: return false
        val record = managers[device] ?: return false
        val legacy = runCatching {
            Triple(NativeMembers.call(record, "getRequestFlag") as? Int,
                NativeMembers.call(record, "getIsShowFastConnectDialog") as? Boolean,
                NativeMembers.call(record, "getAlreadyShowFastConnectDiaolog") as? Boolean)
        }
        val legacyFailure = legacy.exceptionOrNull()
        if (legacyFailure == null) return flowOwnsFromReadings(legacy.getOrThrow(), null)
        if (legacyFailure !is NoSuchMethodException) throw legacyFailure
        // This build has no legacy record names. The stable public wrappers take the device directly
        // (island-spec §8); the second name keeps the host's own spelling.
        val wrappers = runCatching {
            Pair(NativeMembers.method(native.javaClass, "getShowDialogFromConnectManager",
                BluetoothDevice::class.java).invoke(native, device) as? Boolean,
                NativeMembers.method(native.javaClass, "getAlreadyShowDiaologFromConnectManager",
                    BluetoothDevice::class.java).invoke(native, device) as? Boolean)
        }
        val wrapperFailure = wrappers.exceptionOrNull()
        if (wrapperFailure != null && wrapperFailure !is NoSuchMethodException) throw wrapperFailure
        return flowOwnsFromReadings(null, wrappers.getOrNull())
    }
}
