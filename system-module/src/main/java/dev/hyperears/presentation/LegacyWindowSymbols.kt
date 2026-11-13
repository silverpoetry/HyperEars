package dev.hyperears.presentation

import android.app.Activity
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Message
import android.view.View
import java.lang.reflect.Modifier

/** Named members from the audited SDK-35 feature plugin; no obfuscated aliases or view searches. */
internal class LegacyWindowSymbols(private val loader: ClassLoader) : NativeWindowSymbols {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)
    override val kind = NativeWindowKind.LEGACY_XIAOMI
    val base = type("${NativeWindowBridge.PREFIX}.MiuiFastConnectController")
    override val controller = type(CONTROLLER)
    val activity = type(NativeWindowRequest.ACTIVITY)
    val factory = type("${NativeWindowBridge.PREFIX}.MiuiFastConnectControllerFactory")
    val createController = NativeMembers.method(factory, "getController")
    val info = type("${NativeWindowBridge.PREFIX}.MiuiHeadsetInfo")
    override val service = type("com.android.bluetooth.ble.app.IFastConnectService")
    override val callback = type("com.android.bluetooth.ble.app.IFastConnectClientCallBack")
    override val create = NativeMembers.method(base, "onActivityCreate")
    override val destroy = NativeMembers.method(base, "onActivityDestroy")
    override val contentReady = NativeMembers.method(base, "setFindDeviceDialog")
    override val load = NativeMembers.method(base, "loadDynamicPicture", String::class.java)
    override val init = NativeMembers.method(controller, "initParameter")
    override val resourceInit = NativeMembers.method(type("${NativeWindowBridge.PREFIX}.MiuiFastConnectResource"),
        "initParameter", String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
        *Array(10) { Int::class.javaPrimitiveType!! }, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    override val dynamic = NativeMembers.field(type("${NativeWindowBridge.PREFIX}.Constants"), "ENABLE_DYNAMIC_RESOURCE")
    val parse = NativeMembers.method(base, "parseIntentAdvData", Intent::class.java)
    val valid = NativeMembers.method(info, "isValidAdvData")
    val deviceId = NativeMembers.method(info, "getDeviceId")
    val updateTws = NativeMembers.method(info, "updateTwsConnectState")
    val setAdvertisement = NativeMembers.method(info, "setAdvData", ByteArray::class.java)
    val selectDialog = NativeMembers.method(controller, "goToSuitableDialog")
    val registerReceiver = NativeMembers.method(base, "initBroadcastReceiver")
    val startPair = NativeMembers.method(base, "startPairAndConnectLocal", Long::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    val renderConnected = NativeMembers.method(base, "updateConnectSuccessDilog", Message::class.java,
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    val nativeBatteryCallback = NativeMembers.method(controller, "updateConnectSuccessDilog", Message::class.java,
        Boolean::class.javaPrimitiveType!!)
    val renderBattery = NativeMembers.method(controller, "setConnectSuccessLayoutDetail", View::class.java)
    val modifyView = NativeMembers.method(base, "modifyView", View::class.java, Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!, String::class.java)
    val resources = LegacyPopupBatteryLayout()
    val stringSetters = listOf("setCurrentDeviceAddress", "setPeerDeviceAddress").associateWith {
        NativeMembers.method(info, it, String::class.java)
    }
    val intSetters = listOf("setCurrentConnectState", "setPeerConnectState", "setBatteryLeftPercent",
        "setBatteryRightPercent", "setBatteryBoxPercent").associateWith {
        NativeMembers.method(info, it, Int::class.javaPrimitiveType!!)
    }
    val flagSetters = listOf("setTwsHeadset", "setTwsConnected", "setLeftConnected", "setRightConnected",
        "setBatteryLeftCharge", "setBatteryRightCharge", "setBatteryBoxCharge").associateWith {
        NativeMembers.method(info, it, Boolean::class.javaPrimitiveType!!)
    }

    init {
        require(base.isAssignableFrom(controller) && createController.returnType == base)
        controller.getConstructor(activity)
        require(NativeMembers.field(factory, "mActivityContext").type == activity)
        require(Activity::class.java.isAssignableFrom(NativeMembers.field(base, "mActivityContext").type))
        require(ServiceConnection::class.java.isAssignableFrom(NativeMembers.field(base, "mFastConnectServiceConnection").type))
        require(NativeMembers.field(base, "mHeadsetInfo").type == info)
        require(NativeMembers.field(base, "mFastConnectClientCallBack").type == callback)
        require(NativeMembers.field(base, "mHeadsetIconDrawable").type == Array<Drawable>::class.java)
        require(Handler::class.java.isAssignableFrom(NativeMembers.field(base, "mMainHandler").type))
        NativeMembers.field(base, "mMainHandlerLock")
        require(View::class.java.isAssignableFrom(NativeMembers.field(base, "mView").type))
        require(resourceInit.declaringClass.isAssignableFrom(NativeMembers.field(base, "mLocalResource").type))
        require(NativeMembers.field(base, "mFastConnectService").type == service)
        listOf("mDeviceId", "mNewExposeUI", "mDeviceNameForDialog", "mDeviceNameForDialogLocal",
            "mStringResetHeadset").forEach { require(NativeMembers.field(base, it).type == String::class.java) }
        listOf("mPairFirstTime", "mNeedNewExposeUI", "mCanRunSuccessAnimation").forEach {
            require(NativeMembers.field(base, it).type == Boolean::class.javaPrimitiveType)
        }
        listOf("mTextHeadset", "mTextResetHeadset").forEach {
            require(NativeMembers.field(base, it).type == Int::class.javaPrimitiveType)
        }
        listOf("mBatteryIconIds", "mBatteryDarkIconIds").forEach {
            require(NativeMembers.field(base, it).type == IntArray::class.java)
        }
        val record = NativeMembers.field(base, "mCloudResRecord").type
        require(NativeMembers.field(record, "mLayout").type == Int::class.javaPrimitiveType)
        listOf("mTwsHeadset", "mAllowSync").forEach {
            require(NativeMembers.field(record, it).type == Boolean::class.javaPrimitiveType)
        }
        require(NativeMembers.field(record, "mDeviceName").type == String::class.java)
        require(NativeMembers.field(record, "mRecordDocument").type == org.w3c.dom.Document::class.java)
        require(Modifier.isStatic(dynamic.modifiers) && dynamic.type == Boolean::class.javaPrimitiveType)
        require(valid.returnType == Boolean::class.javaPrimitiveType && init.returnType == Boolean::class.javaPrimitiveType)
        require(deviceId.returnType == String::class.java && renderConnected.returnType == Void.TYPE)
        require(resourceInit.returnType == Void.TYPE && load.returnType == Boolean::class.javaPrimitiveType)
        service.getMethod("register", callback)
        service.getMethod("unregister", callback)
        require(service.getMethod("getDeviceConnectState", String::class.java, Boolean::class.javaPrimitiveType)
            .returnType == Int::class.javaPrimitiveType)
        service.getMethod("notifyActivityIsFront", Boolean::class.javaPrimitiveType)
        service.getMethod("syncCloudResourceByDeviceId", String::class.java, Int::class.javaPrimitiveType)
        callback.getMethod("closeDialog")
        (stringSetters.values + intSetters.values + flagSetters.values).forEach { require(it.returnType == Void.TYPE) }
    }

    companion object { const val CONTROLLER = "${NativeWindowBridge.PREFIX}.MiuiControllerCommon" }
}
