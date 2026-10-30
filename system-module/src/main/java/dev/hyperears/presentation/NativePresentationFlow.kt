package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.lang.reflect.Method

/** Uses the host's semantic wrapper methods; only requestFlag needs a validated DEX relation. */
internal class NativePresentationFlow(loader: ClassLoader, private val requestFlag: Method) {
    @SuppressLint("PrivateApi") // The scoped Xiaomi app's own class, not a framework private API.
    private val type = Class.forName(NativeHostResolver.NOTIFICATION, false, loader)
    private val instance = NativeMembers.field(type, "mMiuiBluetoothNotification")
    private val records = NativeMembers.method(type, "getConnectManagerList")
    private val showing = NativeMembers.method(type, "getShowDialogFromConnectManager", BluetoothDevice::class.java)
    private val shown = NativeMembers.method(type, "getAlreadyShowDiaologFromConnectManager", BluetoothDevice::class.java)

    init {
        require(Map::class.java.isAssignableFrom(records.returnType))
        require(showing.returnType == Boolean::class.javaPrimitiveType)
        require(shown.returnType == Boolean::class.javaPrimitiveType)
    }

    fun read(device: BluetoothDevice): NativeFlowState? {
        val native = instance.get(null) ?: return null
        val record = (records.invoke(native) as Map<*, *>)[device] ?: return NativeFlowState()
        require(requestFlag.declaringClass.isInstance(record))
        return NativeFlowState(
            connecting = requestFlag.invoke(record) == 1,
            showing = showing.invoke(native, device) == true,
            shownBefore = shown.invoke(native, device) == true,
        )
    }
}
