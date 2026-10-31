package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Verified OS3/OS4 operations. Obfuscated names are results, never matching criteria. */
@SuppressLint("PrivateApi") // Classes belong to the explicitly scoped Xiaomi app, not Android hidden APIs.
internal object NativeHostResolver {
    data class Core(
        val ownership: Result<NativeOwnershipStore>,
        val airPodsState: Result<Method>,
        val flow: Result<NativePresentationFlow>,
    )

    fun resolve(context: Context, onOwnershipChanged: (String?) -> Unit): Core {
        val loader = context.classLoader
        val stable = Core(
            ownership = runCatching { stableOwnership(context, onOwnershipChanged) },
            airPodsState = runCatching {
                NativeMembers.method(Class.forName(OFFLINE_PROVIDER, false, loader),
                    "getAirPodsState", String::class.java, Context::class.java).also {
                    require(Modifier.isStatic(it.modifiers) && it.returnType == Array<String>::class.java)
                }
            },
            flow = runCatching {
                val record = Class.forName("$NOTIFICATION\$ConnectManager", false, loader)
                val getter = NativeMembers.method(record, "getRequestFlag")
                require(getter.returnType == Int::class.javaPrimitiveType && !Modifier.isStatic(getter.modifiers))
                NativePresentationFlow(loader, getter)
            },
        )
        if (stable.ownership.isSuccess && stable.airPodsState.isSuccess && stable.flow.isSuccess) return stable
        return runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                dex.setThreadNum(1)
                Core(
                    ownership = stable.ownership.recoverCatching {
                        verifyOwnershipReaders(dex)
                        NativeOwnershipStore.openVerified(context, onOwnershipChanged)
                    },
                    airPodsState = stable.airPodsState.recoverCatching { airPodsState(dex, loader) },
                    flow = stable.flow.recoverCatching { resolveFlow(dex, loader) },
                )
            }
        }.getOrElse { error ->
            Core(stable.ownership.recoverCatching { throw error },
                stable.airPodsState.recoverCatching { throw error }, stable.flow.recoverCatching { throw error })
        }
    }

    private fun verifyOwnershipReaders(dex: DexKitBridge) {
        // Validate actual identity readers, not a permission-filtered Binder call. Both formats
        // read the same stores; absence only authorizes display after this proof succeeds.
        dex.findMethod {
            matcher {
                declaredClass = HEADSET_SERVICE
                returnType = "boolean"
                paramTypes("java.lang.String")
                usingEqStrings("BluetoothHeadsetService", "isMiTWS: deviceId = ",
                    "DeviceIdCached", "HEADSET_DEVICE_ID_INFO", "saved")
            }
        }.single()
        dex.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("java.lang.String", "android.content.Context")
                usingEqStrings("OfflineFindProvider", "isAirPods: model = ", "AirpodsModel", "-type")
            }
        }.single()
    }

    private fun airPodsState(dex: DexKitBridge, loader: ClassLoader): Method = dex.findMethod {
        matcher {
            returnType = "java.lang.String[]"
            paramTypes("java.lang.String", "android.content.Context")
            usingEqStrings("OfflineFindProvider", "getAirPodsState: state = ", "AirpodsModel", "-state", "\\|")
        }
    }.single().reflected(loader).also { require(Modifier.isStatic(it.modifiers)) }

    private fun stableOwnership(context: Context, onChanged: (String?) -> Unit): NativeOwnershipStore {
        val binder = Class.forName("$HEADSET_SERVICE\$HeadsetBinder", false, context.classLoader)
        listOf("isMiTWS", "checkIsMiTWS", "checkIsAirPods", "checkIsThirdPartyHeadset").forEach { name ->
            require(NativeMembers.method(binder, name, String::class.java).returnType == Boolean::class.javaPrimitiveType)
        }
        val handler = Class.forName("com.android.bluetooth.ble.app.headset.FragmentHandler", false, context.classLoader)
        require(NativeMembers.field(handler, "HEADSET_DEVICE_INFO").get(null) == "DeviceIdCached")
        return NativeOwnershipStore.openVerified(context, onChanged)
    }

    private fun resolveFlow(dex: DexKitBridge, loader: ClassLoader): NativePresentationFlow {
        val insertion = dex.findMethod {
            matcher {
                declaredClass = NOTIFICATION
                name = "addConnectManagerList"
                returnType = "void"
                paramCount = 2
            }
        }.single()
        require(insertion.paramTypeNames.first() == BluetoothDevice::class.java.name)
        val recordType = insertion.paramTypeNames[1]
        val handler = dex.findMethod {
            matcher {
                name = "handleMessage"
                returnType = "void"
                paramTypes("android.os.Message")
                usingEqStrings("MiuiBluetoothNotification", "addConnectManager : connectManager is exist.",
                    "com.xiaomi.bluetooth.headset.autoswitch.connect")
            }
        }.single()
        // In the verified handler this is the only zero-argument int read from the exact record
        // type accepted by addConnectManagerList: requestFlag. connectCount is only written here.
        val requestFlag = handler.invokes.filter {
            it.declaredClassName == recordType && it.paramCount == 0 && it.returnTypeName == "int"
        }.distinctBy(MethodData::descriptor).single().reflected(loader)
        require(!Modifier.isStatic(requestFlag.modifiers))
        return NativePresentationFlow(loader, requestFlag)
    }

    private fun MethodData.reflected(loader: ClassLoader): Method =
        getMethodInstance(loader).apply { isAccessible = true }

    private const val HEADSET_SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
    private const val OFFLINE_PROVIDER = "com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider"
    internal const val NOTIFICATION = "com.android.bluetooth.ble.app.MiuiBluetoothNotification"
}
