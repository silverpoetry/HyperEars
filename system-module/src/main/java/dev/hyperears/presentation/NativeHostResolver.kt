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
        val connectionHint: Result<NativeHintSource>,
        val flow: Result<NativePresentationFlow>,
        val legacyVerified: Boolean,
        val legacyFingerprint: String?,
    )

    fun resolve(context: Context, onOwnershipChanged: (String?) -> Unit): Core {
        val loader = context.classLoader
        val legacy = LegacyNativeHostContract.inspect(context)
        val legacyVerified = legacy.verified
        val stable = Core(
            ownership = runCatching { stableOwnership(context, onOwnershipChanged) },
            connectionHint = runCatching {
                NativeMembers.method(Class.forName(OFFLINE_PROVIDER, false, loader),
                    "getAirPodsState", String::class.java, Context::class.java).also {
                    require(Modifier.isStatic(it.modifiers) && it.returnType == Array<String>::class.java)
                }.let(NativeHintSource::AirPods)
            },
            flow = runCatching {
                val record = Class.forName("$NOTIFICATION\$ConnectManager", false, loader)
                val getter = NativeMembers.method(record, "getRequestFlag")
                require(getter.returnType == Int::class.javaPrimitiveType && !Modifier.isStatic(getter.modifiers))
                NativePresentationFlow(loader, getter)
            },
            legacyVerified = legacyVerified,
            legacyFingerprint = legacy.digest,
        )
        if (stable.ownership.isSuccess && stable.connectionHint.isSuccess && stable.flow.isSuccess) return stable
        return runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                dex.setThreadNum(1)
                Core(
                    ownership = stable.ownership.recoverCatching {
                        verifyOwnershipReaders(dex, legacyVerified)
                        NativeOwnershipStore.openVerified(context, onOwnershipChanged)
                    },
                    connectionHint = stable.connectionHint.recoverCatching {
                        if (legacyVerified) {
                            legacyHint(dex, loader)
                        } else {
                            NativeHintSource.AirPods(airPodsState(dex, loader))
                        }
                    },
                    flow = stable.flow.recoverCatching { resolveFlow(dex, loader) },
                    legacyVerified = legacyVerified,
                    legacyFingerprint = legacy.digest,
                )
            }
        }.getOrElse { error ->
            Core(stable.ownership.recoverCatching { throw error },
                stable.connectionHint.recoverCatching { throw error }, stable.flow.recoverCatching { throw error },
                legacyVerified, legacy.digest)
        }
    }

    private fun legacyHint(dex: DexKitBridge, loader: ClassLoader): NativeHintSource.LegacyXiaomi {
        val predicate = dex.findMethod {
            matcher {
                declaredClass = NOTIFICATION
                name = "isSupportShowNotificationForSpecialHeadsets"
                returnType = "boolean"
                paramTypes("java.lang.String", "android.content.Context")
                usingEqStrings("singlebatteryheadset")
            }
        }.singleNativeContract("native single-battery renderer predicate")
        // R8's direct access bridge can contain an inlined predicate. Resolve this exact call
        // relation, never an obfuscated name or an unrelated signature scan.
        val accessors = predicate.callers.filter {
            it.declaredClassName == NOTIFICATION && it.returnTypeName == "boolean" &&
                it.paramTypeNames == listOf(NOTIFICATION, "java.lang.String", "android.content.Context")
        }
        return NativeHintSource.LegacyXiaomi(NativeDetailNotificationResolver.notificationType(dex, loader),
            (listOf(predicate) + accessors).distinctBy(MethodData::descriptor).map { it.reflected(loader) })
    }

    private fun verifyOwnershipReaders(dex: DexKitBridge, legacyVerified: Boolean) {
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
        }.singleNativeContract("Xiaomi registration reader")
        // This audited host has only the Xiaomi registration schema. A missing Apple reader in
        // any other build remains a failure; it can never silently authorize a device.
        if (legacyVerified) return
        dex.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes("java.lang.String", "android.content.Context")
                usingEqStrings("OfflineFindProvider", "isAirPods: model = ", "AirpodsModel", "-type")
            }
        }.singleNativeContract("Apple registration reader")
    }

    private fun airPodsState(dex: DexKitBridge, loader: ClassLoader): Method = dex.findMethod {
        matcher {
            returnType = "java.lang.String[]"
            paramTypes("java.lang.String", "android.content.Context")
            usingEqStrings("OfflineFindProvider", "getAirPodsState: state = ", "AirpodsModel", "-state", "\\|")
        }
    }.singleNativeContract("AirPods state reader").reflected(loader).also { require(Modifier.isStatic(it.modifiers)) }

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
        }.singleNativeContract("circulation record boundary")
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
        }.singleNativeContract("circulation message handler")
        // In the verified handler this is the only zero-argument int read from the exact record
        // type accepted by addConnectManagerList: requestFlag. connectCount is only written here.
        val requestFlag = handler.invokes.filter {
            it.declaredClassName == recordType && it.paramCount == 0 && it.returnTypeName == "int"
        }.distinctBy(MethodData::descriptor).singleNativeContract("circulation request flag").reflected(loader)
        require(!Modifier.isStatic(requestFlag.modifiers))
        return NativePresentationFlow(loader, requestFlag)
    }

    private fun MethodData.reflected(loader: ClassLoader): Method =
        getMethodInstance(loader).apply { isAccessible = true }

    private const val HEADSET_SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
    private const val OFFLINE_PROVIDER = "com.android.bluetooth.ble.app.offlinefind.OfflineFindProvider"
    internal const val NOTIFICATION = "com.android.bluetooth.ble.app.MiuiBluetoothNotification"
}
