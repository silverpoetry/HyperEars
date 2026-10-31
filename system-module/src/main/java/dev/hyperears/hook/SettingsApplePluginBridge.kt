package dev.hyperears.hook

import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dev.hyperears.integration.*
import dev.hyperears.presentation.NativeHeadsetProjection
import dev.hyperears.presentation.nativeAppleWearFlags
import java.util.IdentityHashMap

/** Presentation-only route into the installed Xiaomi AirPods plugin. Never grants system ownership. */
internal class SettingsApplePluginBridge(
    private val current: (String) -> SettingsHeadsetState.Record?,
    private val send: (SettingsHeadsetState.Record, ControlRequest) -> Unit,
    private val attach: (Any, View) -> Unit,
    private val detach: (Any) -> Unit,
) : HookContext() {
    private data class Route(val token: String, val producer: android.os.IBinder)
    private val routes = mutableMapOf<String, Route>()
    private val loaders = java.util.Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    private val contexts = java.util.WeakHashMap<Any, String>()
    private val activities = java.util.WeakHashMap<Activity, String>()
    val active: Boolean get() = routes.isNotEmpty()
    fun record(address: String): SettingsHeadsetState.Record? {
        val route = routes[address] ?: return null
        return current(address)?.takeIf { it.token == route.token && it.producer == route.producer &&
            it.state.address.equals(address, true) && it.state.sessionActive }
    }
    fun open(context: Context, device: BluetoothDevice, value: SettingsHeadsetState.Record, direct: Boolean = false): Boolean {
        if (value.state.features.get<AppleDeviceInformationFeatureState>()?.modelIdentifier !in setOf("A3439", "A3440", "A3441", "A3531", "A3532", "A3533")) return false
        if (!value.state.sessionActive || !value.state.address.equals(device.address, true)) return false
        routes[device.address] = Route(value.token, value.producer)
        return runCatching {
            context.startActivity(Intent("miui.bluetooth.action.HEADSET_SETTINGS_PLUGIN")
                .setClassName("com.android.settings", if (direct) "plugin.settings.java.JavaActivity" else "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra("bluetoothaddress", device.address)
                // AirPods4 ANC selects existing appearance only; protocol identity stays A3441 etc.
                .putExtra("MIUI_HEADSET_SUPPORT", "airpods|1B20|1")
                .putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS"))
            ModuleLog.debug("ApplePage", "native list route direct=$direct")
            true
        }.getOrElse { routes.remove(device.address); ModuleLog.warn("ApplePage", "native page launch failed", it); false }
    }
    override fun install() {
        module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnDestroy", Activity::class.java)).intercept { chain ->
            val activity = chain.args[0] as Activity
            val result = chain.proceed()
            activities.remove(activity)?.let { address ->
                if (!activity.isChangingConfigurations) {
                    routes.remove(address)
                    contexts.entries.removeAll { it.value == address }
                    detach(activity)
                }
            }
            result
        }
        module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnCreate", Activity::class.java, Bundle::class.java)).intercept { chain ->
            val activity = chain.args[0] as Activity
            if (activity.javaClass.name == "plugin.settings.java.JavaActivity") {
                val device = activity.intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device != null && record(device.address) != null) {
                    installPlugin(activity.javaClass.classLoader!!)
                    activities[activity] = device.address
                }
            }
            chain.proceed()
        }
    }
    // Captured Settings plugin AIDL, not an Android framework private API.
    @android.annotation.SuppressLint("PrivateApi")
    private fun installPlugin(loader: ClassLoader) {
        if (loader in loaders) return
        val tx = HookInstallTransaction<io.github.libxposed.api.XposedInterface.HookHandle> { it.unhook() }
        tx.install {
            CustomPreferenceRows.install(module, loader)
            val seek = loader.loadClass("androidx.preference.SeekBarPreference")
            seek.declaredMethods.firstOrNull { it.name == "onBindViewHolder" && it.parameterCount == 1 }?.let { method ->
                tx.record(module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { runCatching { SettingsPreferenceHost.bindRange(it) }
                        .onFailure { error -> ModuleLog.warn("ApplePage", "slider scale bind failed", error) } }
                    result
                })
            }
            val prefix = "plugin.settings.java.airpods."
            val stateClass = loader.loadClass(prefix + "AirPodsHeadsetStateContext")
            val db = loader.loadClass(prefix + "DbHelper")
            val fragment = loader.loadClass(prefix + "MiuiAirpodsFragment")
            val anc = loader.loadClass(prefix + "AncController")
            val proxy = loader.loadClass("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy")
            for (name in listOf("connect", "disconnect")) {
                tx.record(module.hook(proxy.getDeclaredMethod(name, BluetoothDevice::class.java)).intercept { chain ->
                    val device = chain.args[0] as? BluetoothDevice
                    if (device != null && record(device.address) != null) null else chain.proceed()
                })
            }
            tx.record(module.hook(stateClass.getDeclaredMethod("getAirPodsState", String::class.java)).intercept { chain ->
                val address = chain.args[0] as String
                val value = record(address)
                if (value == null) chain.proceed() else {
                    val target = requireNotNull(chain.thisObject)
                    contexts[target] = address
                    updateContext(target, value.state)
                    null
                }
            })
            for (name in listOf("getState", "isFeatureCloudSupport")) {
                tx.record(module.hook(db.getDeclaredMethod(name, Context::class.java, String::class.java, BluetoothDevice::class.java)).intercept { chain ->
                    val device = chain.args[2] as? BluetoothDevice
                    val value = device?.let { record(it.address) }
                    if (value == null) chain.proceed() else {
                        val key = chain.args[1] as String
                        if (name == "isFeatureCloudSupport") key == "NoiseControl" && value.state.features.get<AppleListeningModeFeatureState>() != null
                        else if (key == "air_anc") ancValue(value.state) else ""
                    }
                })
            }
            tx.record(module.hook(anc.getDeclaredMethod("setAncForUser", Int::class.javaPrimitiveType)).intercept { chain ->
                val target = requireNotNull(chain.thisObject)
                val activity = getObjectField(target, "mActivity") as? Activity
                val value = activities[activity]?.let(::record)
                if (value == null) chain.proceed() else {
                    val mode = when (chain.args[0] as Int) { 1 -> AppleListeningMode.ANC; 2 -> AppleListeningMode.OFF; 3 -> AppleListeningMode.TRANSPARENCY; else -> null }
                    if (mode != null) AppleListeningCardPolicy.request(value.state, mode)?.let { send(value, it) }
                    null // Selection changes only when a new device report reaches the stock callback.
                }
            })
            tx.record(module.hook(fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)).intercept { chain ->
                val result = chain.proceed()
                val target = requireNotNull(chain.thisObject)
                val activity = target.javaClass.getMethod("getActivity").invoke(target) as? Activity
                if (activities[activity]?.let(::record) != null) attach(target, chain.args[0] as View)
                result
            })
            tx.record(module.hook(fragment.getDeclaredMethod("onDestroyView")).intercept { chain ->
                chain.thisObject?.let(detach)
                chain.proceed()
            })
        }
        loaders.add(loader)
        ModuleLog.debug("ApplePage", "native AirPods plugin bindings installed")
    }
    fun update() {
        contexts.toMap().forEach { (target, address) ->
            record(address)?.let { runCatching { updateContext(target, it.state) }.onFailure { ModuleLog.warn("ApplePage", "native state update failed", it) } }
        }
        activities.toMap().forEach { (activity, address) -> if (record(address) == null && !activity.isFinishing) activity.finish() }
    }
    private fun updateContext(target: Any, state: EarbudState) {
        val p = NativeHeadsetProjection.from(state) ?: return
        val wear = nativeAppleWearFlags(state)
        val data = arrayOf(p.left.percent?.toString() ?: "-", p.right.percent?.toString() ?: "-", p.chargingCase.percent?.toString() ?: "-",
            wear.first, wear.second, p.left.charging.toString(), p.right.charging.toString(), p.chargingCase.charging.toString(), p.address, "2", p.name)
        target.javaClass.getMethod("onHeadsetStateChanged", Array<String>::class.java).invoke(target, data as Any)
        target.javaClass.getMethod("notifyCallbaksStateChanged", String::class.java, String::class.java, String::class.java)
            .invoke(target, "air_anc", ancValue(state), p.address)
    }
    private fun ancValue(state: EarbudState) = when (state.features.get<AppleListeningModeFeatureState>()?.mode) {
        AppleListeningMode.OFF -> "01"; AppleListeningMode.ANC -> "02"; AppleListeningMode.TRANSPARENCY -> "03"; else -> ""
    }
}
