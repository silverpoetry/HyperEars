package dev.hyperears.presentation

import android.app.Activity
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

/** Owns explicitly authorized native windows and local display bindings, never device protocols. */
internal abstract class NativeWindowBridge(
    protected val context: Context,
    private val forBinding: (NativeWindowRequest) -> NativeWindowRequest?,
    private val onOpened: (String, IBinder) -> Unit,
    private val onClosed: (String) -> Unit,
    private val openSettings: (Context, EarbudState) -> Boolean,
) : HookContext() {
    protected val handler = Handler(Looper.getMainLooper())
    private val controllers = IdentityHashMap<Any, Binding>()
    private val activities = IdentityHashMap<Any, Binding>()
    private val creating = ThreadLocal<Binding?>()
    private val consumedTickets = mutableSetOf<String>()
    private val artwork = NativeArtworkLoader(context)
    protected lateinit var controllerType: Class<*>
    protected lateinit var callbackType: Class<*>
    private lateinit var serviceType: Class<*>
    private lateinit var resourceInitializer: Method
    private var enabled = false

    protected class Binding(
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
        var navigation: ContentNavigation? = null
        lateinit var binder: Binder
        lateinit var death: IBinder.DeathRecipient
    }

    protected class ContentNavigation(val view: View) {
        private val clickable = view.isClickable
        private val focusable = view.isFocusable

        fun release() {
            view.setOnClickListener(null)
            view.isClickable = clickable
            view.isFocusable = focusable
        }
    }

    protected abstract val serviceContract: NativeWindowServiceContract
    protected abstract fun resolveSymbols(): NativeWindowSymbols
    protected abstract fun installModelHooks()
    protected abstract fun synchronizeModel(binding: Binding)
    protected open fun prepareNativeCallers() = Unit
    protected open fun afterCreate(binding: Binding) = Unit
    protected open fun destroyNative(binding: Binding, proceed: () -> Any?): Any? = proceed()
    protected open fun destroyRejected(controller: Any): Any? = null
    protected open fun onReleased(binding: Binding) = Unit
    protected open fun twsLayout(binding: Binding): Boolean = !projection(binding).singleBattery

    protected open fun installInitializationHooks(init: Method) {
        hookBefore(init) { bindingFor(instance)?.let(::synchronizeModel) }
        hookAfter(init) { bindingFor(instance)?.let(::updateArtwork) }
    }

    protected fun bindingFor(controller: Any?): Binding? = controllers[controller]
    protected fun creatingBinding(): Binding? = creating.get()
    protected fun bindingForInfo(info: Any?, field: String): Binding? = controllers.values.firstOrNull {
        it.active && NativeMembers.get(it.controller, field) === info
    }

    protected fun authorizedRequest(activity: Activity): NativeWindowRequest? =
        if (!enabled) null else runCatching { NativeWindowRequest.read(activity)?.let(forBinding) }.getOrNull()

    final override fun install() {
        val symbols = resolveSymbols()
        controllerType = symbols.controller
        serviceType = symbols.service
        callbackType = symbols.callback
        val create = symbols.create
        val destroy = symbols.destroy
        val load = symbols.load
        val init = symbols.init
        resourceInitializer = symbols.resourceInit
        val dynamic = symbols.dynamic
        prepareNativeCallers()

        module.hook(create).intercept { chain ->
            val controller = requireNotNull(chain.thisObject)
            val activity = NativeMembers.get(controller, "mActivityContext") as Activity
            if (!activity.intent.hasExtra(NativePresentationContract.EXTRA_WINDOW)) return@intercept chain.proceed()
            if (!enabled) {
                activity.finish()
                return@intercept null
            }
            val request = authorizedRequest(activity)
            if (request == null || request.kind != symbols.kind || !consumedTickets.add(request.id)) {
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
            // Keep the display binding through every failure path, so destroy can never fall
            // through to an OEM protocol lifecycle after a partially initialized module window.
            controllers[controller] = binding
            activities[activity] = binding
            runCatching { request.producer.linkToDeath(binding.death, 0) }.getOrElse {
                binding.closing = true
                activity.finish()
                return@intercept null
            }
            val previous = creating.get()
            val originalDynamic = dynamic.getBoolean(null)
            creating.set(binding)
            try {
                chain.proceed().also {
                    check(!activity.isFinishing) { "Native window rejected its display parameters" }
                    afterCreate(binding)
                    check(binding.active && !binding.closing && !activity.isFinishing) { "Native display closed during initialization" }
                    onOpened(request.id, binding.binder)
                    ModuleLog.debug(TAG, "created address=${maskBluetoothAddress(request.state.address)} " +
                        "revision=${request.state.revision} single=${projection(binding).singleBattery}")
                }
            } catch (error: Throwable) {
                binding.closing = true
                activity.finish()
                ModuleLog.warn(TAG, "native window initialization failed", error)
                null
            } finally {
                dynamic.setBoolean(null, originalDynamic)
                if (previous == null) creating.remove() else creating.set(previous)
            }
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
            initializeStaticResources(binding)
            result = true
        }
        installInitializationHooks(init)

        module.hook(destroy).intercept { chain ->
            val binding = controllers[chain.thisObject]
            try {
                if (binding != null) {
                    destroyNative(binding) { chain.proceed() }
                } else {
                    val owner = requireNotNull(chain.thisObject)
                    val activity = NativeMembers.get(owner, "mActivityContext") as? Activity
                    if (activity?.intent?.hasExtra(NativePresentationContract.EXTRA_WINDOW) == true) {
                        destroyRejected(owner)
                    } else chain.proceed()
                }
            } finally { binding?.let(::release) }
        }
        installModelHooks()
        installContentNavigation(symbols.contentReady)
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

    protected fun installBatteryLayout(layout: NativePopupBatteryLayout) {
        hookAfter(layout.render) {
            val binding = controllers[instance] ?: return@hookAfter
            if (!binding.active || binding.closing || binding.activity.isFinishing) return@hookAfter
            runCatching {
                val root = args[0] as? View ?: return@runCatching
                if (root !== NativeMembers.get(binding.controller, "mView")) return@runCatching
                val p = projection(binding)
                if (layout.apply(root, p)) {
                    ModuleLog.debug(TAG) {
                        "battery layout updated in place single=${p.singleBattery} " +
                            "case=${p.showCaseInPopup} revision=${binding.state.revision}"
                    }
                }
            }.onFailure {
                ModuleLog.warn(TAG, "native battery layout incompatible", it)
                close(binding, "battery layout unavailable")
            }
        }
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
                // Keep the existing View, Dialog, animation state and close/navigation listeners.
                synchronizeModel(binding)
                if (previous.artwork != p.artwork || previous.form != p.form) updateArtwork(binding)
                runCatching { publishBattery(binding) }.onFailure { close(binding, "native update failed") }
                ModuleLog.debug(TAG, "updated address=${maskBluetoothAddress(p.address)} revision=${state.revision}")
            }
        }
    }

    fun closeAll(reason: String) = controllers.values.toList().forEach { close(it, reason) }

    protected open fun publishBattery(binding: Binding) {
        val p = projection(binding)
        binding.callback?.let { callback ->
            callbackType.getMethod("deviceBatteryStateUpdate", String::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(callback, p.address, true, charging(p),
                    p.left.percent ?: -1, p.right.percent ?: -1, p.chargingCase.percent ?: -1)
        }
    }

    private fun localService(binding: Binding): Binder {
        val binder = Binder()
        val proxy = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { instance, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "HyperEars.NativeWindow"
                serviceContract.register -> { if (binding.active) binding.callback = args?.get(0); null }
                serviceContract.unregister -> { binding.callback = null; null }
                serviceContract.connectedState -> serviceContract.connectionState(binding.state.address, binding.active, args)
                serviceContract.foreground -> {
                    if (args?.get(0) == false) handler.post { close(binding, "background") }
                    null
                }
                in serviceContract.ignoredResourceCalls -> null
                else -> {
                    handler.post { close(binding, "unsupported service call ${method.name}") }
                    throw RemoteException("Unsupported native display operation")
                }
            }
        } as IInterface
        binder.attachInterface(proxy, serviceType.name)
        return binder
    }

    protected fun projection(binding: Binding) = requireNotNull(NativeHeadsetProjection.from(binding.state))

    protected fun initializeStaticResources(binding: Binding) {
        val owner = binding.controller
        val record = requireNotNull(NativeMembers.get(owner, "mCloudResRecord"))
        NativeMembers.set(record, "mLayout", 1)
        NativeMembers.set(record, "mTwsHeadset", twsLayout(binding))
        NativeMembers.set(record, "mAllowSync", false)
        NativeMembers.set(record, "mDeviceName", projection(binding).name)
        NativeMembers.set(record, "mRecordDocument", null)
        val resources = requireNotNull(NativeMembers.get(owner, "mLocalResource"))
        if (NativeMembers.call(resources, "isInit") == true) NativeMembers.call(resources, "cleanUp")
        resourceInitializer.invoke(resources,
            "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 800, false, false)
    }

    protected fun updateArtwork(binding: Binding) {
        val p = projection(binding)
        val defaults = HeadsetArtwork.defaultFor(p.form)
        @Suppress("UNCHECKED_CAST")
        val icons = NativeMembers.get(binding.controller, "mHeadsetIconDrawable") as Array<Drawable?>
        icons.indices.forEach { icons[it] = artwork.load(p.artwork.headset, defaults.headset) }
        NativeMembers.set(binding.controller, "mHeadsetBoxIconDrawable",
            artwork.load(p.artwork.chargingCase ?: HeadsetArtwork.EARBUDS.chargingCase!!,
                HeadsetArtwork.EARBUDS.chargingCase!!))
    }

    protected fun charging(p: NativeHeadsetProjection): Int =
        (if (p.left.charging) 1 else 0) or (if (p.right.charging) 2 else 0) or
            (if (p.chargingCase.charging) 4 else 0)

    protected fun setInt(owner: Any, method: String, value: Int) =
        NativeMembers.method(owner.javaClass, method, Int::class.javaPrimitiveType!!).invoke(owner, value)

    protected fun close(binding: Binding, reason: String) {
        if (!binding.active || binding.closing || binding.activity.isFinishing) return
        binding.closing = true
        binding.navigation?.release()
        binding.navigation = null
        ModuleLog.debug(TAG, "close address=${maskBluetoothAddress(binding.state.address)} reason=$reason")
        val closed = binding.callback?.let {
            runCatching {
                if (serviceContract.closeWithAddress) callbackType.getMethod(serviceContract.close, String::class.java).invoke(it, binding.state.address)
                else callbackType.getMethod(serviceContract.close).invoke(it)
            }.isSuccess
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
        runCatching { onReleased(binding) }.onFailure { ModuleLog.warn(TAG, "native display cleanup failed", it) }
        runCatching { binding.request.producer.unlinkToDeath(binding.death, 0) }
        onClosed(binding.request.id)
        ModuleLog.debug(TAG, "released address=${maskBluetoothAddress(binding.state.address)}")
    }

    companion object {
        const val PREFIX = "com.android.bluetooth.ble.app.fastconnect"
        private const val TAG = "NativeWindow"
    }
}
