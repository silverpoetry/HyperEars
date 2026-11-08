package dev.hyperears.presentation

import android.bluetooth.BluetoothDevice
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.os.PowerManager
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import java.lang.reflect.Method

/** Reuses the audited Xiaomi parameter factory and connected renderer on the native Handler. */
internal class LegacyNativeConnectionHint(
    private val context: Context,
    private val notificationType: Method,
    private val inlineBoundaries: List<Method>,
) : HookContext(), NativeConnectionPresenter {
    private class Call(val projection: NativeHeadsetProjection) { var dispatched = false }
    private val current = ThreadLocal<Call?>()
    private lateinit var host: Class<*>
    private lateinit var factory: Method
    private lateinit var textType: Class<*>
    private lateinit var resource: Method
    private val preparedHandlers = mutableSetOf<Class<*>>()
    private var connectedMessage = 0

    override fun install() {
        host = findClass(NativeHostResolver.NOTIFICATION)
        textType = findClass("com.xiaomi.dist.statusbar.StatusBarGuideParams\$TextParams")
        textType.getConstructor(String::class.java, Int::class.javaObjectType)
        factory = NativeMembers.method(host, "getParams", Context::class.java, String::class.java,
            Int::class.javaPrimitiveType!!, String::class.java, Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, textType, textType, String::class.java, String::class.java)
        connectedMessage = NativeMembers.field(host, "MSG_SHOW_CONNECTED_TOAST").getInt(null)
        require(connectedMessage == 104)
        require(Handler::class.java.isAssignableFrom(NativeMembers.field(host, "mHandler").type))
        require(NativeMembers.field(host, "mAddress").type == String::class.java)
        require(NativeMembers.field(host, "mCurrentDeviceId").type == String::class.java)
        require(module.deoptimize(factory)) { "Native connected parameter factory unavailable" }
        inlineBoundaries.forEach { require(module.deoptimize(it)) { "Native rendering predicate unavailable" } }
        resource = NativeMembers.method(host, "getResourceUri", String::class.java, String::class.java)
        hookBefore(notificationType) {
            val call = current.get() ?: return@hookBefore
            if (args[0] == "") result = if (call.projection.singleBattery) "singlebatteryheadset" else null
        }
        hookAfter(NativeMembers.method(host, "invokeStatusBar", Context::class.java, String::class.java, Bundle::class.java)) {
            if (args[1] == "strong_toast_action") current.get()?.dispatched = true
        }
    }

    @SuppressLint("DiscouragedApi") // Audited native color names; the host owns palette and serialization.
    override fun show(
        projection: NativeHeadsetProjection,
        device: BluetoothDevice,
        latest: () -> NativeHeadsetProjection?,
    ): NativePresentationResult {
        if (!projection.canShowConnectionHint) return NativePresentationResult.NO_BATTERY
        val native = NativeMembers.field(host, "mMiuiBluetoothNotification").get(null)
            ?: return NativePresentationResult.HOST_UNAVAILABLE
        val handler = NativeMembers.get(native, "mHandler") as? Handler
            ?: return NativePresentationResult.HOST_UNAVAILABLE
        if (handler.javaClass !in preparedHandlers) {
            if (!module.deoptimize(NativeMembers.method(handler.javaClass, "handleMessage", Message::class.java))) {
                return NativePresentationResult.HOST_UNAVAILABLE
            }
            preparedHandlers += handler.javaClass
        }
        val names = if (projection.singleBattery) listOf("single_battery_earphone")
            else listOf("earphone_left_inear", "earphone_right_inear")
        if (names.any { resource.invoke(native, it, "") == null }) return NativePresentationResult.RESOURCE_UNAVAILABLE
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        val accepted = handler.post {
            val p = latest()?.takeIf { it.canShowConnectionHint && it.address == projection.address }
                ?: return@post
            if (SystemClock.elapsedRealtime() > deadline ||
                !context.getSystemService(PowerManager::class.java).isInteractive ||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                BluetoothDevice::class.java.getMethod("isConnected").invoke(device) != true) return@post
            val previousCall = current.get()
            val previousAddress = NativeMembers.get(native, "mAddress")
            val previousDeviceId = NativeMembers.get(native, "mCurrentDeviceId")
            val call = Call(p)
            current.set(call)
            try {
                // Empty device ID selects built-in artwork. No Xiaomi identity or cloud resource
                // record is created. The native factory owns both toast and island serialization.
                val colorId = context.resources.getIdentifier("miui_notification_white", "color", context.packageName)
                check(colorId != 0) { "Native connected text color missing" }
                val redId = context.resources.getIdentifier("miui_notification_red", "color", context.packageName)
                check(redId != 0) { "Native connected low-battery color missing" }
                fun text(percent: Int?): Any? = percent?.let {
                    val color = context.getColor(if (it <= 20) redId else colorId)
                    textType.getConstructor(String::class.java, Int::class.javaObjectType).newInstance("$it%", color)
                }
                val tablet = NativeMembers.method(host, "isTablet").invoke(native) as Boolean
                val params = factory.invoke(native, context, "", 1, p.name, tablet, 3,
                    if (p.singleBattery) null else text(p.left.percent),
                    text(if (p.singleBattery) p.left.percent else p.right.percent),
                    "earphone_left_inear", "earphone_right_inear")
                check(params != null) { "Native connected parameters unavailable" }
                NativeMembers.set(native, "mAddress", p.address)
                NativeMembers.set(native, "mCurrentDeviceId", "")
                // The original case 104 retains main-user, connection and popup occupancy checks,
                // the five-second lifetime and native throttling for devices without wear reports.
                val message = Message.obtain(handler, connectedMessage, 4, 0, params)
                try { handler.handleMessage(message) } finally { message.recycle() }
                ModuleLog.debug("NativeLegacyHint") {
                    "rendered address=${maskBluetoothAddress(p.address)} submitted=${call.dispatched} " +
                        "single=${p.singleBattery} left=${p.left.percent} right=${p.right.percent}"
                }
            } catch (error: Throwable) {
                ModuleLog.warn("NativeLegacyHint", "native connected rendering failed", error)
            } finally {
                NativeMembers.set(native, "mAddress", previousAddress)
                NativeMembers.set(native, "mCurrentDeviceId", previousDeviceId)
                if (previousCall == null) current.remove() else current.set(previousCall)
            }
        }
        return if (accepted) NativePresentationResult.SUBMITTED else NativePresentationResult.HOST_UNAVAILABLE
    }
}
