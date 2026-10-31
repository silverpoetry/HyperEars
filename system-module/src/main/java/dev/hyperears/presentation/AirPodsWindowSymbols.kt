package dev.hyperears.presentation

import android.app.Dialog
import android.app.Activity
import android.content.ServiceConnection
import android.graphics.drawable.Drawable

/** Shared preflight for both host processes. Resolution does not start services or protocols. */
internal class AirPodsWindowSymbols(private val loader: ClassLoader) {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)
    val controller = type(AirPodsWindowBridge.CONTROLLER)
    val service = type("com.android.bluetooth.ble.app.IFastConnectService")
    val callback = type("com.android.bluetooth.ble.app.IFastConnectClientCallBack")
    val create = NativeMembers.method(controller, "onActivityCreate")
    val destroy = NativeMembers.method(controller, "onActivityDestroy")
    val createDialog = NativeMembers.method(controller, "setConnectDeviceDialog", Int::class.javaPrimitiveType!!)
    val read = NativeMembers.method(controller, "getAirPodsState", String::class.java)
    val build = NativeMembers.method(type("${AirPodsWindowBridge.PREFIX}.airpods.AirPodsInfo"),
        "buildPodsStatus", String::class.java)
    val load = NativeMembers.method(controller, "loadDynamicPicture", String::class.java)
    val init = NativeMembers.method(controller, "initParameter")
    val batteryLayout = NativePopupBatteryLayout.resolve(controller, loader)
    val resourceInit = NativeMembers.method(type("${AirPodsWindowBridge.PREFIX}.MiuiFastConnectResource"),
        "initParameter", String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
        *Array(10) { Int::class.javaPrimitiveType!! },
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    val dynamic = type("${AirPodsWindowBridge.PREFIX}.Constants")
        .getDeclaredField("ENABLE_DYNAMIC_RESOURCE").apply { isAccessible = true }

    init {
        require(Dialog::class.java.isAssignableFrom(
            NativeMembers.field(type(NativeWindowRequest.ACTIVITY), "mPairingAlert").type))
        NativeMembers.method(controller, "goToSuitableDialog", Int::class.javaPrimitiveType!!)
        fun field(name: String, expected: Class<*>) {
            require(expected.isAssignableFrom(NativeMembers.field(controller, name).type))
        }
        field("mActivityContext", Activity::class.java)
        field("mFastConnectServiceConnection", ServiceConnection::class.java)
        field("mFastConnectClientCallBack", callback)
        field("mAirPodsInfo", build.returnType)
        field("mHeadsetIconDrawable", Array<Drawable>::class.java)
        field("mHeadsetBoxIconDrawable", Drawable::class.java)
        field("mLocalResource", resourceInit.declaringClass)
        val record = NativeMembers.field(controller, "mCloudResRecord").type
        require(NativeMembers.field(record, "mLayout").type == Int::class.javaPrimitiveType)
        require(NativeMembers.field(record, "mTwsHeadset").type == Boolean::class.javaPrimitiveType)
        require(NativeMembers.field(record, "mAllowSync").type == Boolean::class.javaPrimitiveType)
        require(NativeMembers.field(record, "mDeviceName").type == String::class.java)
        NativeMembers.field(record, "mRecordDocument")
        require(read.returnType == Array<String>::class.java)
        require(init.returnType == Boolean::class.javaPrimitiveType)
        require(load.returnType == Boolean::class.javaPrimitiveType)
        service.getMethod("registerForAirPods", callback)
        service.getMethod("unregisterForAirPods", callback)
        require(service.getMethod("getDeviceConnectStateForAirPods", String::class.java)
            .returnType == Int::class.javaPrimitiveType)
        service.getMethod("notifyActivityIsFrontForAirPods", Boolean::class.javaPrimitiveType)
        callback.getMethod("closeDialogForAirPods", String::class.java)
        callback.getMethod("deviceBatteryStateUpdate", String::class.java,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
}
