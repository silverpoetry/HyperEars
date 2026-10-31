package dev.hyperears.hook

import java.lang.reflect.Method
import android.annotation.SuppressLint
import org.luckypray.dexkit.DexKitBridge

internal object AppleNativeConnectionResolver {
    const val STORAGE = "com.xiaomi.bluetooth.modules.airlibrary.db.AirLocalStorage"
    // Independent optional features resolve once on host readiness and promptly release the DEX
    // mapping. Keeping a process-global native bridge alive would retain memory indefinitely.
    @SuppressLint("DuplicateCreateDexKit")
    fun resolve(apk: String, loader: ClassLoader): Method {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(apk).use { dex ->
            dex.setThreadNum(1)
            dex.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("android.bluetooth.BluetoothDevice")
                    usingEqStrings("AirCoreManager", "onDeviceConnected not target devices", "ConnectL2cap")
                    addInvoke("Lcom/xiaomi/bluetooth/modules/airlibrary/db/AirLocalStorage;->isFeatureSupport(Landroid/content/Context;Landroid/bluetooth/BluetoothDevice;Ljava/lang/String;)Z")
                }
            }.single().getMethodInstance(loader).apply { isAccessible = true }
        }
    }
}
