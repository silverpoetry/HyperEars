package dev.hyperears.presentation

import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.RemoteException
import android.view.View
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.EarbudState
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/** Owns only explicitly authorized native window instances, never the native Apple device service. */
internal class AirPodsWindowBridge(
    private val context: Context,
    private val forBinding: (NativeWindowRequest) -> NativeWindowRequest?,
    private val onOpened: (String, IBinder) -> Unit,
    private val onClosed: (String) -> Unit,
    private val openSettings: (Context, EarbudState) -> Boolean,
) : HookContext() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = IdentityHashMap<Any, Binding>()
    private val activities = IdentityHashMap<Any, Binding>()
    private val creating = ThreadLocal<Binding?>()
    private val consumedTickets = mutableSetOf<String>()
    private val artwork = NativeArtworkLoader(context)
    private lateinit var controllerType: Class<*>
    private lateinit var callbackType: Class<*>
    private lateinit var serviceType: Class<*>
    private var enabled = false

    private class Binding(
        var request: NativeWindowRequest,
        val controller: Any,
        val activity: Activity,
        val connection: ServiceConnection,
    ) {
        val state: EarbudState get() = request.state
        var active = true
        var closing = false
        var bound = false
        var callback: Any? = null
        var caseVisibilityDisabled = false
        var navigation: ContentNavigation? = null
        lateinit var binder: Binder
        lateinit var death: IBinder.DeathRecipient
    }

    private class ContentNavigation(val view: View) {
        private val clickable = view.isClickable
        private val focusable = view.isFocusable

        fun release() {
            view.setOnClickListener(null)
            view.isClickable = clickable
            view.isFocusable = focusable
        }
    }

    override fun install() {
        val symbols = AirPodsWindowSymbols(appClassLoader)
        controllerType = symbols.controller
        serviceType = symbols.service
        callbackType = symbols.callback
        val create = symbols.create
        val destroy = symbols.destroy
        val read = symbols.read
        val build = symbols.build
        val load = symbols.load
        val init = symbols.init
        val resourceInit = symbols.resourceInit
        val dynamic = symbols.dynamic

        module.hook(create).intercept { chain ->
            val controller = requireNotNull(chain.thisObject)
            val activity = NativeMembers.get(controller, "mActivityContext") as Activity
            if (!activity.intent.hasExtra(NativePresentationContract.EXTRA_WINDOW)) return@intercept chain.proceed()
            if (!enabled) {
                activity.finish()
                return@intercept null
            }
            val request = runCatching { NativeWindowRequest.read(activity)?.let(forBinding) }.getOrNull()
            if (request == null || !consumedTickets.add(request.id)) {
                ModuleLog.warn(TAG, "rejected invalid native window authorization")
                activity.finish()
                return@intercept null
            }
            val device = activity.intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device == null || device.address != request.state.address ||
                BluetoothDevice::class.java.getMethod("isConnected").invoke(device) != true) {
                activity.finish()
                return@intercept null
            }
            val binding = Binding(request, controller, activity,
                NativeMembers.get(controller, "mFastConnectServiceConnection") as ServiceConnection)
            binding.binder = localService(binding)
            binding.death = IBinder.DeathRecipient { handler.post { close(binding, "producer died") } }
            runCatching { request.producer.linkToDeath(binding.death, 0) }.getOrElse {
                activity.finish()
                return@intercept null
            }
            controllers[controller] = binding
            activities[activity] = binding
            val previous = creating.get()
            val originalDynamic = dynamic.getBoolean(null)
            creating.set(binding)
            try {
                chain.proceed().also {
                    onOpened(request.id, binding.binder)
                    ModuleLog.debug(TAG, "created address=${maskBluetoothAddress(request.state.address)} " +
                        "revision=${request.state.revision} single=${projection(binding).singleBattery}")
                }
            } catch (error: Throwable) {
                release(binding)
                activity.finish()
                ModuleLog.warn(TAG, "native window initialization failed", error)
                null
            } finally {
                dynamic.setBoolean(null, originalDynamic)
                if (previous == null) creating.remove() else creating.set(previous)
            }
        }

        hookAfter(build) {
            val binding = creating.get() ?: return@hookAfter
            if (args[0] != binding.state.address) return@hookAfter
            val info = result ?: return@hookAfter
            val p = projection(binding)
            setInt(info, "setConnectState", 2)
            setInt(info, "setLeftBattery", p.left.percent ?: -1)
            setInt(info, "setRightBattery", p.right.percent ?: -1)
            setInt(info, "setBoxBattery", p.chargingCase.percent ?: -1)
            setInt(info, "setChargingState", charging(p))
            setInt(info, "setInEarState", 0)
        }

        hookBefore(read) {
            val binding = controllers[instance] ?: return@hookBefore
            if (binding.active && args[0] == binding.state.address) result = binding.request.stateArray()
        }

        // The Controller uses an in-process display service. Only its exact Activity/connection
        // pair is intercepted; no real FastConnect service, BLE scanner, or global Binder is used.
        hookBefore(ContextWrapper::class.java.getMethod("bindService", Intent::class.java,
            ServiceConnection::class.java, Int::class.javaPrimitiveType)) {
            val binding = activities[instance] ?: return@hookBefore
            val intent = args[0] as? Intent ?: return@hookBefore
            if (args[1] !== binding.connection || intent.component?.className != "$PREFIX.MiuiFastConnectService") return@hookBefore
            binding.bound = true
            handler.post {
                if (binding.active && binding.bound) binding.connection.onServiceConnected(
                    ComponentName(ModuleContract.NATIVE_HEADSET_PACKAGE, "$PREFIX.MiuiFastConnectService"),
                    binding.binder,
                )
            }
            result = true
        }
        hookBefore(ContextWrapper::class.java.getMethod("unbindService", ServiceConnection::class.java)) {
            val binding = activities[instance] ?: return@hookBefore
            if (args[0] !== binding.connection || !binding.bound) return@hookBefore
            binding.bound = false
            result = null
        }

        hookBefore(load) {
            val binding = controllers[instance] ?: return@hookBefore
            val owner = binding.controller
            val record = requireNotNull(NativeMembers.get(owner, "mCloudResRecord"))
            NativeMembers.set(record, "mLayout", 1)
            NativeMembers.set(record, "mTwsHeadset", !projection(binding).singleBattery)
            NativeMembers.set(record, "mAllowSync", false)
            NativeMembers.set(record, "mDeviceName", projection(binding).name)
            NativeMembers.set(record, "mRecordDocument", null)
            // Use the native static resource initializer, skipping cloud downloads for this window.
            val resources = requireNotNull(NativeMembers.get(owner, "mLocalResource"))
            if (NativeMembers.call(resources, "isInit") == true) NativeMembers.call(resources, "cleanUp")
            resourceInit.invoke(resources,
                "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 800, false, false)
            result = true
        }
        hookAfter(init) {
            val binding = controllers[instance] ?: return@hookAfter
            val p = projection(binding)
            val defaults = HeadsetArtwork.defaultFor(p.form)
            @Suppress("UNCHECKED_CAST")
            val icons = NativeMembers.get(binding.controller, "mHeadsetIconDrawable") as Array<Drawable?>
            icons.indices.forEach { icons[it] = artwork.load(p.artwork.headset, defaults.headset) }
            NativeMembers.set(binding.controller, "mHeadsetBoxIconDrawable",
                artwork.load(p.artwork.chargingCase ?: HeadsetArtwork.EARBUDS.chargingCase!!,
                    HeadsetArtwork.EARBUDS.chargingCase!!))
        }

        module.hook(destroy).intercept { chain ->
            val binding = controllers[chain.thisObject]
            try { chain.proceed() } finally { binding?.let(::release) }
        }
        installCaseVisibility()
        installContentNavigation(symbols.createDialog)
        enabled = true
        ModuleLog.debug(TAG, "native window semantic hooks ready")
    }

    private fun installContentNavigation(createDialog: Method) {
        runCatching {
            val content = NativeMembers.field(controllerType, "mView")
            require(View::class.java.isAssignableFrom(content.type))
            hookAfter(createDialog) {
                val binding = controllers[instance] ?: return@hookAfter
                if (!binding.active || binding.closing || binding.activity.isFinishing) return@hookAfter
                runCatching {
                    val view = content.get(binding.controller) as? View ?: return@runCatching
                    if (binding.navigation?.view === view) return@runCatching
                    binding.navigation?.release()
                    binding.navigation = null
                    // Only the authorized Controller's content is clickable. Native dialog controls
                    // (including close/retry) retain their own actions; an existing root action wins.
                    if (view.hasOnClickListeners()) return@runCatching
                    binding.navigation = ContentNavigation(view)
                    view.setOnClickListener {
                        if (!binding.active || binding.closing || binding.activity.isFinishing ||
                            !binding.request.producer.isBinderAlive) return@setOnClickListener
                        runCatching { openSettings(binding.activity, binding.state) }
                            .onSuccess { if (it) close(binding, "settings opened") }
                            .onFailure { ModuleLog.warn(TAG, "unable to open headset settings", it) }
                    }
                    view.isFocusable = true
                }.onFailure { ModuleLog.warn(TAG, "native content navigation unavailable", it) }
            }
        }.onFailure { ModuleLog.warn(TAG, "native content navigation hook unavailable", it) }
    }

    private fun installCaseVisibility() {
        runCatching {
            val visibility = NativeCaseVisibility.resolve(controllerType, appClassLoader)
            hookAfter(visibility.render) {
                val binding = controllers[instance] ?: return@hookAfter
                if (!binding.active || binding.activity.isFinishing || binding.caseVisibilityDisabled) return@hookAfter
                runCatching {
                    val root = args[0] as? View ?: return@runCatching
                    if (root !== NativeMembers.get(binding.controller, "mView")) return@runCatching
                    val p = projection(binding)
                    if (p.singleBattery) return@runCatching
                    if (visibility.apply(root, p.showCaseInPopup)) {
                        ModuleLog.debug(TAG) {
                            "case visibility=${p.showCaseInPopup} battery=${p.chargingCase.percent} " +
                                "charging=${p.chargingCase.charging} revision=${binding.state.revision}"
                        }
                    }
                }.onFailure {
                    binding.caseVisibilityDisabled = true
                    ModuleLog.warn(TAG, "native case layout incompatible; retaining native rendering", it)
                }
            }
        }.onFailure { ModuleLog.warn(TAG, "native case visibility extension unavailable", it) }
    }

    fun update(state: EarbudState, session: String, metadata: NativePresentationMetadata, producer: IBinder) {
        controllers.values.toList().filter { it.state.address == state.address }.forEach { binding ->
            if (!binding.active || binding.closing || binding.activity.isFinishing) return@forEach
            val updated = binding.request.updated(state, session, metadata, producer)
            if (updated == null) {
                close(binding, "session ended or ownership changed")
            } else if (state.revision > binding.state.revision) {
                val previous = projection(binding)
                binding.request = updated
                val p = projection(binding)
                if (previous.singleBattery != p.singleBattery || previous.artwork != p.artwork) {
                    // PairingDialog.setCustomView only applies before the first show. Retire this
                    // owned Dialog and let the native Activity create its replacement; no View edits.
                    val info = requireNotNull(NativeMembers.get(binding.controller, "mAirPodsInfo"))
                    setInt(info, "setWearType", if (p.singleBattery) 3 else 0)
                    if (NativeMembers.call(binding.controller, "initParameter") != true) {
                        close(binding, "resource initialization failed")
                        return@forEach
                    }
                    val dialog = NativeMembers.get(binding.controller, "mAlert") as Dialog
                    dialog.setOnDismissListener(null)
                    dialog.setOnCancelListener(null)
                    dialog.dismiss()
                    NativeMembers.set(binding.activity, "mPairingAlert", null)
                    setInt(binding.controller, "setConnectDeviceDialog", 0)
                    setInt(binding.controller, "goToSuitableDialog", 0)
                }
                binding.callback?.let { callback ->
                    runCatching {
                        callbackType.getMethod("deviceBatteryStateUpdate", String::class.java,
                            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .invoke(callback, p.address, true, charging(p),
                                p.left.percent ?: -1, p.right.percent ?: -1, p.chargingCase.percent ?: -1)
                    }.onFailure { close(binding, "native update failed") }
                }
                ModuleLog.debug(TAG, "updated address=${maskBluetoothAddress(p.address)} revision=${state.revision}")
            }
        }
    }

    fun closeAll(reason: String) = controllers.values.toList().forEach { close(it, reason) }

    private fun localService(binding: Binding): Binder {
        val binder = Binder()
        val proxy = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { instance, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "HyperEars.NativeWindow"
                "registerForAirPods" -> { if (binding.active) binding.callback = args?.get(0); null }
                "unregisterForAirPods" -> { binding.callback = null; null }
                "getDeviceConnectStateForAirPods" -> if (binding.active && args?.get(0) == binding.state.address) 2 else 0
                "notifyActivityIsFrontForAirPods" -> {
                    if (args?.get(0) == false) handler.post { close(binding, "background") }
                    null
                }
                else -> {
                    handler.post { close(binding, "unsupported service call ${method.name}") }
                    throw RemoteException("Unsupported native display operation")
                }
            }
        } as IInterface
        binder.attachInterface(proxy, serviceType.name)
        return binder
    }

    private fun projection(binding: Binding) = requireNotNull(NativeHeadsetProjection.from(binding.state))

    private fun charging(p: NativeHeadsetProjection): Int =
        (if (p.left.charging) 1 else 0) or (if (p.right.charging) 2 else 0) or
            (if (p.chargingCase.charging) 4 else 0)

    private fun setInt(owner: Any, method: String, value: Int) =
        NativeMembers.method(owner.javaClass, method, Int::class.javaPrimitiveType!!).invoke(owner, value)

    private fun close(binding: Binding, reason: String) {
        if (!binding.active || binding.closing || binding.activity.isFinishing) return
        binding.closing = true
        binding.navigation?.release()
        binding.navigation = null
        ModuleLog.debug(TAG, "close address=${maskBluetoothAddress(binding.state.address)} reason=$reason")
        val closed = binding.callback?.let {
            runCatching { callbackType.getMethod("closeDialogForAirPods", String::class.java)
                .invoke(it, binding.state.address) }.isSuccess
        } == true
        if (!closed) binding.activity.finish()
    }

    private fun release(binding: Binding) {
        if (!binding.active) return
        binding.active = false
        binding.navigation?.release()
        binding.navigation = null
        binding.callback = null
        binding.bound = false
        controllers.remove(binding.controller)
        activities.remove(binding.activity)
        runCatching { binding.request.producer.unlinkToDeath(binding.death, 0) }
        onClosed(binding.request.id)
        ModuleLog.debug(TAG, "released address=${maskBluetoothAddress(binding.state.address)}")
    }

    companion object {
        const val PREFIX = "com.android.bluetooth.ble.app.fastconnect"
        const val CONTROLLER = "$PREFIX.airpods.MiuiAirPodsFastConnectController"
        private const val TAG = "NativeWindow"
    }
}
