package dev.hyperears.presentation

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.view.View
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.ModuleLog
import dev.hyperears.integration.EarbudState
import java.lang.reflect.Method

/** Display-only use of the old Xiaomi controller; it never binds the real FastConnect service. */
internal class LegacyXiaomiWindowBridge(
    context: Context,
    forBinding: (NativeWindowRequest) -> NativeWindowRequest?,
    onOpened: (String, IBinder) -> Unit,
    onClosed: (String) -> Unit,
    openSettings: (Context, EarbudState) -> Boolean,
) : NativeWindowBridge(context, forBinding, onOpened, onClosed, openSettings) {
    private lateinit var symbols: LegacyWindowSymbols
    override val serviceContract = NativeWindowServiceContract.LEGACY
    override fun resolveSymbols(): NativeWindowSymbols = LegacyWindowSymbols(appClassLoader).also { symbols = it }
    override fun twsLayout(binding: Binding): Boolean = true

    @SuppressLint("DiscouragedApi") // Exact native drawable names, verified in this plugin's ARSC.
    override fun installInitializationHooks(init: Method) {
        module.hook(init).intercept { chain ->
            val binding = bindingFor(chain.thisObject) ?: return@intercept chain.proceed()
            synchronizeModel(binding)
            initializeStaticResources(binding)
            val owner = binding.controller
            val darkIcons = NativeMembers.get(owner, "mBatteryDarkIconIds") as IntArray
            val resources = binding.activity.resources
            val namespace = resources.getResourcePackageName(darkIcons.first())
            val names = listOf("fc_0000_battery_0", "fc_0000_battery_20", "fc_0000_battery_40",
                "fc_0000_battery_60", "fc_0000_battery_80", "fc_0000_battery_100", "fc_0000_battery_disable")
            val icons = names.map { name -> resources.getIdentifier(name, "drawable", namespace)
                .also { require(it != 0) { "Missing native battery drawable: $name" } } }.toIntArray()
            NativeMembers.set(owner, "mBatteryIconIds", icons)
            NativeMembers.set(owner, "mDeviceId", "")
            NativeMembers.set(owner, "mNeedNewExposeUI", true)
            val resetText = NativeMembers.field(owner.javaClass, "mTextResetHeadset").getInt(owner)
            NativeMembers.set(owner, "mStringResetHeadset", binding.activity.getString(resetText))
            updateArtwork(binding)
            true
        }
    }

    override fun prepareNativeCallers() {
        listOf(symbols.create, symbols.init, symbols.modifyView,
            NativeMembers.method(symbols.activity, "onCreate", Bundle::class.java)).forEach {
            require(module.deoptimize(it)) { "Legacy display caller unavailable: ${it.name}" }
        }
    }

    override fun installModelHooks() {
        hookBefore(symbols.createController) {
            val activity = NativeMembers.get(requireNotNull(instance), "mActivityContext") as Activity
            if (!activity.intent.hasExtra(NativePresentationContract.EXTRA_WINDOW)) return@hookBefore
            val request = authorizedRequest(activity)
            if (request?.kind != NativeWindowKind.LEGACY_XIAOMI) {
                activity.finish()
                result = null
            } else {
                result = symbols.controller.getConstructor(symbols.activity).newInstance(activity)
            }
        }
        hookBefore(symbols.valid) {
            if (bindingForInfo(instance, "mHeadsetInfo") != null) result = true
        }
        hookBefore(symbols.deviceId) {
            if (bindingForInfo(instance, "mHeadsetInfo") != null) result = ""
        }
        hookBefore(symbols.parse) {
            val binding = bindingFor(instance) ?: return@hookBefore
            synchronizeModel(binding)
            // The native parser can change LE Audio pairing. Only our accepted projection is used.
            result = null
        }
        hookBefore(symbols.updateTws) {
            if (bindingForInfo(instance, "mHeadsetInfo") != null) result = null
        }
        // Native broadcast telemetry and connection requests are outside this display session.
        listOf(symbols.registerReceiver, symbols.startPair, symbols.selectDialog).forEach { method ->
            hookBefore(method) { if (bindingFor(instance) != null) result = null }
        }
        hookBefore(symbols.nativeBatteryCallback) {
            val binding = bindingFor(instance) ?: return@hookBefore
            publishBattery(binding)
            result = null
        }
        hookAfter(symbols.renderBattery) {
            val binding = bindingFor(instance) ?: return@hookAfter
            if (!binding.active || binding.closing || binding.activity.isFinishing) return@hookAfter
            val root = args[0] as? View ?: return@hookAfter
            if (root !== NativeMembers.get(binding.controller, "mView")) return@hookAfter
            runCatching { renderBattery(binding, root) }.onFailure {
                ModuleLog.warn("NativeLegacyWindow", "native battery layout unavailable", it)
                close(binding, "legacy battery layout unavailable")
            }
        }
    }

    override fun synchronizeModel(binding: Binding) {
        val owner = binding.controller
        val info = requireNotNull(NativeMembers.get(owner, "mHeadsetInfo"))
        val p = projection(binding)
        // The native diagnostic formatter expects a non-null array. This is an empty display
        // value, not a synthesized advertisement; the radio parser is never executed.
        symbols.setAdvertisement.invoke(info, ByteArray(0))
        fun string(name: String, value: String) = symbols.stringSetters.getValue(name).invoke(info, value)
        fun flag(name: String, value: Boolean) = symbols.flagSetters.getValue(name).invoke(info, value)
        fun number(name: String, value: Int) = symbols.intSetters.getValue(name).invoke(info, value)
        string("setCurrentDeviceAddress", p.address)
        string("setPeerDeviceAddress", "")
        number("setCurrentConnectState", 2)
        number("setPeerConnectState", 0)
        flag("setTwsHeadset", true)
        flag("setTwsConnected", true)
        flag("setLeftConnected", p.left.percent != null)
        flag("setRightConnected", p.right.percent != null)
        number("setBatteryLeftPercent", p.left.percent ?: 0)
        number("setBatteryRightPercent", p.right.percent ?: 0)
        number("setBatteryBoxPercent", p.chargingCase.percent ?: 0)
        flag("setBatteryLeftCharge", p.left.charging)
        flag("setBatteryRightCharge", p.right.charging)
        flag("setBatteryBoxCharge", p.chargingCase.charging)
        NativeMembers.set(owner, "mDeviceNameForDialog", p.name)
        NativeMembers.set(owner, "mDeviceNameForDialogLocal", p.name)
        NativeMembers.set(owner, "mPairFirstTime", false)
    }

    override fun afterCreate(binding: Binding) { publishBattery(binding) }

    override fun publishBattery(binding: Binding) {
        if (!binding.active || binding.closing || binding.activity.isFinishing) return
        synchronizeModel(binding)
        // Static artwork needs no discovery or success-animation loading phase.
        NativeMembers.set(binding.controller, "mCanRunSuccessAnimation", true)
        val message = Message.obtain()
        try { symbols.renderConnected.invoke(binding.controller, message, false, false) }
        finally { message.recycle() }
    }

    private fun renderBattery(binding: Binding, root: View) {
        val owner = binding.controller
        val dark = binding.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val icons = NativeMembers.get(owner, if (dark) "mBatteryDarkIconIds" else "mBatteryIconIds") as IntArray
        val resources = requireNotNull(NativeMembers.get(owner, "mLocalResource"))
        val getDrawable = NativeMembers.method(resources.javaClass, "getDrawable", Int::class.javaPrimitiveType!!)
        val newStyle = NativeMembers.get(owner, "mNewExposeUI") == "new_expose_style" &&
            NativeMembers.get(owner, "mNeedNewExposeUI") == true
        val labelId = NativeMembers.field(owner.javaClass, "mTextHeadset").getInt(owner)
        symbols.resources.apply(binding.activity, root, projection(binding), newStyle, icons,
            binding.activity.getString(labelId)) {
            getDrawable.invoke(resources, it) as? Drawable
        }
    }

    override fun destroyNative(binding: Binding, proceed: () -> Any?): Any? {
        // The stock destroy method requests pairing and clears shared Auracast settings. Our
        // window owns only its resource loader and Handler; the Activity still closes natively.
        cleanUpController(binding.controller)
        return null
    }

    override fun destroyRejected(controller: Any): Any? {
        cleanUpController(controller)
        return null
    }

    private fun cleanUpController(owner: Any) {
        runCatching {
            val lock = requireNotNull(NativeMembers.get(owner, "mMainHandlerLock"))
            synchronized(lock) {
                (NativeMembers.get(owner, "mMainHandler") as? Handler)?.removeCallbacksAndMessages(null)
                NativeMembers.set(owner, "mMainHandler", null)
            }
        }.onFailure { ModuleLog.warn("NativeLegacyWindow", "native display timer cleanup failed", it) }
        runCatching { NativeMembers.get(owner, "mLocalResource")?.let { NativeMembers.call(it, "cleanUp") } }
            .onFailure { ModuleLog.warn("NativeLegacyWindow", "native display resource cleanup failed", it) }
        NativeMembers.set(owner, "mFastConnectService", null)
    }

    override fun onReleased(binding: Binding) {
        symbols.resources.release(NativeMembers.get(binding.controller, "mView") as? View)
    }
}
