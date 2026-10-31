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

/** Connects captured Settings device pages to trusted headset state and the native Apple plugin. */
internal class SettingsHeadsetHook : HookContext() {
    private val main = Handler(Looper.getMainLooper())
    private val states = SettingsHeadsetState()
    private val pages = IdentityHashMap<Any, SettingsHeadsetBinding>()
    private data class PendingDetails(val address: String, val activity: android.app.Activity, val view: View?, val existingDetails:Boolean=false)
    private val pendingDetails = IdentityHashMap<Any, PendingDetails>()
    private val listHosts = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private data class Observer(val owner: Any, val address: String, val callback: (EarbudState) -> Unit)
    private val nativePage by lazy {
        SettingsApplePluginBridge(::trusted, { value, request ->
            val ctx = requireNotNull(context)
            check(controls.send(value, request) {
                BroadcastIdentity.send(ctx, ModuleContract.control(request, requireNotNull(value.state.address), value.token))
            })
        }, ::bind, ::release).also {
            it.module = module; it.appClassLoader = appClassLoader; it.packageName = packageName
        }
    }
    private val observers = linkedMapOf<Any, Observer>()
    private val controls = SettingsControlGate()
    private val producers = mutableMapOf<IBinder, IBinder.DeathRecipient>()
    private var context: Context? = null
    private var settingsObserver: java.io.Closeable? = null
    private var receiver: BroadcastReceiver? = null
    private val installation = HookInstallTransaction<io.github.libxposed.api.XposedInterface.HookHandle> { it.unhook() }
    override fun install() = installation.install {
        nativePage.install()
        CustomPreferenceRows.install(module,appClassLoader)
        val seekPreference = findClass("androidx.preference.SeekBarPreference")
        seekPreference.declaredMethods.firstOrNull { it.name == "onBindViewHolder" && it.parameterCount == 1 }?.let { method ->
            installation.record(module.hook(method).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { runCatching { SettingsPreferenceHost.bindRange(it) } }
                result
            })
        }
        val listType = findClass("com.android.settings.bluetooth.MiuiBluetoothSettings")
        val cachedType = findClass("com.android.settingslib.bluetooth.CachedBluetoothDevice")
        installation.record(module.hook(inherited(listType, "onResume")).intercept { chain ->
            val result = chain.proceed()
            chain.thisObject?.takeIf(listType::isInstance)?.let { target ->
                val ctx = target.javaClass.getMethod("getContext").invoke(target) as? Context
                if (ctx != null) {
                    listHosts.add(target)
                    startReceiver(ctx)
                    val activity = target.javaClass.getMethod("getActivity").invoke(target) as? android.app.Activity
                    val address = activity?.intent?.getStringExtra(BluetoothDeviceSettings.ROUTE_ADDRESS)
                        ?.takeIf(android.bluetooth.BluetoothAdapter::checkBluetoothAddress)
                    if (activity != null && address != null && target !in pendingDetails) {
                        activity.intent.removeExtra(BluetoothDeviceSettings.ROUTE_ADDRESS)
                        val view = target.javaClass.getMethod("getView").invoke(target) as? View
                        view?.visibility = View.INVISIBLE
                        val pending = PendingDetails(address, activity, view)
                        pendingDetails[target] = pending
                        main.postDelayed({
                            if (pendingDetails[target] === pending) finishPendingDetails(target, pending, false)
                        }, 2000)
                    }
                    BroadcastIdentity.send(ctx, ModuleContract.requestState(ModuleContract.SETTINGS_PACKAGE))
                    routePendingDetails()
                }
            }
            result
        })
        installation.record(module.hook(listType.getDeclaredMethod("checkStartMiuiHeadset", cachedType)).intercept { chain ->
            // Preserve the official decision first. This method returns before any generic detail page opens.
            val original = chain.proceed()
            if (original == true) true else runCatching {
                val target = requireNotNull(chain.thisObject)
                val device = cachedType.getMethod("getDevice").invoke(chain.args[0]) as android.bluetooth.BluetoothDevice
                val value = trusted(device.address) ?: return@runCatching false
                val ctx = target.javaClass.getMethod("getContext").invoke(target) as Context
                val direct = getObjectField(target, "mQigsawInstalled") == true
                nativePage.open(ctx, device, value, direct)
            }.onFailure { ModuleLog.warn("SettingsDetails", "list native route failed", it) }.getOrDefault(false)
        })
        installation.record(module.hook(inherited(listType, "onDestroyView")).intercept { chain ->
            chain.thisObject?.takeIf(listType::isInstance)?.let { pendingDetails.remove(it)?.view?.visibility = View.VISIBLE; listHosts.remove(it); release(it) }
            chain.proceed()
        })
        val legacy = findClass("com.android.settings.bluetooth.DeviceProfilesSettings")
        legacy.getDeclaredField("mCachedDevice")
        legacy.getDeclaredField("mDeviceMacAddress")
        val resumeLegacy = legacy.getDeclaredMethod("onResume")
        val refreshLegacy = legacy.getDeclaredMethod("refresh")
        installation.record(module.hook(resumeLegacy).intercept { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { target ->
                runCatching {
                    (target.javaClass.getMethod("getView").invoke(target) as? View)?.let { if (pages.containsKey(target)) pages[target]?.reconcile() else bind(target, it) }
                }.onFailure { error ->
                    ModuleLog.warn("SettingsDetails", "legacy device page binding failed", error)
                    release(target)
                }
            }
            result
        })
        installation.record(module.hook(refreshLegacy).intercept { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { pages[it]?.reconcile() }
            result
        })
        listOf(inherited(legacy, "onDestroyView"), inherited(legacy, "onDetach")).distinct().forEach { method ->
            installation.record(module.hook(method).intercept { chain ->
                chain.thisObject?.takeIf(legacy::isInstance)?.let { release(it) }
                chain.proceed()
            })
        }
        val fragment = findClass(FRAGMENT)
        val base = findClass(BASE)
        val layout = findClass("com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingLayout")
        val create = fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val construct = base.getDeclaredMethod("constructLayout", layout).apply { isAccessible = true }
        val destroy = inherited(fragment, "onDestroyView")
        val detach = inherited(fragment, "onDetach")
        inherited(base, "getDeviceAddress").also { check(it.returnType == String::class.java) }
        inherited(base, "getCachedDevice").also { check(it.returnType.name == "com.android.settingslib.bluetooth.CachedBluetoothDevice") }
        installation.record(module.hook(create).intercept { chain ->
            val original = chain.proceed()
            val target = chain.thisObject
            val view = chain.args.firstOrNull() as? View
            if (target != null && view != null) runCatching { bind(target, view) }.onFailure { error ->
                ModuleLog.warn("SettingsDetails", "device page binding failed", error)
                release(target)
            }
            original
        })
        installation.record(module.hook(construct).intercept { chain ->
            val original = chain.proceed()
            chain.thisObject?.takeIf(fragment::isInstance)?.let { target ->
                runCatching { pages[target]?.reconcile() }.onFailure { error ->
                    ModuleLog.warn("SettingsDetails", "device page reconstruction failed", error)
                    release(target)
                }
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
        val address = address(fragment) ?: run { ModuleLog.debug("SettingsDetails", "bind has no matching device address"); return }
        val ctx = view.context
        val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment) ?: return
        val host = SettingsPreferenceHost(fragment.javaClass.classLoader!!, ctx, screen)
        startReceiver(ctx)
        ModuleLog.debug("SettingsDetails", "bound " + fragment.javaClass.name)
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
            canReturn = { value -> controls.canReturn(value) && VendorHandoffLauncher.hasReturnableLease(address,value.token) },
            nativeApple = fragment.javaClass.name == "plugin.settings.java.airpods.MiuiAirpodsFragment")
        val activity=fragment.javaClass.getMethod("getActivity").invoke(fragment) as? android.app.Activity
        if(fragment.javaClass.name !in setOf("plugin.settings.java.MiuiHeadsetFragment", "plugin.settings.java.airpods.MiuiAirpodsFragment") &&
            activity?.intent?.getStringExtra(BluetoothDeviceSettings.ROUTE_ADDRESS).equals(address,true)) {
            activity?.intent?.removeExtra(BluetoothDeviceSettings.ROUTE_ADDRESS)
            view.visibility=View.INVISIBLE
            val pending=PendingDetails(address,requireNotNull(activity),view,true)
            pendingDetails[fragment]=pending
            main.postDelayed({if(pendingDetails[fragment]===pending)finishPendingDetails(fragment,pending,false)},2000)
        }
        pages[fragment]?.render()
        routePendingDetails()
        BroadcastIdentity.send(ctx, ModuleContract.requestState(ModuleContract.SETTINGS_PACKAGE))
    }
    private fun address(fragment: Any): String? {
        if (fragment.javaClass.name in setOf("plugin.settings.java.MiuiHeadsetFragment", "plugin.settings.java.airpods.MiuiAirpodsFragment")) {
            val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) ?: return null
            val device = activity.javaClass.getMethod("getDevice").invoke(activity) as? android.bluetooth.BluetoothDevice ?: return null
            val bound = getObjectField(fragment, "mDevice") as? android.bluetooth.BluetoothDevice ?: return null
            return device.address.takeIf { it.equals(bound.address, true) }
        }
        if (fragment.javaClass.name == "com.android.settings.bluetooth.DeviceProfilesSettings") {
            val expected = getObjectField(fragment, "mDeviceMacAddress") as? String ?: return null
            val cached = getObjectField(fragment, "mCachedDevice") ?: return null
            val device = cached.javaClass.getMethod("getDevice").invoke(cached) as? android.bluetooth.BluetoothDevice ?: return null
            return expected.takeIf { it.equals(device.address, true) }
        }
        val address = inherited(fragment.javaClass, "getDeviceAddress").invoke(fragment) as? String ?: return null
        val cached = inherited(fragment.javaClass, "getCachedDevice").invoke(fragment) ?: return null
        val device = cached.javaClass.getMethod("getDevice").invoke(cached) as? android.bluetooth.BluetoothDevice ?: return null
        return address.takeIf { it.equals(device.address, true) }
    }
    private fun routePendingDetails() {
        pendingDetails.toMap().forEach { (target, pending) ->
            if (pending.activity.isFinishing || pending.activity.isDestroyed) {
                pendingDetails.remove(target); return@forEach
            }
            if(pending.existingDetails && (target !in pages ||
                !runCatching { address(target).equals(pending.address,true) }.getOrDefault(false))) {
                pendingDetails.remove(target);pending.view?.visibility=View.VISIBLE;return@forEach
            }
            // Wait for an authenticated state response; the launch Intent never authorizes controls.
            if (trusted(pending.address) == null) return@forEach
            runCatching {
                if(pending.existingDetails) {
                    val device=pending.activity.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(pending.address)
                    val value=trusted(pending.address) ?: return@runCatching
                    finishPendingDetails(target,pending,nativePage.open(pending.activity,device,value,false))
                    return@runCatching
                }
                val manager = getObjectField(target, "mLocalManager") ?: return@runCatching
                val cachedManager = manager.javaClass.getMethod("getCachedDeviceManager").invoke(manager)
                val adapter = pending.activity.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                val device = adapter.getRemoteDevice(pending.address)
                val cached = cachedManager.javaClass.getMethod("findDevice", android.bluetooth.BluetoothDevice::class.java)
                    .invoke(cachedManager, device) ?: return@runCatching
                val method = target.javaClass.getDeclaredMethod("checkStartMiuiHeadset", cached.javaClass).apply { isAccessible = true }
                // Invoke the same hooked decision as the paired-list button: system first, then module.
                pendingDetails.remove(target)
                val opened = method.invoke(target, cached) == true
                finishPendingDetails(target, pending, opened)
            }.onFailure {
                ModuleLog.warn("SettingsDetails", "popup native route failed", it)
                finishPendingDetails(target, pending, false)
            }
        }
    }
    private fun finishPendingDetails(target: Any, pending: PendingDetails, opened: Boolean) {
        pendingDetails.remove(target)
        pending.view?.visibility = View.VISIBLE
        if(!opened && pending.existingDetails)return
        if (!opened) BluetoothDeviceSettings.openLegacy(pending.activity, pending.address)
        pending.activity.finish()
    }
    private fun trusted(address: String) = states.trustedRecord(address).takeUnless { ModuleRuntimeGate.paused }
    private fun startReceiver(candidate: Context) {
        if (receiver != null) return
        val app = candidate.applicationContext ?: candidate
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid, ModuleContract.BLUETOOTH_PACKAGE)) { ModuleLog.debug("SettingsDetails", "state sender rejected"); return }
                val accepted = runCatching { states.accept(intent) }.getOrDefault(false)
                ModuleLog.debug("SettingsDetails", "state accepted=$accepted metadata=${NativePresentationContract.metadata(intent)}")
                if (accepted) {
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
        routePendingDetails()
        nativePage.update()
        observers.values.toList().forEach { observer -> runCatching { observer.callback(trusted(observer.address)?.state ?: EarbudState()) } }
        pages.values.toList().forEach(SettingsHeadsetBinding::render)
    }
    private fun release(fragment: Any) {
        pendingDetails.remove(fragment)?.view?.visibility=View.VISIBLE
        // Cancel independent handoff observers before removing their live token/sender context.
        observers.values.filter { it.owner === fragment }.toList().forEach { observer ->
            runCatching { observer.callback(EarbudState()) }
        }
        pages.remove(fragment)?.close()
        if (pages.isNotEmpty() || listHosts.isNotEmpty() || nativePage.active) return
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
