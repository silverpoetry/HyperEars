package dev.hyperears.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import dev.hyperears.bridge.*
import dev.hyperears.integration.EarbudState
import java.lang.reflect.Method
import java.util.IdentityHashMap

/** Captured Settings.apk generic device-detail host. No AirPods plugin routing or service binding. */
internal class SettingsHeadsetHook : HookContext() {
    private val main = Handler(Looper.getMainLooper())
    private val states = SettingsHeadsetState()
    private val pages = IdentityHashMap<Any, SettingsHeadsetBinding>()
    private data class Observer(val owner: Any, val address: String, val callback: (EarbudState) -> Unit)
    private val observers = linkedMapOf<Any, Observer>()
    private val controls = SettingsControlGate()
    private val producers = mutableMapOf<IBinder, IBinder.DeathRecipient>()
    private var context: Context? = null
    private var settingsObserver: java.io.Closeable? = null
    private var receiver: BroadcastReceiver? = null
    private val installation = HookInstallTransaction<io.github.libxposed.api.XposedInterface.HookHandle> { it.unhook() }
    override fun install() = installation.install {
        val fragment = findClass(FRAGMENT)
        val base = findClass(BASE)
        val layout = findClass("com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingLayout")
        val create = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val construct = base.getDeclaredMethod("constructLayout", layout).apply { isAccessible = true }
        val destroy = inherited(fragment, "onDestroyView")
        val detach = inherited(fragment, "onDetach")
        base.getMethod("getDeviceAddress").also { check(it.returnType == String::class.java) }
        base.getMethod("getCachedDevice").also { check(it.returnType.name == "com.android.settingslib.bluetooth.CachedBluetoothDevice") }
        installation.record(module.hook(create).intercept { chain ->
            val original = chain.proceed()
            val target = chain.thisObject
            val view = chain.args.firstOrNull() as? View
            if (target != null && view != null) runCatching { bind(target, view) }.onFailure { release(target) }
            original
        })
        installation.record(module.hook(construct).intercept { chain ->
            val original = chain.proceed()
            chain.thisObject?.takeIf(fragment::isInstance)?.let { target ->
                runCatching { pages[target]?.reconcile() }.onFailure { release(target) }
            }
            original
        })
        listOf(destroy, detach).distinct().forEach { method ->
            installation.record(module.hook(method).intercept { chain ->
                chain.thisObject?.takeIf(fragment::isInstance)?.let { target -> runCatching { release(target) } }
                chain.proceed()
            })
        }
    }

    private fun bind(fragment: Any, view: View) {
        release(fragment)
        val address = address(fragment) ?: return
        val ctx = view.context
        val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment) ?: return
        val host = SettingsPreferenceHost(fragment.javaClass.classLoader!!, ctx, screen)
        startReceiver(ctx)
        fun pageRecord(target: String) = settingsPageRecord(address, target, { address(fragment) }, { trusted(target) })
        val env = MiLinkCardEnvironment(fragment.javaClass.classLoader!!,
            { target -> pageRecord(target)?.state ?: EarbudState() },
            { target, request ->
                val value = pageRecord(target) ?: error("No trusted Settings target")
                check(controls.send(value, request) {
                    BroadcastIdentity.send(ctx, ModuleContract.control(request, target, value.token))
                }) { "Settings control ownership or local lease changed" }
            },
            { target, callback ->
                val key = Any()
                observers[key] = Observer(fragment, target) { incoming -> callback(if (!incoming.sessionActive) EarbudState() else pageRecord(target)?.state ?: EarbudState()) }
                val release: () -> Unit = { observers.remove(key); Unit }
                release
            },
            { target -> pageRecord(target)?.token })
        pages[fragment] = SettingsHeadsetBinding(host, view, address, { address(fragment) }, { trusted(address) }, env,
            canReturn = { value -> controls.canReturn(value) && VendorHandoffLauncher.hasReturnableLease(address, value.token) })
        pages[fragment]?.render()
        BroadcastIdentity.send(ctx, ModuleContract.requestState(ModuleContract.SETTINGS_PACKAGE))
    }
    private fun address(fragment: Any): String? {
        val address = fragment.javaClass.getMethod("getDeviceAddress").invoke(fragment) as? String ?: return null
        val cached = fragment.javaClass.getMethod("getCachedDevice").invoke(fragment) ?: return null
        val device = cached.javaClass.getMethod("getDevice").invoke(cached) as? android.bluetooth.BluetoothDevice ?: return null
        return address.takeIf { it.equals(device.address, true) }
    }
    private fun trusted(address: String) = states.trustedRecord(address).takeUnless { ModuleRuntimeGate.paused }
    private fun startReceiver(candidate: Context) {
        if (receiver != null) return
        val app = candidate.applicationContext ?: candidate
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid, ModuleContract.BLUETOOTH_PACKAGE)) return
                if (runCatching { states.accept(intent) }.getOrDefault(false)) {
                    with(ModuleContract) { intent.readState() }?.address?.let { controls.observe(it, trusted(it)) }
                    NativePresentationContract.producer(intent)?.let(::watchProducer)
                    renderAll()
                }
            }
        }
        app.registerReceiver(listener, IntentFilter(ModuleContract.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        context = app; receiver = listener
        settingsObserver = dev.hyperears.settings.ModuleSettingsRuntime.observe { main.post { renderAll() } }
    }
    private fun watchProducer(producer: IBinder) {
        if (producer in producers) return
        val death = IBinder.DeathRecipient { main.post { renderAll() } }
        runCatching { producer.linkToDeath(death, 0) }.onSuccess { producers[producer] = death }
    }
    private fun renderAll() {
        observers.values.toList().forEach { observer -> runCatching { observer.callback(trusted(observer.address)?.state ?: EarbudState()) } }
        pages.values.toList().forEach(SettingsHeadsetBinding::render)
    }
    private fun release(fragment: Any) {
        // Cancel independent handoff observers before removing their live token/sender context.
        observers.values.filter { it.owner === fragment }.toList().forEach { observer ->
            runCatching { observer.callback(EarbudState()) }
        }
        pages.remove(fragment)?.close()
        if (pages.isNotEmpty()) return
        receiver?.let { runCatching { context?.unregisterReceiver(it) } }
        receiver = null; context = null; settingsObserver?.close(); settingsObserver = null; observers.clear(); states.clear()
        producers.forEach { (binder, death) -> runCatching { binder.unlinkToDeath(death, 0) } }; producers.clear()
    }
    private fun inherited(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current!!.getDeclaredMethod(name).apply { isAccessible = true } }
            current = current.superclass
        }
        error("Required Settings lifecycle method missing: $name")
    }
    companion object {
        const val FRAGMENT = "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment"
        const val BASE = "com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment"
    }
}

/** Every popup callback is still bound to the real fragment's current Bluetooth address. */
internal fun settingsPageRecord(address: String, target: String, currentAddress: () -> String?,
    record: () -> SettingsHeadsetState.Record?): SettingsHeadsetState.Record? = runCatching {
    if (!target.equals(address, true) || !currentAddress().equals(address, true)) null
    else record()?.takeIf { it.state.address.equals(address, true) }
}.getOrNull()
