package dev.hyperears.presentation

import java.lang.reflect.Field
import java.lang.reflect.Method

internal enum class NativeWindowKind { AIRPODS, LEGACY_XIAOMI }

/** Native window variants share lifecycle and authority, while retaining their own state ABI. */
internal interface NativeWindowSymbols {
    val kind: NativeWindowKind
    val controller: Class<*>
    val service: Class<*>
    val callback: Class<*>
    val create: Method
    val destroy: Method
    val contentReady: Method
    val load: Method
    val init: Method
    val resourceInit: Method
    val dynamic: Field
}

internal data class NativeWindowServiceContract(
    val register: String,
    val unregister: String,
    val connectedState: String,
    val foreground: String,
    val close: String,
    val hasPeerArgument: Boolean = false,
    val closeWithAddress: Boolean = true,
    val ignoredResourceCalls: Set<String> = emptySet(),
) {
    fun connectionState(address: String?, active: Boolean, arguments: Array<out Any?>?): Int =
        if (active && !address.isNullOrEmpty() && arguments?.getOrNull(0) == address &&
            (!hasPeerArgument || arguments.getOrNull(1) == false)) 2 else 0

    companion object {
        val AIRPODS = NativeWindowServiceContract("registerForAirPods", "unregisterForAirPods",
            "getDeviceConnectStateForAirPods", "notifyActivityIsFrontForAirPods", "closeDialogForAirPods")
        val LEGACY = NativeWindowServiceContract("register", "unregister", "getDeviceConnectState",
            "notifyActivityIsFront", "closeDialog", hasPeerArgument = true,
            closeWithAddress = false,
            ignoredResourceCalls = setOf("syncCloudResourceByDeviceId"))
    }
}
