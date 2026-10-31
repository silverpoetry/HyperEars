package dev.hyperears.presentation

import java.lang.reflect.Method
import android.annotation.SuppressLint
import org.luckypray.dexkit.DexKitBridge

/** Unique operation fingerprints; no obfuscated class/method names are stored in the module. */
internal object NativeDetailNotificationResolver {
    data class Methods(
        val create: Method,
        val cancel: Method,
        val support: Method,
        val notificationType: Method,
    )

    // Independent optional features resolve once on host readiness and promptly release the DEX
    // mapping. Keeping a process-global native bridge alive would retain memory indefinitely.
    @SuppressLint("DuplicateCreateDexKit")
    fun resolve(apk: String, loader: ClassLoader): Methods {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(apk).use { dex ->
            dex.setThreadNum(1)
            fun unique(result: List<org.luckypray.dexkit.result.MethodData>): Method =
                result.single().getMethodInstance(loader).apply { isAccessible = true }
            val create = unique(dex.findMethod {
                searchPackages("com.android.bluetooth.ble.app.headset")
                matcher {
                    returnType = "void"
                    paramTypes("android.content.Context", "android.bluetooth.BluetoothDevice", "int[]", "boolean", "boolean")
                    usingEqStrings("create HeadsetNotification", "notification is not permit open",
                        "com.android.bluetooth.headset.click.detail_notification")
                }
            })
            val owner = create.declaringClass.name
            val cancel = unique(dex.findMethod {
                matcher {
                    declaredClass = owner
                    returnType = "void"
                    paramTypes("android.content.Context", "android.bluetooth.BluetoothDevice")
                    usingEqStrings("cancle headset detail notification")
                }
            })
            val support = unique(dex.findMethod {
                matcher {
                    declaredClass = owner
                    returnType = "boolean"
                    paramTypes("android.content.Context", "java.lang.String")
                    usingEqStrings("feature")
                }
            })
            val type = unique(dex.findMethod {
                searchPackages("com.android.bluetooth.ble.app")
                matcher {
                    returnType = "java.lang.String"
                    paramTypes("java.lang.String", "android.content.Context")
                    usingEqStrings("getNotifyType deviceId=", "show_new_notification_type")
                }
            })
            Methods(create, cancel, support, type)
        }
    }
}
