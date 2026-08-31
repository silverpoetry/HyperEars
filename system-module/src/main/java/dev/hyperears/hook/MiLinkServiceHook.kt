package dev.hyperears.hook

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import dev.hyperears.bridge.BridgeStage
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.ModuleRuntimeGate
import dev.hyperears.bridge.ProcessStateStore
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.MiLinkStateCodec
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.NoiseModeFeatureState
import dev.hyperears.integration.withFeature
import dev.hyperears.settings.ModuleSettings
import dev.hyperears.settings.ModuleSettingsRuntime
import dev.hyperears.settings.MoreSettingsTarget
import java.io.Closeable
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Supplies the minimum truthful Xiaomi identity required for native audio handoff.
 *
 * A2DP routing remains entirely owned by HyperOS/MiLink. This bridge exposes identity,
 * battery and ANC state, and translates only the three verified noise-control commands.
 */
internal class MiLinkServiceHook : HookContext() {
    private data class SessionStages(
        val sessionToken: String,
        val stages: MutableSet<BridgeStage> = mutableSetOf(),
    )

    private val knownAddresses = Collections.synchronizedSet(mutableSetOf<String>())
    private val deviceOwnership = MiLinkDeviceOwnershipRegistry()
    private val pendingSystemOwnershipClaims =
        Collections.synchronizedSet(mutableSetOf<String>())
    private val runtimeOwners = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
    )
    private val targetHeadsetAddresses = Collections.synchronizedMap(
        WeakHashMap<Any, String>(),
    )
    private val observationLock = Any()
    private val sessionStages = mutableMapOf<String, SessionStages>()
    private val pendingStages = mutableMapOf<String, MutableSet<BridgeStage>>()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var settings = ModuleSettingsRuntime.current

    private var settingsSubscription: Closeable? = null

    @Volatile
    private var lastAncBatteryController: Any? = null

    @Volatile
    private var lastProfileContext: Any? = null

    @Volatile
    private var headsetDetailExtension: MiLinkHeadsetDetailExtension? = null

    override fun install() {
        settingsSubscription = ModuleSettingsRuntime.observe { updated ->
            val wasPaused = settings.modulePaused
            settings = updated
            if (updated.modulePaused && !wasPaused) clearModuleState()
        }
        hookApplicationContext()
        val runtimeClasses = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        )
        // Hook exactly one native-admission boundary. Prefer the service implementation so a
        // manager wrapper cannot observe and then reclassify the positive value injected below.
        // Older MiLink builds without that method fall back to the manager entry.
        val admissionHookInstalled = runtimeClasses.asReversed().any(::hookNativeHeadsetAdmission)
        if (!admissionHookInstalled) {
            ModuleLog.warn("MiLink", "no native headset-admission boundary available")
        }
        runtimeClasses.forEach { className ->
            hookContextEntry(className)
            hookBluetoothDeviceResult(className, "getDeviceId") { _, adapter ->
                MiLinkCarrierIdentity.deviceId(adapter)
            }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { device, adapter ->
                if (adapter.capabilities.battery) {
                    MiLinkStateCodec.regularBatteryLevel(stateFor(device))
                } else {
                    UNKNOWN_BATTERY_LEVEL
                }
            }
            hookBluetoothDeviceResult(className, "getAncState") { device, adapter ->
                nativeAncStateForSurface(stateFor(device), adapter)
            }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { _, _ -> 0 }
            hookBluetoothDeviceResult(className, "getWearStatus") { _, adapter ->
                "0,0".takeIf { adapter.capabilities.wearDetection }
            }
            hookBluetoothDeviceResult(className, "isLeAudio") { _, adapter ->
                false.takeIf { adapter.privateProtocolRequired }
            }

            hookAddressResult(className, "isMiTWS") { true }
            hookAddressResult(className, "isSupportAudioSwitch") { 1 }
            hookAddressResult(className, "getRingFindState") { false }

            hookNoiseCommand(className, "openAnc", NoiseMode.ANC)
            hookNoiseCommand(className, "closeAnc", NoiseMode.OFF)
            hookNoiseCommand(className, "openTransparent", NoiseMode.TRANSPARENCY)
        }

        hookHeadsetRuntime()
        hookSupportedAncModes()
        hookHeadsetPresentationMetadata()
        hookHeadsetDetailExtension()
    }

    /**
     * Publishes only optional HyperEars card presentation metadata.
     *
     * Device form is already represented by the stock carrier ID. Concrete model identity does
     * not belong in that ID; MiLink's extensible service-properties bundle is the appropriate
     * cross-device channel for an optional UI extension.
     */
    private fun hookHeadsetPresentationMetadata() {
        runCatching {
            val owner = findClass(
                "com.miui.circulate.api.protocol.headset.HeadsetDeviceManager",
            )
            val headsetInfoClass = findClass("com.miui.headset.api.HeadsetInfo")
            val serviceInfoClass =
                findClass("com.miui.circulate.api.service.CirculateServiceInfo")
            val method = owner.declaredMethods.single { candidate ->
                candidate.name == "convertToBluetoothService" &&
                    candidate.returnType == serviceInfoClass &&
                    candidate.parameterTypes.firstOrNull() == headsetInfoClass
            }.apply { isAccessible = true }
            hookAfter(method) {
                if (ModuleRuntimeGate.paused) return@hookAfter
                val headsetInfo = args.firstOrNull() ?: return@hookAfter
                val address = rawHeadsetAddress(headsetInfo) ?: return@hookAfter
                val state = stateForAddress(address).takeIf(EarbudState::sessionActive)
                    ?: return@hookAfter
                val adapter = state.adapter
                    ?: return@hookAfter
                val presentationId = adapter.presentationId ?: return@hookAfter
                val serviceInfo = result ?: return@hookAfter
                val properties = runCatching {
                    getObjectField(serviceInfo, "serviceProperties")
                }.getOrNull() ?: return@hookAfter
                val bundle = runCatching {
                    callMethod(properties, "getAll") as? Bundle
                }.getOrNull() ?: return@hookAfter
                bundle.putInt(
                    MiLinkPresentationContract.SCHEMA_KEY,
                    MiLinkPresentationContract.SCHEMA_VERSION,
                )
                bundle.putString(
                    MiLinkPresentationContract.PRESENTATION_KEY,
                    presentationId.value,
                )
                ModuleLog.debug(
                    "MiLink",
                    "published presentation=${presentationId.value} " +
                        "address=${maskBluetoothAddress(address)}",
                )
            }
            ModuleLog.debug("MiLink", "headset presentation metadata installed")
        }.onFailure {
            ModuleLog.warn("MiLink", "headset presentation metadata unavailable", it)
        }
    }

    private fun hookHeadsetDetailExtension() {
        runCatching {
            val extension = MiLinkHeadsetDetailExtension(
                hostClassLoader = appClassLoader,
                stateProvider = ::stateForAddress,
                controlSender = { address, request ->
                    sendControl(request, address)
                },
            )
            hookAfter(
                findHeadsetDetailBindMethod(),
            ) {
                if (ModuleRuntimeGate.paused) return@hookAfter
                val root = instance as? View ?: return@hookAfter
                val serviceInfo = args.firstOrNull()
                val publishedPresentationId = presentationIdFrom(serviceInfo)
                val serviceAddress = serviceInfo?.let(::serviceInfoAddress)
                val address = serviceAddress
                    ?.takeIf(::isTargetAddress)
                    ?: args.firstNotNullOfOrNull(::headsetAddress)
                if (address == null) return@hookAfter
                extension.bind(
                    root = root,
                    address = address,
                    publishedPresentationId = publishedPresentationId,
                )
            }
            headsetDetailExtension = extension
            ModuleLog.debug("MiLink", "headset detail extension installed")
        }.onFailure {
            ModuleLog.warn("MiLink", "optional headset detail extension unavailable", it)
        }
    }

    private fun presentationIdFrom(serviceInfo: Any?): MiLinkCardPresentationId? {
        val properties = runCatching {
            getObjectField(serviceInfo, "serviceProperties")
        }.getOrNull() ?: return null
        val bundle = runCatching {
            callMethod(properties, "getAll") as? Bundle
        }.getOrNull() ?: return null
        return MiLinkPresentationContract.decode(
            schemaVersion = bundle.getInt(MiLinkPresentationContract.SCHEMA_KEY, 0),
            presentationId = bundle.getString(MiLinkPresentationContract.PRESENTATION_KEY),
        )
    }

    private fun serviceInfoAddress(serviceInfo: Any): String? =
        runCatching {
            getObjectField(serviceInfo, "deviceId") as? String
        }.getOrNull()?.takeIf(::isBluetoothAddress)

    private fun hookHeadsetSettingsNavigation() {
        MiLinkHeadsetSettingsNavigationBridge(
            contextProvider = { context },
            isHyperEarsCard = { serviceInfo, address ->
                presentationIdFrom(serviceInfo) != null || isTargetAddress(address)
            },
            serviceInfoAddress = ::serviceInfoAddress,
            openPreferredSettings = ::openPreferredHeadsetSettings,
        ).also {
            it.module = module
            it.appClassLoader = appClassLoader
            it.packageName = packageName
        }.install()
    }

    private fun openPreferredHeadsetSettings(address: String): Boolean {
        val state = stateForAddress(address)
        return when (settings.moreSettingsTarget) {
            MoreSettingsTarget.SYSTEM_SETTINGS -> openBluetoothDeviceSettings(address)
            MoreSettingsTarget.VENDOR_APP ->
                openControlApp(state) || openBluetoothDeviceSettings(address)
            MoreSettingsTarget.HYPEREARS ->
                openHyperEars() || openBluetoothDeviceSettings(address)
        }
    }

    private fun openHyperEars(): Boolean {
        val appContext = context ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(ModuleContract.MODULE_PACKAGE, HYPEREARS_MAIN_ACTIVITY)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { appContext.startActivity(intent) }
            .onFailure { ModuleLog.warn("MiLink", "unable to open HyperEars", it) }
            .isSuccess
    }

    private fun openControlApp(state: EarbudState): Boolean {
        val appContext = context ?: return false
        val adapter = state.adapter ?: return false
        val candidates = buildList {
            state.lifecycle.externalControlApp?.let(::add)
            adapter.controlApps.forEach { candidate ->
                if (none { it.packageName == candidate.packageName }) add(candidate)
            }
        }
        val launch = candidates.firstNotNullOfOrNull { controlApp ->
            appContext.packageManager
                .getLaunchIntentForPackage(controlApp.packageName)
                ?.let { controlApp to it }
        } ?: return false
        val (controlApp, intent) = launch
        return runCatching {
            appContext.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onSuccess {
            ModuleLog.debug(
                "MiLink",
                "opened ${controlApp.packageName} for ${maskBluetoothAddress(state.address)}",
            )
        }.onFailure {
            ModuleLog.warn(
                "MiLink",
                "unable to open ${controlApp.displayName}",
                it,
            )
        }.isSuccess
    }

    private fun openBluetoothDeviceSettings(address: String): Boolean {
        val appContext = context ?: return false
        val fragmentArguments = Bundle().apply {
            putString(EXTRA_DEVICE_ADDRESS, address)
        }
        val deviceDetails = Intent(ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS)
            .setPackage(SETTINGS_PACKAGE)
            .putExtra(EXTRA_DEVICE_ADDRESS, address)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, fragmentArguments)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (runCatching { appContext.startActivity(deviceDetails) }.isSuccess) return true

        val bluetoothSettings = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .setPackage(SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { appContext.startActivity(bluetoothSettings) }
            .onFailure {
                ModuleLog.warn("MiLink", "unable to open Bluetooth settings", it)
            }
            .isSuccess
    }

    /**
     * Finds HeadSetsDetail's semantic bind entry without depending on its obfuscated name.
     *
     * HyperOS 17.2.0 names this method `m`; 17.2.4 names it `p`. The stable contract is the
     * four model arguments produced by the headset pipeline.
     */
    private fun findHeadsetDetailBindMethod(): Method {
        val className = "com.miui.circulateplus.world.headset.HeadSetsDetail"
        return findClass(className).declaredMethods
            .single { method ->
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 4 &&
                    parameters[0].name ==
                    "com.miui.circulate.api.service.CirculateServiceInfo" &&
                    parameters[2].name ==
                    "com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo" &&
                    parameters[3].name ==
                    "com.miui.circulate.api.service.CirculateDeviceInfo"
            }
            .apply { isAccessible = true }
    }

    private fun hookApplicationContext() {
        runCatching {
            hookAfter(
                findMethod(
                    Application::class.java.name,
                    "attach",
                    Context::class.java,
                ),
            ) {
                registerStateReceiver(args[0] as? Context)
                hookHeadsetSettingsNavigation()
            }
        }.onFailure {
            ModuleLog.warn("MiLink", "Application.attach hook unavailable", it)
        }
    }

    private fun hookContextEntry(className: String) {
        runCatching {
            hookAfter(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                registerStateReceiver(args[0] as? Context)
            }
        }.onFailure {
            ModuleLog.debug("MiLink", "optional $className context entry unavailable")
        }
    }

    private fun hookHeadsetRuntime() {
        hookBluetoothDeviceResult(
            "com.miui.headset.runtime.ProfileContext",
            "getBatteryLevel",
        ) { device, _ ->
            batteryLevelsFor(stateFor(device)) ?: UNKNOWN_COMPONENT_BATTERY_LEVELS
        }
        hookBluetoothDeviceResult(
            "com.miui.headset.runtime.AncBatteryController",
            "getAncState",
        ) { device, adapter ->
            nativeAncStateForSurface(stateFor(device), adapter)
        }
        hookBluetoothDeviceResult(
            "com.miui.headset.runtime.AncBatteryController",
            "getBatteryLevelCache",
        ) { device, _ ->
            batteryLevelsFor(stateFor(device)) ?: UNKNOWN_COMPONENT_BATTERY_LEVELS
        }
        hookHeadsetPropertyRefresh()
        hookAddressResult(
            "com.miui.headset.runtime.AncBatteryController",
            "getSwitchState",
        ) { address ->
            adapterForAddress(address)
                ?.let(::supportsNativeAncSurface)
                ?.let { supported -> if (supported) 1 else 0 }
        }

        runCatching {
            hookBefore(
                findMethod(
                    "com.miui.headset.runtime.AncBatteryController",
                    "setAncStateBlock",
                    BluetoothDevice::class.java,
                    Int::class.java,
                ),
            ) {
                if (ModuleRuntimeGate.paused) return@hookBefore
                val device = args[0] as? BluetoothDevice
                val mode = args[1] as? Int ?: return@hookBefore
                val requestedMode = when (mode) {
                    1 -> NoiseMode.ANC
                    2 -> NoiseMode.TRANSPARENCY
                    else -> NoiseMode.OFF
                }
                val adapter = adapterFor(device)
                    ?: return@hookBefore
                if (
                    !adapter.capabilities.noiseControl ||
                    requestedMode !in adapter.supportedNoiseModes
                ) {
                    result = HEADSET_OPERATION_UNSUPPORTED
                    return@hookBefore
                }
                rememberRuntimeOwner(
                    "com.miui.headset.runtime.AncBatteryController",
                    instance,
                )
                sendControl(
                    StandardControlRequest.SetNoiseMode(requestedMode),
                    device,
                )
                result = HEADSET_OPERATION_SUCCESS
            }
        }.onFailure {
            ModuleLog.debug("MiLink", "optional setAncStateBlock unavailable")
        }

        hookHeadsetInfo("getPowers") { info ->
            activeStateForHeadsetInfo(info)?.let { state ->
                batteryLevelsFor(state) ?: UNKNOWN_COMPONENT_BATTERY_LEVELS
            }
        }
        hookHeadsetInfo("component4") { info ->
            activeStateForHeadsetInfo(info)?.let { state ->
                batteryLevelsFor(state) ?: UNKNOWN_COMPONENT_BATTERY_LEVELS
            }
        }
        hookHeadsetInfo("getMode") { info ->
            val state = activeStateForHeadsetInfo(info) ?: return@hookHeadsetInfo null
            adapterIdentity(state)?.let { adapter -> nativeAncStateForSurface(state, adapter) }
        }
        hookHeadsetInfo("component5") { info ->
            val state = activeStateForHeadsetInfo(info) ?: return@hookHeadsetInfo null
            adapterIdentity(state)?.let { adapter -> nativeAncStateForSurface(state, adapter) }
        }
        hookHeadsetInfo("getSwitchState") { info ->
            adapterForHeadsetInfo(info)
                ?.let(::supportsNativeAncSurface)
                ?.let { supported -> if (supported) 1 else 0 }
        }
        hookHeadsetInfo("component8") { info ->
            adapterForHeadsetInfo(info)
                ?.let(::supportsNativeAncSurface)
                ?.let { supported -> if (supported) 1 else 0 }
        }
    }

    /**
     * Xiaomi defines getHeadsetPropertyBlock() as an operation result, not a battery getter.
     *
     * A successful native implementation refreshes its model, publishes property update type 4,
     * and returns 100. The active adapter already owns the current property snapshot, so it
     * completes the same lifecycle without entering Xiaomi's unsupported private-protocol path.
     */
    private fun hookHeadsetPropertyRefresh() {
        val className = "com.miui.headset.runtime.AncBatteryController"
        runCatching {
            hookBefore(
                findMethod(
                    className,
                    "getHeadsetPropertyBlock",
                    BluetoothDevice::class.java,
                ),
            ) {
                if (ModuleRuntimeGate.paused) return@hookBefore
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (adapterFor(device) == null) return@hookBefore
                rememberRuntimeOwner(className, instance)
                captureContext(instance)
                recordBridgeStage(device, BridgeStage.CAPABILITIES_QUERIED)

                val listenerCount = notifyHeadsetPropertyChanged(
                    device = device,
                    updateTypes = setOf(HEADSET_PROPERTY_CHANGED),
                    additionalOwner = instance,
                )
                result = HEADSET_OPERATION_SUCCESS
                ModuleLog.debug(
                    "MiLink",
                    "completed property refresh for " +
                        "${maskBluetoothAddress(runCatching { device.address }.getOrNull())} " +
                        "listeners=$listenerCount result=$HEADSET_OPERATION_SUCCESS",
                )
            }
        }.onFailure {
            ModuleLog.warn(
                "MiLink",
                "required $className.getHeadsetPropertyBlock unavailable",
                it,
            )
        }
    }

    private fun hookBluetoothDeviceResult(
        className: String,
        methodName: String,
        value: (BluetoothDevice, AdapterSnapshot) -> Any?,
    ) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                if (ModuleRuntimeGate.paused) return@hookAfter
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                val adapter = adapterFor(device) ?: return@hookAfter
                recordBridgeStage(device, methodName.bridgeStage())
                rememberRuntimeOwner(className, instance)
                captureContext(instance)
                value(device, adapter)?.let {
                    result = it
                }
            }
        }.onFailure {
            ModuleLog.debug("MiLink", "optional $className.$methodName unavailable")
        }
    }

    /**
     * Extends MiLink's own headset-admission decision without replacing it.
     *
     * The original method has already run at this hook point. A positive platform result is
     * authoritative: HyperEars records system ownership, leaves the result untouched, and asks
     * the Bluetooth process to close any speculative module session for the same address. Only a
     * native rejection plus an active HyperEars candidate is changed to an accepted result.
     */
    @SuppressLint("MissingPermission")
    private fun hookNativeHeadsetAdmission(className: String): Boolean =
        runCatching {
            hookAfter(findMethod(className, "checkIsMiTWS", BluetoothDevice::class.java)) {
                if (ModuleRuntimeGate.paused) return@hookAfter
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                val address = runCatching { device.address }.getOrNull() ?: return@hookAfter
                captureContext(instance)

                val candidateState = rawStateForAddress(address)
                val decision = deviceOwnership.observeNativeAdmission(
                    address = address,
                    originalResult = result,
                    hyperEarsCandidateAvailable =
                        candidateState.sessionActive && candidateState.adapter != null,
                )
                ModuleLog.debug(
                    "MiLink",
                    "native admission original=$result candidate=${candidateState.sessionActive} " +
                        "owner=${decision.owner} boundary=$className " +
                        "process=${Application.getProcessName()} " +
                        "address=${maskBluetoothAddress(address)}",
                )
                when (decision.owner) {
                    MiLinkDeviceOwnershipRegistry.Owner.SYSTEM -> {
                        knownAddresses.remove(normalizeAddress(address))
                        headsetDetailExtension?.unbind(address)
                        if (decision.systemOwnershipNewlyClaimed) {
                            publishSystemOwnershipClaim(address)
                            ModuleLog.debug(
                                "MiLink",
                                "preserved native headset ownership " +
                                    "address=${maskBluetoothAddress(address)}",
                            )
                        }
                    }

                    MiLinkDeviceOwnershipRegistry.Owner.HYPEREARS -> {
                        knownAddresses += normalizeAddress(address)
                        recordBridgeStage(device, BridgeStage.IDENTITY_QUERIED)
                        rememberRuntimeOwner(className, instance)
                        result = NATIVE_HEADSET_SUPPORTED
                    }

                    MiLinkDeviceOwnershipRegistry.Owner.UNKNOWN -> Unit
                }
            }
            true
        }.getOrElse {
            ModuleLog.debug("MiLink", "optional $className.checkIsMiTWS unavailable")
            false
        }

    private fun hookAddressResult(
        className: String,
        methodName: String,
        value: (String) -> Any?,
    ) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                if (ModuleRuntimeGate.paused) return@hookAfter
                val address = args[0] as? String ?: return@hookAfter
                if (!isTargetAddress(address)) return@hookAfter
                recordBridgeStage(address, methodName.bridgeStage())
                value(address)?.let { result = it }
            }
        }.onFailure {
            ModuleLog.debug("MiLink", "optional $className.$methodName unavailable")
        }
    }

    private fun hookNoiseCommand(
        className: String,
        methodName: String,
        mode: NoiseMode,
    ) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                if (ModuleRuntimeGate.paused) return@hookBefore
                val device = args[0] as? BluetoothDevice
                val adapter = adapterFor(device)
                    ?: return@hookBefore
                if (
                    !adapter.capabilities.noiseControl ||
                    mode !in adapter.supportedNoiseModes
                ) {
                    result = HEADSET_OPERATION_UNSUPPORTED
                    return@hookBefore
                }
                rememberRuntimeOwner(className, instance)
                captureContext(instance)
                sendControl(StandardControlRequest.SetNoiseMode(mode), device)
                result = HEADSET_OPERATION_SUCCESS
            }
        }.onFailure {
            ModuleLog.debug("MiLink", "optional $className.$methodName command unavailable")
        }
    }

    /**
     * Publishes the model's raw ANC capability through MiLink's stable headset query boundary.
     *
     * The outer service controller is obfuscated between MiLink releases (`b0.L` on 17.2.0 and
     * `HeadsetServiceController.getSupportAncMode` on 17.2.4). Both versions delegate to these
     * stable query methods and then normalize raw values 3/7 to UI values 1/2 themselves. Hooking
     * this boundary keeps Xiaomi's conversion, async execution and card lifecycle intact.
     */
    private fun hookSupportedAncModes() {
        val queryClasses = listOf(
            "com.miui.headset.runtime.QueryLocal",
            "com.miui.headset.runtime.QueryServer",
        )
        var installed = 0
        queryClasses.forEach { className ->
            runCatching {
                hookBefore(
                    findMethod(
                        className,
                        "getSupportAncMode",
                        String::class.java,
                        String::class.java,
                    ),
                ) {
                    if (ModuleRuntimeGate.paused) return@hookBefore
                    val address = args.firstOrNull() as? String ?: return@hookBefore
                    val adapter = adapterForAddress(address) ?: return@hookBefore
                    result = when {
                        nativeCardAdapter(adapter)?.nativeSurface ==
                            MiLinkNativeCardSurface.ANC_THREE_STATE ->
                            MILINK_RAW_ANC_ALL_MODES

                        !adapter.capabilities.noiseControl -> NO_ANC_CAPABILITY
                        NoiseMode.TRANSPARENCY in adapter.supportedNoiseModes ->
                            MILINK_RAW_ANC_ALL_MODES

                        else -> MILINK_RAW_ANC_NO_TRANSPARENCY
                    }
                    ModuleLog.debug(
                        "MiLink",
                        "published raw ANC capabilities modes=$result " +
                            "address=${maskBluetoothAddress(address)}",
                    )
                }
                installed += 1
            }.onFailure {
                ModuleLog.debug("MiLink", "optional $className ANC query unavailable")
            }
        }
        if (installed == 0) {
            ModuleLog.debug("MiLink", "optional ANC-mode capability hook unavailable")
        }
    }

    private fun hookHeadsetInfo(
        methodName: String,
        value: (Any) -> Any?,
    ) {
        val method: Method = runCatching {
            findMethodByParamCount(
                "com.miui.headset.api.HeadsetInfo",
                methodName,
                0,
            )
        }.getOrElse {
            ModuleLog.debug("MiLink", "optional HeadsetInfo.$methodName unavailable")
            return
        }
        hookAfter(method) {
            if (ModuleRuntimeGate.paused) return@hookAfter
            val info = instance ?: return@hookAfter
            if (!isTargetHeadsetInfo(info)) return@hookAfter
            headsetAddress(info)?.let {
                recordBridgeStage(it, methodName.bridgeStage())
            }
            value(info)?.let { result = it }
        }
    }

    private fun registerStateReceiver(candidate: Context?) {
        if (candidate == null || receiverRegistered) return
        context = candidate.applicationContext ?: candidate
        ModuleLog.debug("MiLink", "state receiver context attached")
        context?.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ModuleContract.ACTION_STATE_CHANGED ->
                            handleStateChanged(intent)

                        ModuleContract.ACTION_REQUEST_BRIDGE_STATUS -> {
                            val targetPackage = with(ModuleContract) {
                                intent.readReplyPackage()
                            }?.takeIf { it == ModuleContract.MODULE_PACKAGE } ?: return
                            publishCurrentBridgeStatus(targetPackage)
                        }

                        ModuleContract.ACTION_SYSTEM_OWNERSHIP_CLAIMED ->
                            handleSystemOwnershipClaim(
                                context = context,
                                intent = intent,
                                senderPackage = sentFromPackage,
                                senderUid = sentFromUid,
                            )
                    }
                }
            },
            IntentFilter().apply {
                addAction(ModuleContract.ACTION_STATE_CHANGED)
                addAction(ModuleContract.ACTION_REQUEST_BRIDGE_STATUS)
                addAction(ModuleContract.ACTION_SYSTEM_OWNERSHIP_CLAIMED)
            },
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        flushPendingSystemOwnershipClaims()
        requestState()
        ModuleLog.debug("MiLink", "state receiver registered")
    }

    private fun clearModuleState() {
        knownAddresses.clear()
        deviceOwnership.clear()
        pendingSystemOwnershipClaims.clear()
        targetHeadsetAddresses.clear()
        synchronized(observationLock) {
            sessionStages.clear()
            pendingStages.clear()
        }
        ProcessStateStore.clear()
    }

    private fun handleStateChanged(intent: Intent) {
        if (ModuleRuntimeGate.paused) return
        val incoming = with(ModuleContract) { intent.readState() } ?: return
        val sessionToken = with(ModuleContract) { intent.readSessionToken() } ?: return
        val previous = incoming.address
            ?.let(ProcessStateStore::knownSnapshot)
            ?: EarbudState()
        val state = ProcessStateStore.accept(intent) ?: return
        state.address?.let {
            val normalized = normalizeAddress(it)
            val systemOwned = deviceOwnership.isSystemOwned(it)
            if (state.sessionActive && !systemOwned) {
                knownAddresses += normalized
                observeStateAccepted(state, sessionToken)
            } else {
                if (systemOwned) {
                    knownAddresses.remove(normalized)
                }
                synchronized(observationLock) {
                    sessionStages.remove(normalized)
                    pendingStages.remove(normalized)
                }
            }
        }
        if (state.address?.let(deviceOwnership::isSystemOwned) != true) {
            notifyRuntimeChanged(previous, state)
            // Let the model-specific card adapter render after MiLink has consumed the stock
            // three-state callback; otherwise the host can overwrite an extended mode such as WIND.
            headsetDetailExtension?.onStateChanged(state)
        }
    }

    private fun handleSystemOwnershipClaim(
        context: Context?,
        intent: Intent,
        senderPackage: String?,
        senderUid: Int,
    ) {
        if (context == null ||
            !isAuthenticatedMiLinkSender(context, senderPackage, senderUid)
        ) return
        val address = with(ModuleContract) {
            intent.readSystemOwnershipClaimAddress()
        } ?: return
        if (!deviceOwnership.claimSystemOwnership(address)) return
        val normalized = normalizeAddress(address)
        knownAddresses.remove(normalized)
        synchronized(observationLock) {
            sessionStages.remove(normalized)
            pendingStages.remove(normalized)
        }
        headsetDetailExtension?.unbind(address)
        ModuleLog.debug(
            "MiLink",
            "received shared system ownership address=${maskBluetoothAddress(address)} " +
                "process=${Application.getProcessName()}",
        )
    }

    private fun isAuthenticatedMiLinkSender(
        context: Context,
        senderPackage: String?,
        senderUid: Int,
    ): Boolean {
        if (senderPackage.isNullOrBlank() && senderUid < 0) return false
        if (!senderPackage.isNullOrBlank() && senderPackage != ModuleContract.MILINK_PACKAGE) {
            return false
        }
        if (senderUid >= 0) {
            val packages = context.packageManager.getPackagesForUid(senderUid).orEmpty()
            if (ModuleContract.MILINK_PACKAGE !in packages) return false
        }
        return true
    }

    private fun publishCurrentBridgeStatus(targetPackage: String) {
        context?.sendBroadcast(
            ModuleContract.bridgeRuntimeObserved(
                consumerProcess = Application.getProcessName(),
                targetPackage = targetPackage,
            ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
        ProcessStateStore.snapshots().forEach { state ->
            val address = state.address ?: return@forEach
            val token = ProcessStateStore.sessionToken(address) ?: return@forEach
            val stages = synchronized(observationLock) {
                sessionStages[normalizeAddress(address)]
                    ?.takeIf { it.sessionToken == token }
                    ?.stages
                    ?.toSet()
                    .orEmpty()
            } + BridgeStage.STATE_ACCEPTED
            stages.forEach { stage ->
                publishBridgeReceipt(state, token, targetPackage, stage)
            }
        }
    }

    private fun publishBridgeReceipt(
        state: EarbudState,
        sessionToken: String,
        targetPackage: String,
        stage: BridgeStage,
    ) {
        context?.sendBroadcast(
            ModuleContract.bridgeStateObserved(
                state = state,
                sessionToken = sessionToken,
                consumerProcess = Application.getProcessName(),
                targetPackage = targetPackage,
                stage = stage,
            ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    private fun observeStateAccepted(
        state: EarbudState,
        sessionToken: String,
    ) {
        val address = state.address ?: return
        val key = normalizeAddress(address)
        val pending = synchronized(observationLock) {
            val tracked = sessionStages[key]
                ?.takeIf { it.sessionToken == sessionToken }
                ?: SessionStages(sessionToken).also { sessionStages[key] = it }
            tracked.stages += BridgeStage.STATE_ACCEPTED
            pendingStages.remove(key)?.toSet().orEmpty()
        }
        publishBridgeReceipt(
            state,
            sessionToken,
            ModuleContract.MODULE_PACKAGE,
            BridgeStage.STATE_ACCEPTED,
        )
        pending.forEach { stage ->
            observeBridgeStage(state, sessionToken, stage)
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordBridgeStage(
        device: BluetoothDevice,
        stage: BridgeStage,
    ) {
        val address = runCatching { device.address }.getOrNull() ?: return
        recordBridgeStage(address, stage)
    }

    private fun recordBridgeStage(
        address: String,
        stage: BridgeStage,
    ) {
        val state = ProcessStateStore.find(address)
        val sessionToken = ProcessStateStore.sessionToken(address)
        if (state == null || sessionToken == null) {
            synchronized(observationLock) {
                pendingStages
                    .getOrPut(normalizeAddress(address)) { mutableSetOf() }
                    .add(stage)
            }
            return
        }
        observeBridgeStage(state, sessionToken, stage)
    }

    private fun observeBridgeStage(
        state: EarbudState,
        sessionToken: String,
        stage: BridgeStage,
    ) {
        val address = state.address ?: return
        val isNew = synchronized(observationLock) {
            val key = normalizeAddress(address)
            val tracked = sessionStages[key]
                ?.takeIf { it.sessionToken == sessionToken }
                ?: SessionStages(sessionToken).also { sessionStages[key] = it }
            tracked.stages.add(stage)
        }
        if (!isNew) return
        publishBridgeReceipt(
            state,
            sessionToken,
            ModuleContract.MODULE_PACKAGE,
            stage,
        )
    }

    @SuppressLint("MissingPermission")
    private fun adapterFor(device: BluetoothDevice?) =
        device?.let { target ->
            val address = runCatching { target.address }.getOrNull()
            val adapter = address?.let(::adapterForAddress)
            if (adapter != null) {
                knownAddresses += normalizeAddress(address)
            }
            adapter
        }

    private fun adapterForAddress(address: String) =
        if (!deviceOwnership.isSystemOwned(address)) {
            rawStateForAddress(address).adapter
        } else {
            null
        }

    private fun adapterIdentity(state: EarbudState) =
        state.adapter

    @SuppressLint("MissingPermission")
    private fun stateFor(device: BluetoothDevice?): EarbudState {
        val address = runCatching { device?.address }.getOrNull()
        return address?.let(::stateForAddress) ?: EarbudState()
    }

    private fun isTargetAddress(address: String): Boolean {
        val normalized = normalizeAddress(address)
        return !deviceOwnership.isSystemOwned(address) &&
            (normalized in knownAddresses || ProcessStateStore.containsKnown(normalized))
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        return adapterForHeadsetInfo(info) != null
    }

    private fun adapterForHeadsetInfo(info: Any?): AdapterSnapshot? {
        val state = activeStateForHeadsetInfo(info) ?: return null
        return state.adapter
    }

    private fun isBluetoothAddress(value: String): Boolean =
        BLUETOOTH_ADDRESS_PATTERN.matches(value)

    /**
     * Reads only the stable Bluetooth-address portion of a headset transport object.
     *
     * The carrier VID/PID is deliberately not decoded here: it represents only TWS vs headphones,
     * never a concrete HyperEars model.
     */
    private fun rawHeadsetAddress(info: Any?): String? {
        if (info == null) return null
        val methodCandidates = listOf(
            "getAddress",
            "getMac",
            "component1",
        ).mapNotNull { methodName ->
            runCatching { callMethod(info, methodName) as? String }.getOrNull()
        }
        val fieldCandidates = listOf("address", "mac", "deviceId").mapNotNull { fieldName ->
            runCatching { getObjectField(info, fieldName) as? String }.getOrNull()
        }
        return (methodCandidates + fieldCandidates).firstOrNull(::isBluetoothAddress)
    }

    private fun headsetAddress(info: Any?): String? {
        if (info == null) return null
        targetHeadsetAddresses[info]?.let { return it }
        val address = rawHeadsetAddress(info)?.takeIf(::isTargetAddress)
        if (address != null) targetHeadsetAddresses[info] = address
        return address
    }

    private fun stateForHeadsetInfo(info: Any?): EarbudState {
        val address = headsetAddress(info) ?: return EarbudState()
        return stateForAddress(address)
    }

    private fun activeStateForHeadsetInfo(info: Any?): EarbudState? =
        stateForHeadsetInfo(info).takeIf(EarbudState::sessionActive)

    private fun stateForAddress(address: String): EarbudState {
        return if (!deviceOwnership.isSystemOwned(address)) {
            rawStateForAddress(address)
        } else {
            EarbudState()
        }
    }

    private fun rawStateForAddress(address: String): EarbudState =
        ProcessStateStore.knownSnapshot(address)

    private fun rememberRuntimeOwner(className: String, owner: Any?) {
        owner?.let { runtimeOwners += it }
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" ->
                lastAncBatteryController = owner

            "com.miui.headset.runtime.ProfileContext" ->
                lastProfileContext = owner
        }
    }

    private fun captureContext(owner: Any?) {
        if (receiverRegistered) return
        val candidate = listOf(owner, lastProfileContext, lastAncBatteryController)
            .firstNotNullOfOrNull {
                runCatching { getObjectField(it, "context") as? Context }.getOrNull()
            }
        registerStateReceiver(candidate)
    }

    @SuppressLint("MissingPermission")
    private fun notifyRuntimeChanged(previous: EarbudState, snapshot: EarbudState) {
        val owners = synchronized(runtimeOwners) { runtimeOwners.toList() }
        val propertyListeners = propertyChangeListeners()
        if (owners.isEmpty() && propertyListeners.isEmpty()) return

        val adapter = adapterIdentity(snapshot) ?: return
        val capabilities = adapter.capabilities
        val previousAdapter = adapterIdentity(previous)
        val identityChanged =
            previous.modelId != snapshot.modelId ||
                previous.address != snapshot.address
        val adapterChanged = previous.adapter != snapshot.adapter
        val connectionChanged = identityChanged || previous.connected != snapshot.connected
        val batteryChanged =
            capabilities.battery &&
                (identityChanged || adapterChanged || previous.battery != snapshot.battery)
        val previousAncSurface = previousAdapter?.let(::supportsNativeAncSurface) == true
        val currentAncSurface = supportsNativeAncSurface(adapter)
        val previousAncState = previousAdapter
            ?.takeIf { previousAncSurface }
            ?.let { nativeAncStateForSurface(previous, it) }
            ?: NO_ANC_STATE
        val currentAncState = if (currentAncSurface) {
            nativeAncStateForSurface(snapshot, adapter)
        } else {
            NO_ANC_STATE
        }
        val ancChanged =
            (previousAncSurface || currentAncSurface) &&
                (
                    identityChanged ||
                        adapterChanged ||
                        previousAncSurface != currentAncSurface ||
                        previousAncState != currentAncState
                )
        if (!adapterChanged && !connectionChanged && !batteryChanged && !ancChanged) return

        val address = snapshot.address ?: return
        val device = runCatching {
            context
                ?.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
        }.getOrNull() ?: return

        val updateTypes = buildSet {
            if (adapterChanged || identityChanged || batteryChanged || connectionChanged) {
                add(HEADSET_PROPERTY_CHANGED)
            }
            if (identityChanged || ancChanged) add(8)
        }
        notifyHeadsetPropertyChanged(
            device = device,
            updateTypes = updateTypes,
            listeners = propertyListeners,
        )

        val battery = batteryLevelsFor(snapshot)?.toIntArray() ?: return
        val anc = currentAncState
        val deviceId = adapterIdentity(snapshot)
            ?.let(MiLinkCarrierIdentity::deviceId)
            ?: return
        owners.forEach { owner ->
            val callbackCollections = listOf("mCallbacks", "callbacks")
                .mapNotNull { field ->
                    runCatching { getObjectField(owner, field) as? Collection<*> }
                        .getOrNull()
                }
            callbackCollections
                .flatMap { it.filterNotNull() }
                .distinctBy(System::identityHashCode)
                .forEach { callback ->
                    if (identityChanged) {
                        runCatching {
                            callMethod(callback, "onDeviceIdUpdate", device, deviceId)
                        }
                    }
                    if (batteryChanged) {
                        runCatching { callMethod(callback, "onBatteryLevel", device, battery) }
                    }
                    if (ancChanged) {
                        runCatching { callMethod(callback, "onAncStateChanged", device, anc) }
                        runCatching { callMethod(callback, "onReportAncState", device, anc) }
                    }
                    if (connectionChanged) runCatching {
                        callMethod(
                            callback,
                            "onConnectMmaStateChanged",
                            device,
                            snapshot.connected,
                        )
                    }
                }
        }
        if (snapshot.sessionActive) {
            recordBridgeStage(address, BridgeStage.RUNTIME_NOTIFIED)
        }
    }

    private fun notifyHeadsetPropertyChanged(
        device: BluetoothDevice,
        updateTypes: Set<Int>,
        additionalOwner: Any? = null,
        listeners: List<Any> = propertyChangeListeners(additionalOwner),
    ): Int {
        listeners.forEach { listener ->
            updateTypes.forEach { updateType ->
                runCatching { callMethod(listener, "invoke", device, updateType) }
                    .onFailure {
                        ModuleLog.warn(
                            "MiLink",
                            "headset property callback failed type=$updateType",
                            it,
                        )
                    }
            }
        }
        return listeners.size
    }

    /** MiLink exposes the same downstream listener through multiple runtime facades. */
    private fun propertyChangeListeners(additionalOwner: Any? = null): List<Any> =
        listOf(additionalOwner, lastAncBatteryController, lastProfileContext)
            .filterNotNull()
            .firstNotNullOfOrNull { owner ->
                runCatching {
                    getObjectField(owner, "headsetPropertyChangeListener")
                }.getOrNull()
            }
            ?.let(::listOf)
            .orEmpty()

    private fun batteryLevelsFor(state: EarbudState): List<Int>? {
        val adapter = adapterIdentity(state)?.takeIf { it.capabilities.battery } ?: return null
        return MiLinkStateCodec.batteryLevels(
            state = state,
            formFactor = adapter.formFactor,
        )
    }

    private fun nativeAncState(state: EarbudState): Int {
        val presentation = adapterIdentity(state)
            ?.presentationId
            ?.let(MiLinkCardAdapterRegistry::resolve)
        val projectedMode = presentation?.projectNativeNoiseMode(state.noiseMode)
            ?: state.noiseMode
        return MiLinkStateCodec.ancState(
            projectedMode?.let { state.withFeature(NoiseModeFeatureState(it)) } ?: state,
        )
    }

    private fun nativeAncStateForSurface(
        state: EarbudState,
        adapter: AdapterSnapshot,
    ): Int {
        if (adapter.capabilities.noiseControl) return nativeAncState(state)
        val mode = nativeCardAdapter(adapter)
            ?.takeIf { it.nativeSurface == MiLinkNativeCardSurface.ANC_THREE_STATE }
            ?.nativeSurfaceNoiseMode(state)
            ?: return NO_ANC_STATE
        return MiLinkStateCodec.ancState(state.withFeature(NoiseModeFeatureState(mode)))
    }

    private fun supportsNativeAncSurface(adapter: AdapterSnapshot): Boolean =
        adapter.capabilities.noiseControl ||
            nativeCardAdapter(adapter)?.nativeSurface == MiLinkNativeCardSurface.ANC_THREE_STATE

    private fun nativeCardAdapter(adapter: AdapterSnapshot): MiLinkCardAdapter? =
        adapter.presentationId?.let(MiLinkCardAdapterRegistry::resolve)

    private fun requestState() {
        context?.sendBroadcast(
            ModuleContract.requestState(packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    private fun publishSystemOwnershipClaim(address: String) {
        val targetContext = context
        if (targetContext == null) {
            pendingSystemOwnershipClaims += normalizeAddress(address)
            return
        }
        var deliveryFailed = false
        listOf(ModuleContract.MILINK_PACKAGE, ModuleContract.BLUETOOTH_PACKAGE)
            .forEach { targetPackage ->
                runCatching {
                    targetContext.sendBroadcast(
                        ModuleContract.systemOwnershipClaimed(address, targetPackage)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        null,
                        ModuleContract.systemOwnershipClaimOptions(),
                    )
                }.onFailure {
                    deliveryFailed = true
                    ModuleLog.warn(
                        "MiLink",
                        "system-ownership broadcast failed target=$targetPackage",
                        it,
                    )
                }
            }
        if (deliveryFailed) {
            pendingSystemOwnershipClaims += normalizeAddress(address)
        }
    }

    private fun flushPendingSystemOwnershipClaims() {
        val pending = synchronized(pendingSystemOwnershipClaims) {
            pendingSystemOwnershipClaims.toList().also {
                pendingSystemOwnershipClaims.clear()
            }
        }
        pending.forEach(::publishSystemOwnershipClaim)
    }

    @SuppressLint("MissingPermission")
    private fun sendControl(request: ControlRequest, device: BluetoothDevice?) {
        val address = runCatching { device?.address }.getOrNull() ?: return
        sendControl(request, address)
    }

    private fun sendControl(request: ControlRequest, address: String) {
        if (deviceOwnership.isSystemOwned(address)) return
        val snapshot = ProcessStateStore.find(address) ?: return
        if (!snapshot.sessionActive) return
        val token = ProcessStateStore.sessionToken(address) ?: return
        val targetContext = context ?: return
        val send = {
            targetContext.sendBroadcast(
                ModuleContract.control(request, address, token)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            )
        }
        send()
        ModuleLog.debug("MiLink", "forwarded ${request.javaClass.simpleName}")
    }

    private fun normalizeAddress(address: String): String = address.uppercase(Locale.ROOT)

    private fun String.bridgeStage(): BridgeStage = when (this) {
        "checkIsMiTWS",
        "getDeviceId",
        "isMiTWS",
        -> BridgeStage.IDENTITY_QUERIED

        else -> BridgeStage.CAPABILITIES_QUERIED
    }

    private companion object {
        const val ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val HYPEREARS_MAIN_ACTIVITY = "dev.hyperears.MainActivity"
        const val MILINK_RAW_ANC_NO_TRANSPARENCY = 3
        const val MILINK_RAW_ANC_ALL_MODES = 7
        const val NO_ANC_CAPABILITY = 0
        const val NO_ANC_STATE = MiLinkStateCodec.ANC_STATE_UNAVAILABLE
        const val NATIVE_HEADSET_SUPPORTED = 1
        const val UNKNOWN_BATTERY_LEVEL = -1
        const val HEADSET_OPERATION_UNSUPPORTED = 0
        val UNKNOWN_COMPONENT_BATTERY_LEVELS = listOf(-1, -1, -1, 0, 0, 0)
        const val HEADSET_OPERATION_SUCCESS = 100
        const val HEADSET_PROPERTY_CHANGED = 4
        val BLUETOOTH_ADDRESS_PATTERN =
            Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
