package dev.hyperears.hook

import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import dev.hyperears.integration.*
import dev.hyperears.presentation.NativeArtworkLoader
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Scoped presentation bridge into Xiaomi's TWS fragment; each adapter retains its own protocol. */
internal class SettingsNativePluginBridge(
    private val current: (String) -> SettingsHeadsetState.Record?,
    private val send: (SettingsHeadsetState.Record, ControlRequest) -> Unit,
    private val attach: (Any, View) -> Unit,
    private val detach: (Any) -> Unit,
) : HookContext() {
    private data class Route(val token: String, val producer: android.os.IBinder)
    private val routes = mutableMapOf<String, Route>()
    private val activities = WeakHashMap<Activity, String>()
    private val fragments = WeakHashMap<Any, String>()
    private val renderedImages = WeakHashMap<ImageView, String>()
    private var artworkLoader: NativeArtworkLoader? = null
    private val loaders = java.util.Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    val active get() = routes.isNotEmpty()
    private fun record(address: String): SettingsHeadsetState.Record? {
        val route = routes[address] ?: return null
        return current(address)?.takeIf { it.token == route.token && it.producer == route.producer &&
            it.state.address.equals(address, true) && NativeHeadsetPageModel.eligible(it.state) }
    }
    fun open(context: Context, device: BluetoothDevice, value: SettingsHeadsetState.Record, direct: Boolean): Boolean {
        if (!NativeHeadsetPageModel.eligible(value.state) || !value.state.address.equals(device.address, true)) return false
        routes[device.address] = Route(value.token, value.producer)
        return runCatching {
            // Re-read the current adapter settings on entry.
            if (value.state.privateChannelConnected && value.state.handshakeAccepted &&
                value.state.lifecycle.controlOwnership == ControlOwnership.MODULE) send(value, StandardControlRequest.Refresh)
            context.startActivity(Intent("miui.bluetooth.action.HEADSET_SETTINGS_PLUGIN")
                .setClassName("com.android.settings", if (direct) "plugin.settings.java.JavaActivity" else "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra("bluetoothaddress", device.address)
                .putExtra("MIUI_HEADSET_SUPPORT", NativeHeadsetPageModel.support(device.address, value.state))
                .putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS"))
            ModuleLog.debug("NativeHeadsetPage", "native TWS page requested")
            true
        }.getOrElse { routes.remove(device.address); ModuleLog.warn("NativeHeadsetPage", "launch failed", it); false }
    }
    override fun install() {
        module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnCreate", Activity::class.java, Bundle::class.java)).intercept { chain ->
            val activity = chain.args[0] as Activity
            if (activity.javaClass.name == "plugin.settings.java.JavaActivity") {
                val device = activity.intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device != null && record(device.address) != null) {
                    runCatching { installPlugin(activity.javaClass.classLoader!!) }.onSuccess {
                        activities[activity] = device.address
                    }.onFailure {
                        routes.remove(device.address)
                        ModuleLog.warn("NativeHeadsetPage", "plugin binding rejected", it)
                        activity.finish()
                    }
                }
            }
            chain.proceed()
        }
        module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnDestroy", Activity::class.java)).intercept { chain ->
            val activity = chain.args[0] as Activity
            try { chain.proceed() } finally {
                activities.remove(activity)?.let { address ->
                    if (!activity.isChangingConfigurations) routes.remove(address)
                    detach(activity)
                }
            }
        }
    }
    @android.annotation.SuppressLint("PrivateApi")
    private fun installPlugin(loader: ClassLoader) {
        if (loader in loaders) return
        val tx = HookInstallTransaction<io.github.libxposed.api.XposedInterface.HookHandle> { it.unhook() }
        val fragment = loader.loadClass("plugin.settings.java.MiuiHeadsetFragment")
        val anc = loader.loadClass("plugin.settings.java.tws.anc.MiuiHeadsetAnc")
        val proxy = loader.loadClass("com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy")
        val ble = loader.loadClass("plugin.settings.java.tws.BleMmaUtil")
        val animation = loader.loadClass("plugin.settings.java.tws.MiuiHeadsetAnimation")
        tx.install {
            CustomPreferenceRows.install(module,loader)
            val seek = loader.loadClass("androidx.preference.SeekBarPreference")
            seek.declaredMethods.firstOrNull { it.name == "onBindViewHolder" && it.parameterCount == 1 }?.let { method ->
                tx.record(module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { runCatching { SettingsPreferenceHost.bindRange(it) }
                        .onFailure { error -> ModuleLog.warn("NativeHeadsetPage", "slider scale bind failed", error) } }
                    result
                })
            }
            tx.record(module.hook(animation.getDeclaredMethod("loadDefaultInternal")).intercept { chain ->
                val target = requireNotNull(chain.thisObject)
                val context = (getObjectField(target, "mContext") as? java.lang.ref.WeakReference<*>)?.get() as? Activity
                val value = activities[context]?.let(::record)
                val root = (getObjectField(target, "mRootView") as? java.lang.ref.WeakReference<*>)?.get() as? View
                if (value == null || root == null) chain.proceed() else {
                    val id = loader.loadClass("plugin.settings.java.R\$id").getDeclaredField("tic").getInt(null)
                    val image = root.findViewById<ImageView>(id)
                    if (image == null) chain.proceed() else {
                        val art = requireNotNull(value.state.adapter).artwork
                        val images = artworkLoader ?: NativeArtworkLoader(root.context).also { artworkLoader = it }
                        val drawable = images.load(NativeHeadsetPageModel.artwork(value.state), art.headset)
                        root.post {
                            if (activities[context]?.let(::record)?.token == value.token) {
                                image.setImageDrawable(drawable)
                                renderedImages[image] = NativeHeadsetPageModel.artwork(value.state).value
                            }
                        }
                        null
                    }
                }
            })
            val names = setOf("checkSupport", "getDeviceInfo", "connect", "disconnect", "getDeviceConfig", "getCommonConfig", "setCommonCommand", "isMiTWS", "checkIsMiTWS", "getRingFindState", "changeAncMode", "changeAncLevel")
            proxy.declaredMethods.filter { it.name in names }.forEach { method ->
                tx.record(module.hook(method).intercept { chain ->
                    val device = chain.args.filterIsInstance<BluetoothDevice>().firstOrNull()
                    val address = device?.address ?: chain.args.filterIsInstance<String>().firstOrNull { it in routes }
                    val value = address?.let(::record)
                    if (value == null) chain.proceed() else when (method.name) {
                        "checkSupport", "getDeviceInfo" -> NativeHeadsetPageModel.support(address, value.state)
                        "isMiTWS", "checkIsMiTWS" -> true
                        "getRingFindState" -> false
                        "setCommonCommand" -> "" // No fabricated wear status or successful native commands.
                        else -> null // Private transport and controls belong to the typed device adapter.
                    }
                })
            }
            ble.declaredMethods.filter { it.name == "isBleMmaConnect" && it.returnType == Boolean::class.javaPrimitiveType }.forEach { method ->
                tx.record(module.hook(method).intercept { chain ->
                    val value = chain.args.filterIsInstance<BluetoothDevice>().firstOrNull()?.address?.let(::record)
                    if (value == null) chain.proceed() else value.state.privateChannelConnected
                })
            }
            tx.record(module.hook(anc.getDeclaredMethod("updateAncMode", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)).intercept { chain ->
                val device = getObjectField(requireNotNull(chain.thisObject), "mDevice") as? BluetoothDevice
                val value = device?.address?.let(::record)
                if (value == null) chain.proceed() else {
                    if (chain.args[1] == true) {
                        val mode = when (chain.args[0]) { 0 -> NoiseMode.OFF; 1 -> NoiseMode.ANC; 2 -> NoiseMode.TRANSPARENCY; else -> null }
                        mode?.let { NativeHeadsetPageModel.noiseRequest(value.state, device.address, it) }?.let { send(value, it) }
                    }
                    null // Native selection updates only after confirmed device readback.
                }
            })
            tx.record(module.hook(anc.getDeclaredMethod("updateAncUi", String::class.java, Boolean::class.javaPrimitiveType)).intercept { chain ->
                val target = requireNotNull(chain.thisObject)
                val device = getObjectField(target, "mDevice") as? BluetoothDevice
                val value = device?.address?.let(::record)
                val result = if (value == null) chain.proceed() else
                    chain.proceed(arrayOf<Any>(NativeHeadsetPageModel.anc(value.state) ?: "-1", false))
                val root = (getObjectField(target, "mRootView") as? java.lang.ref.WeakReference<*>)?.get() as? View
                if (value != null && root != null) bindAncButtons(root, loader, value)
                result
            })
            tx.record(module.hook(fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)).intercept { chain ->
                val result = chain.proceed()
                val target = requireNotNull(chain.thisObject)
                val activity = target.javaClass.getMethod("getActivity").invoke(target) as? Activity
                activities[activity]?.let { address -> record(address)?.let { value ->
                    fragments[target] = address
                    attach(target, chain.args[0] as View)
                    render(target, value.state)
                    ModuleLog.debug("NativeHeadsetPage", "native TWS fragment attached")
                } }
                result
            })
            tx.record(module.hook(fragment.getDeclaredMethod("onDestroyView")).intercept { chain ->
                chain.thisObject?.let { fragments.remove(it); detach(it) }
                chain.proceed()
            })
            tx.record(module.hook(fragment.getDeclaredMethod("onResume")).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { target -> fragments[target]?.let(::record)?.let {
                    render(target, it.state)
                } }
                result
            })
        }
        loaders.add(loader)
    }
    fun update() {
        fragments.toMap().forEach { (fragment, address) -> record(address)?.let {
            runCatching { render(fragment, it.state) }.onFailure { error -> ModuleLog.warn("NativeHeadsetPage", "state render failed", error) }
        } }
        activities.toMap().forEach { (activity, address) -> if (record(address) == null && !activity.isFinishing) activity.finish() }
    }
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun render(fragment: Any, state: EarbudState) {
        val battery = getObjectField(fragment, "mMiuiHeadsetBattery")
        val levels = NativeHeadsetPageModel.battery(state).split(',').map(String::toInt)
        battery?.javaClass?.getMethod("onBatteryChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType)?.invoke(battery, levels[0], levels[1], levels[2])
        val anc = getObjectField(fragment, "mMiuiHeadsetAnc")
        NativeHeadsetPageModel.anc(state)?.let { level ->
            anc?.javaClass?.getMethod("updateAncUi", String::class.java, Boolean::class.javaPrimitiveType)?.invoke(anc, level, false)
        }
        val root = getObjectField(fragment, "mRootView") as? View ?: return
        state.address?.let(::record)?.let { bindAncButtons(root, fragment.javaClass.classLoader!!, it) }
        fun view(name: String): View? {
            val id = runCatching { fragment.javaClass.classLoader!!.loadClass("plugin.settings.java.R\$id")
                .getDeclaredField(name).getInt(null) }.getOrElse {
                root.resources.getIdentifier(name, "id", "com.android.settings")
            }
            return if (id == 0) null else root.findViewById(id)
        }
        // Renaming here is the phone's Bluetooth alias, independent of vendor HFP commands.
        view("renameLayout")?.let { row ->
            row.isEnabled = true
            row.setOnClickListener {
                val address = state.address ?: return@setOnClickListener
                if (record(address) == null) return@setOnClickListener
                val device = getObjectField(fragment, "mDevice") as? BluetoothDevice ?: return@setOnClickListener
                showAliasDialog(root.context, fragment.javaClass.classLoader!!, device) { name ->
                    (view("deviceName") as? android.widget.TextView)?.text = name
                    val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                    activity?.title = name
                    runCatching {
                        val bar = activity?.javaClass?.getMethod("getAppCompatActionBar")?.invoke(activity)
                        bar?.javaClass?.getMethod("setTitle", CharSequence::class.java)?.invoke(bar, name)
                    }
                }
            }
        }
        // Show only confirmed noise modes; Xiaomi depth presets are not Sony capabilities.
        listOf("ancAdjustView", "transparentAdjustView").forEach { view(it)?.visibility = View.GONE }
        view("transport")?.visibility = if (NoiseMode.TRANSPARENCY in NativeHeadsetPageModel.modes(state)) View.VISIBLE else View.GONE
        // Firmware checks and handoff live in the Coral controls, not Xiaomi's OTA service.
        (view("otaLayout")?.parent as? View)?.visibility = View.GONE
        (view("tic") as? ImageView)?.let { image ->
            val artwork = state.adapter?.artwork ?: return@let
            if (renderedImages[image] != NativeHeadsetPageModel.artwork(state).value) {
                val loader = artworkLoader ?: NativeArtworkLoader(root.context).also { artworkLoader = it }
                image.setImageDrawable(loader.load(NativeHeadsetPageModel.artwork(state), artwork.headset))
                renderedImages[image] = NativeHeadsetPageModel.artwork(state).value
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showAliasDialog(context: Context, loader: ClassLoader, device: BluetoothDevice, renamed: (String) -> Unit) {
        val old = BluetoothDevice::class.java.getMethod("getAlias").invoke(device) as? String ?: device.name.orEmpty()
        val input = android.widget.EditText(context).apply {
            isSingleLine = true
            setText(old)
            selectAll()
            filters = arrayOf(android.text.InputFilter.LengthFilter(248))
        }
        val type = loader.loadClass("miuix.appcompat.app.AlertDialog\$Builder")
        val builder = type.getConstructor(Context::class.java).newInstance(context)
        type.getMethod("setTitle", CharSequence::class.java).invoke(builder, "重命名")
        type.getMethod("setView", View::class.java).invoke(builder, input)
        type.getMethod("setNegativeButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java)
            .invoke(builder, "取消", null)
        val confirm = android.content.DialogInterface.OnClickListener { _, _ ->
            val name = input.text.toString().trim()
            val result = runCatching {
                require(name.isNotEmpty())
                val status = BluetoothDevice::class.java.getMethod("setAlias", String::class.java).invoke(device, name)
                check(status == 0) { "Bluetooth alias rejected: $status" }
                renamed(name)
            }
            result.onFailure {
                ModuleLog.warn("NativeHeadsetPage", "Bluetooth alias update failed", it)
                android.widget.Toast.makeText(context, "重命名失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        type.getMethod("setPositiveButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java)
            .invoke(builder, "保存", confirm)
        (type.getMethod("show").invoke(builder) as android.app.Dialog).apply {
            input.requestFocus()
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    private fun bindAncButtons(root: View, loader: ClassLoader, value: SettingsHeadsetState.Record) {
        val ids = loader.loadClass("plugin.settings.java.R\$id")
        val address = requireNotNull(value.state.address)
        val ready = NativeHeadsetPageModel.ready(value.state, address) && NativeHeadsetPageModel.anc(value.state) != null
        for ((name, mode) in listOf("openAnc" to NoiseMode.ANC, "closeAnc" to NoiseMode.OFF, "transport" to NoiseMode.TRANSPARENCY)) {
            val button = root.findViewById<View>(ids.getDeclaredField(name).getInt(null)) ?: continue
            button.visibility = if (mode in NativeHeadsetPageModel.modes(value.state)) View.VISIBLE else View.GONE
            button.isEnabled = ready && mode in NativeHeadsetPageModel.modes(value.state)
            button.setOnClickListener {
                val fresh = record(address)?.takeIf { it.token == value.token } ?: return@setOnClickListener
                ModuleLog.debug("NativeHeadsetPage", "ANC click current=${fresh.state.noiseMode} requested=$mode")
                NativeHeadsetPageModel.noiseRequest(fresh.state, address, mode)?.let { send(fresh, it) }
            }
        }
        // The stock 0100 mode also reveals Xiaomi's office/daily/travel depth selector.
        // Hide whole sections after every native render, not just their slider widgets.
        for (name in listOf("ancAdjust", "ancAdjustText", "transparentAdjust", "transparentAdjustText")) {
            root.findViewById<View>(ids.getDeclaredField(name).getInt(null))?.visibility = View.GONE
        }
    }
}
