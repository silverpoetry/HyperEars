package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import java.io.Closeable
import java.lang.ref.WeakReference

/** Delegates Apple popup construction to the installed native helper, retaining its display record. */
internal class NativeApplePopupLauncher : HookContext() {
    private data class Submission(val request: NativeWindowRequest, val device: BluetoothDevice, var launched: Boolean = false)
    private val current = ThreadLocal<Submission?>()
    private val creatingRecord = ThreadLocal<BluetoothDevice?>()
    private lateinit var helperType: Class<*>
    private lateinit var helperInstance: java.lang.reflect.Field
    override fun install() {
        helperType = findClass("${AirPodsWindowBridge.PREFIX}.airpods.MiuiAirPodsFastConnectHelper")
        helperInstance = NativeMembers.field(helperType, "sInstance").also {
            check(java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == helperType)
        }
        NativeMembers.method(helperType, "showFastConnectDialog", Int::class.javaPrimitiveType!!, BluetoothDevice::class.java)
        val recordType = findClass("${helperType.name}\$DeviceInfoRecord")
        val observerType = findClass("${helperType.name}\$BLEBatteryHelper")
        val observerField = NativeMembers.field(recordType, "mBLEBatteryHelper")
        check(observerField.type == observerType)
        val observerConstructor = observerType.getDeclaredConstructor(WeakReference::class.java).apply { isAccessible = true }
        val recordConstructor = recordType.getDeclaredConstructor(helperType, BluetoothDevice::class.java, WeakReference::class.java)
        module.hook(recordConstructor).intercept { chain ->
            val original = chain.proceed()
            val device = creatingRecord.get()
            if (device != null && chain.args[1] == device && observerField.get(chain.thisObject) == null) {
                // Native saveScanResult dereferences every record's observer without a null check.
                // A disconnected weak reference makes needDecodeBattery return false: AAP remains
                // the source of state, and no native BLE parser, channel, or model preference is initialized.
                observerField.set(chain.thisObject, observerConstructor.newInstance(WeakReference<Any>(null)))
            }
            original
        }
        hookBefore(ContextWrapper::class.java.getMethod("startActivity", Intent::class.java)) {
            val submission = current.get() ?: return@hookBefore
            val intent = args[0] as? Intent ?: return@hookBefore
            if (intent.component?.className != NativeWindowRequest.ACTIVITY ||
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) != submission.device) return@hookBefore
            NativeWindowRequest.launchIntent(instance as Context, submission.request, intent)
            submission.launched = true
            result = null
        }
    }
    fun launch(request: NativeWindowRequest, device: BluetoothDevice): Closeable {
        // The native factory requires its live MiuiFastConnectService, not an application Context.
        // Borrow only the initialized singleton; never construct a helper with a missing service.
        val helper = requireNotNull(helperInstance.get(null)) { "Native display helper is not initialized" }
        val handler = NativeMembers.call(helper, "getHandler") as? Handler ?: error("Native display helper is not ready")
        val get = NativeMembers.method(helperType, "getDevRecord", BluetoothDevice::class.java)
        val prior = get.invoke(helper, device)
        val owned = if (prior == null) {
            creatingRecord.set(device)
            try { NativeMembers.method(helperType, "createDevRecord", BluetoothDevice::class.java).invoke(helper, device) }
            finally { creatingRecord.remove() }
        } else null
        check(prior != null || owned != null) { "Native display record unavailable" }
        val lease = Closeable {
            if (owned != null) handler.post {
                runCatching {
                    // A real native connection event can adopt this record; never remove an adopted record.
                    if (get.invoke(helper, device) === owned && NativeMembers.get(owned, "sCurrentConnectState") == 0) {
                        NativeMembers.method(helperType, "removeDevRecord", BluetoothDevice::class.java).invoke(helper, device)
                    }
                }.onFailure { ModuleLog.warn("NativeWindow", "display record cleanup failed", it) }
            }
        }
        val previous = current.get()
        val submission = Submission(request, device)
        current.set(submission)
        try {
            NativeMembers.method(helperType, "showFastConnectDialog", Int::class.javaPrimitiveType!!, BluetoothDevice::class.java)
                .invoke(helper, 1, device)
            check(submission.launched) { "Native helper did not launch the requested popup" }
            return lease
        } catch (error: Throwable) {
            lease.close()
            throw error
        } finally { current.set(previous) }
    }
}
