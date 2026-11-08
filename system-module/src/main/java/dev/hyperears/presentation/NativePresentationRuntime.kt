package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.DeviceStateRegistry
import dev.hyperears.bridge.HeadsetSettingsNavigator
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.settings.HeadsetConnectionPresentation
import dev.hyperears.settings.ModuleSettingsRuntime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** One serialized consumer of accepted Adapter state in each native headset host process. */
internal class NativePresentationRuntime(
    private val context: Context,
    private val uiProcess: Boolean,
    private val configure: (HookContext, ClassLoader) -> Unit,
) {
    private data class Record(val state: EarbudState, val session: String,
        val metadata: NativePresentationMetadata, val producer: IBinder) {
        val active: Boolean get() = state.sessionActive && metadata.admission != NativeAdmission.SYSTEM
    }
    private val handler = Handler(Looper.getMainLooper())
    private val registry = DeviceStateRegistry()
    // Keep the latest lifecycle (including its closing/system-owned record) to invalidate delayed
    // Activity tickets. Only active records may render or resolve ownership.
    // Main-thread writes publish immutable records; the legacy native renderer revalidates them
    // on its own Handler immediately before displaying an already accepted connection.
    private val records = ConcurrentHashMap<String, Record>()
    private val gate = ConnectionPresentationGate()
    private val gateDecisions = mutableMapOf<String, Pair<String, String>>()
    private var ownershipStore: NativeOwnershipStore? = null
    private val hostAvailability = NativeHostAvailability()
    private val ownershipReadings = NativeAdmissionCache()
    private var flow: NativePresentationFlow? = null
    private var producer: IBinder? = null
    private var producerDeath: IBinder.DeathRecipient? = null
    private val retiredProducers = ArrayDeque<IBinder>()
    private var windowBridge: NativeWindowBridge? = null
    private var windowKind = NativeWindowKind.AIRPODS
    private var legacyVerified = false
    private var windowAttempted = false
    private var popupCompatible = false
    private var scene: NativePresentationScene? = null
    private var hint: NativeConnectionPresenter? = null
    private var details: NativeDetailNotification? = null
    private var detailResolutionStarted = false
    private var settings = ModuleSettingsRuntime.current
    private var activeWindow: String? = null
    private var windowLifetime: IBinder? = null
    private var windowDeath: IBinder.DeathRecipient? = null
    private var windowDeadline: Runnable? = null

    init {
        if (!uiProcess) {
            prepareHost()
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        ModuleContract.ACTION_STATE_CHANGED -> {
                            if (BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                    ModuleContract.BLUETOOTH_PACKAGE)) accept(intent)
                        }
                        NativePresentationContract.ACTION_PREVIEW -> if (!uiProcess &&
                            BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.MODULE_PACKAGE)) preview(intent)
                        NativePresentationContract.ACTION_WINDOW -> if (!uiProcess &&
                            BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                ModuleContract.NATIVE_HEADSET_PACKAGE)) windowChanged(intent)
                        Intent.ACTION_SCREEN_OFF -> windowBridge?.closeAll("screen off")
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            if (device?.address?.let(records::get)?.active == true) {
                                windowBridge?.closeAll("Bluetooth disconnected")
                            }
                        }
                    }
                }.onFailure { ModuleLog.warn(TAG, "native presentation event failed", it) }
            }
        }, IntentFilter().apply {
            addAction(ModuleContract.ACTION_STATE_CHANGED)
            addAction(NativePresentationContract.ACTION_PREVIEW)
            addAction(NativePresentationContract.ACTION_WINDOW)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)
        ModuleSettingsRuntime.observe { updated -> handler.post {
            val old = settings
            settings = updated
            if (!uiProcess && updated.diagnosticLogging && !old.diagnosticLogging) reportHostAvailability("logging-enabled")
            if (old != updated) {
                ModuleLog.debug(TAG) {
                    "settings applied mode=${updated.headsetConnectionPresentation} " +
                        "detailNotification=${updated.headsetDetailNotification} paused=${updated.modulePaused}"
                }
            }
            if (updated.modulePaused || old.headsetConnectionPresentation != updated.headsetConnectionPresentation) {
                windowBridge?.closeAll("settings changed")
            }
            if (updated.modulePaused || !updated.headsetDetailNotification) details?.clear()
            prepareDetails()
            records.values.toList().forEach { render(it, allowTransient = false) }
        } }
        BroadcastIdentity.send(context, ModuleContract.requestState(ModuleContract.NATIVE_HEADSET_PACKAGE))
        ModuleLog.debug(TAG, "receiver ready ui=$uiProcess")
    }

    /** One bounded APK scan off the main thread. Each native capability can fail independently. */
    private fun prepareHost() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "HyperEars-native-host") }
        executor.execute {
            val environment = runCatching(::hostEnvironment)
                .getOrElse { "host environment unavailable: ${it.javaClass.simpleName}" }
            val result = runCatching { NativeHostResolver.resolve(context) { key ->
                handler.post { nativeOwnershipChanged(key) }
            } }
            handler.post {
                result.onSuccess { symbols ->
                    symbols.ownership.onSuccess { ownershipStore = it }
                    symbols.flow.onSuccess { flow = it }
                    legacyVerified = symbols.legacyVerified
                    val hintResult = symbols.connectionHint.mapCatching { source ->
                        val bridge = when (source) {
                            is NativeHintSource.AirPods -> NativeConnectionHint(source.stateReader)
                            is NativeHintSource.LegacyXiaomi -> LegacyNativeConnectionHint(context, source.notificationType, source.inlineBoundaries)
                        }
                        configure(bridge, context.classLoader)
                        bridge.install()
                        hint = bridge as NativeConnectionPresenter
                    }
                    hostAvailability.complete(environment + " legacyFingerprint=${symbols.legacyFingerprint}", linkedMapOf(
                        "ownership" to symbols.ownership.map { Unit },
                        "circulation" to symbols.flow.map { Unit },
                        "connection-hint" to hintResult.map { Unit },
                    ))
                    reportHostAvailability("initialization")
                    ownershipReadings.clear()
                    ModuleLog.debug(TAG) {
                        "host ready ownership=${ownershipStore != null} flow=${flow != null} hint=${hint != null} " +
                            "legacyVerified=$legacyVerified"
                    }
                    records.values.toList().forEach { render(it, allowTransient = true) }
                }.onFailure { error ->
                    hostAvailability.complete(environment, mapOf("host-resolution" to Result.failure(error)))
                    reportHostAvailability("initialization")
                }
            }
            executor.shutdown()
        }
    }

    @Suppress("DEPRECATION")
    private fun hostEnvironment(): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
            .getOrNull()?.let { "${it.versionName}/${it.longVersionCode}" } ?: "unavailable"
        val unlocked = runCatching { context.getSystemService(UserManager::class.java).isUserUnlocked }.getOrNull()
        return "package=${context.packageName} version=$version sdk=${Build.VERSION.SDK_INT} " +
            "unlockedAtInitialization=$unlocked deviceProtected=${context.isDeviceProtectedStorage} " +
            "startedAt=${SystemClock.elapsedRealtime()} build=${Build.FINGERPRINT} apk=${context.applicationInfo.sourceDir}"
    }

    private fun reportHostAvailability(reason: String) {
        hostAvailability.report(ModuleSettingsRuntime.current.diagnosticLogging, reason) { message, error ->
            if (error == null) ModuleLog.debug(TAG, message) else ModuleLog.warn(TAG, message, error)
        }
    }

    // A feature-plugin class in the explicitly scoped Xiaomi host, not an Android framework API.
    @SuppressLint("PrivateApi")
    fun pluginReady(loader: ClassLoader) {
        if (windowAttempted) return
        val modern = runCatching { Class.forName(AirPodsWindowBridge.CONTROLLER, false, loader) }.isSuccess
        val legacy = !modern && runCatching { LegacyNativeHostContract.isCandidate(context) &&
            Class.forName(LegacyWindowSymbols.CONTROLLER, false, loader) != null }.getOrDefault(false)
        if (!modern && !legacy) return
        windowKind = if (legacy) NativeWindowKind.LEGACY_XIAOMI else NativeWindowKind.AIRPODS
        windowAttempted = true
        if (!uiProcess) {
            runCatching { NativePresentationScene.resolve(loader, legacy) }
                .onSuccess { scene = it }
                .onFailure { ModuleLog.warn(TAG, "native scene query unavailable", it) }
        }
        popupCompatible = runCatching {
            if (legacy) LegacyWindowSymbols(loader) else AirPodsWindowSymbols(loader)
        }.onFailure { ModuleLog.warn(TAG, "native popup contract unavailable kind=$windowKind", it) }.isSuccess
        ModuleLog.debug(TAG) {
            "plugin ready ui=$uiProcess popup=$popupCompatible scene=${scene != null} hint=${hint != null} kind=$windowKind"
        }
        if (!popupCompatible) {
            ModuleLog.debug(TAG, "popup contract unavailable for loaded plugin")
        }
        if (!uiProcess) {
            records.values.toList().forEach { render(it, allowTransient = true) }
            return
        }
        if (!popupCompatible) return
        runCatching {
            val forBinding: (NativeWindowRequest) -> NativeWindowRequest? = { request ->
                    val address = request.state.address
                    val record = address?.let(records::get)
                    request.forBinding(
                        latest = record?.let {
                            NativeWindowRequest(request.id, it.state, it.session, it.metadata, it.producer, request.kind)
                        },
                        currentProducer = producer,
                    )
                }
            val onOpened: (String, IBinder) -> Unit = { id, token -> sendWindowEvent(id, token) }
            val onClosed: (String) -> Unit = { id -> sendWindowEvent(id, null) }
            val bridge = if (legacy) LegacyXiaomiWindowBridge(context, forBinding, onOpened, onClosed, ::openHeadsetSettings)
                else AirPodsWindowBridge(context, forBinding, onOpened, onClosed, ::openHeadsetSettings)
            bridge.also { configure(it, loader); it.install() }
        }.onSuccess { windowBridge = it }
            .onFailure { ModuleLog.warn(TAG, "native popup unavailable", it) }
    }

    private fun sendWindowEvent(id: String, token: IBinder?) {
        BroadcastIdentity.send(context, Intent(NativePresentationContract.ACTION_WINDOW)
            .setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE)
            .putExtra(NativePresentationContract.EXTRA_WINDOW, id)
            .putExtras(Bundle().apply { putBinder("lifetime", token) }))
    }

    private fun windowChanged(intent: Intent) {
        if (intent.getStringExtra(NativePresentationContract.EXTRA_WINDOW) != activeWindow) return
        windowDeadline?.let(handler::removeCallbacks)
        windowDeadline = null
        val token = intent.extras?.getBinder("lifetime")
        if (token == null) { releaseWindow(); return }
        windowDeath?.let { previous -> windowLifetime?.unlinkToDeath(previous, 0) }
        val death = IBinder.DeathRecipient { handler.post { releaseWindow() } }
        windowLifetime = token
        windowDeath = death
        runCatching { token.linkToDeath(death, 0) }.onFailure { releaseWindow() }
    }

    private fun releaseWindow() {
        windowDeadline?.let(handler::removeCallbacks)
        windowDeath?.let { runCatching { windowLifetime?.unlinkToDeath(it, 0) } }
        windowDeadline = null
        windowDeath = null
        windowLifetime = null
        activeWindow = null
    }

    private fun accept(intent: Intent) {
        val nextProducer = NativePresentationContract.producer(intent) ?: return
        val metadata = NativePresentationContract.metadata(intent) ?: return
        val state = with(ModuleContract) { intent.readState() } ?: return
        val token = with(ModuleContract) { intent.readSessionToken() } ?: return
        if (nextProducer != producer) {
            if (!nextProducer.isBinderAlive || nextProducer in retiredProducers) return
            val death = IBinder.DeathRecipient { handler.post {
                if (producer == nextProducer) clear("producer died")
            } }
            if (runCatching { nextProducer.linkToDeath(death, 0) }.isFailure) return
            producerDeath?.let { runCatching { producer?.unlinkToDeath(it, 0) } }
            producer?.let { retiredProducers.addLast(it) }
            while (retiredProducers.size > 16) retiredProducers.removeFirst()
            clear("producer changed")
            producer = nextProducer
            producerDeath = death
        }
        val accepted = registry.accept(state, token) ?: return
        val address = accepted.address ?: return
        val record = Record(accepted, token, metadata, nextProducer)
        records[address] = record
        windowBridge?.update(accepted, token, metadata, nextProducer)
        if (!record.active) {
            gateDecisions.remove(address)
            ownershipReadings.remove(address)
            details?.remove(address)
            // Retain the consumed connection through logical session replacement.
            return
        }
        render(record, allowTransient = true)
    }

    private fun clear(reason: String) {
        windowBridge?.closeAll(reason)
        details?.clear()
        records.clear()
        registry.clear()
        gate.clear()
        gateDecisions.clear()
        ownershipReadings.clear()
        releaseWindow()
    }

    private fun nativeOwnershipChanged(key: String?) {
        records.values.filter(Record::active).forEach { record ->
            val address = record.state.address ?: return@forEach
            if (NativeOwnershipStore.affects(address, key)) {
                ownershipReadings.remove(address)
                render(record, allowTransient = false)
            }
        }
    }

    /** Read and report in the actual presentation host; MiLink is not part of this path. */
    private fun checkNativeOwnership(record: Record, refresh: Boolean = false): NativeAdmission {
        val address = record.state.address ?: return NativeAdmission.UNKNOWN
        val admission = ownershipReadings.read(address, record.session, refresh) {
            val result = ownershipStore?.read(address) ?: NativeAdmission.UNKNOWN
            ModuleLog.debug("NativeOwnership") {
                "decision=$result address=${maskBluetoothAddress(address)} session=${record.session}"
            }
            result
        }
        if (admission != NativeAdmission.UNKNOWN && admission != record.metadata.admission &&
            record.producer.isBinderAlive) {
            BroadcastIdentity.send(context, NativePresentationContract.admission(address, record.session, admission))
        }
        return admission
    }

    private fun prepareDetails() {
        if (uiProcess || !settings.headsetDetailNotification || detailResolutionStarted) return
        detailResolutionStarted = true
        val executor = Executors.newSingleThreadExecutor { Thread(it, "HyperEars-native-symbols") }
        executor.execute {
            val result = runCatching { NativeDetailNotificationResolver.resolve(
                context.applicationInfo.sourceDir, context.classLoader) }
            handler.post {
                result.onSuccess { methods ->
                    runCatching { NativeDetailNotification(context, methods, ::openNotificationSettings).also {
                        configure(it, context.classLoader); it.install()
                    } }.onSuccess {
                        details = it
                        records.values.toList().forEach { record -> render(record, false) }
                    }.onFailure { ModuleLog.warn(TAG, "detail notification unavailable", it) }
                }.onFailure { ModuleLog.warn(TAG, "detail notification fingerprint unavailable", it) }
            }
            executor.shutdown()
        }
    }

    private fun openNotificationSettings(address: String, connection: String): Boolean {
        val record = records[address] ?: return false
        if (record.metadata.connection.id != connection || record.metadata.admission != NativeAdmission.HYPEREARS ||
            !record.producer.isBinderAlive || !ModuleSettingsRuntime.current.headsetDetailNotification) return false
        return openHeadsetSettings(context, record.state)
    }

    private fun openHeadsetSettings(launchContext: Context, state: EarbudState): Boolean {
        val current = ModuleSettingsRuntime.current
        val address = state.address ?: return false
        if (current.modulePaused || !state.sessionActive) return false
        return HeadsetSettingsNavigator(launchContext).open(address, state, current.moreSettingsTarget).also { opened ->
            ModuleLog.debug(TAG, "settings address=${maskBluetoothAddress(address)} " +
                "target=${current.moreSettingsTarget} opened=$opened")
        }
    }

    @SuppressLint("MissingPermission")
    private fun render(record: Record, allowTransient: Boolean) {
        if (uiProcess || settings.modulePaused || !record.active) return
        val p = NativeHeadsetProjection.from(record.state) ?: return
        if (record.metadata.replay || record.metadata.admission != NativeAdmission.HYPEREARS) {
            attemptPresentation(record) { false }
        }
        if (checkNativeOwnership(record) != NativeAdmission.HYPEREARS) return
        if (record.metadata.admission != NativeAdmission.HYPEREARS) return
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(p.address) ?: return
        if (settings.headsetDetailNotification) {
            runCatching { details?.update(p, device, record.metadata.connection.id) }
                .onFailure { ModuleLog.warn(TAG, "detail notification update failed", it) }
        }
        if (!allowTransient || record.metadata.replay ||
            settings.headsetConnectionPresentation == HeadsetConnectionPresentation.OFF) return
        attemptPresentation(record) {
            present(record, settings.headsetConnectionPresentation) == NativePresentationResult.SUBMITTED
        }
    }

    private fun attemptPresentation(record: Record, submit: () -> Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        return gate.tryPresent(record.metadata, now, onDecision = { decision ->
            val address = record.state.address ?: return@tryPresent
            val value = record.metadata.connection.id to decision
            if (gateDecisions.put(address, value) != value) {
                ModuleLog.debug(TAG) {
                    "connection gate=$decision address=${maskBluetoothAddress(address)} " +
                        "ageMs=${now - record.metadata.connection.connectedAtElapsedMs} " +
                        "adapter=${record.state.adapter?.id} batterySource=${record.state.adapter?.batterySource}"
                }
            }
        }, submit = submit)
    }

    private fun preview(intent: Intent) {
        val mode = when (NativePresentationContract.kind(intent)) {
            "hint" -> HeadsetConnectionPresentation.SYSTEM_HINT
            "popup" -> HeadsetConnectionPresentation.BATTERY_POPUP
            else -> return
        }
        val result = runCatching {
            val record = records.values.lastOrNull { it.active }
                ?: return@runCatching NativePresentationResult.NO_DEVICE
            previewAdmissionResult(checkNativeOwnership(record, refresh = true), record.metadata.admission)
                ?: present(record, mode, manual = true)
        }.getOrElse {
            ModuleLog.warn(TAG, "preview failed", it)
            NativePresentationResult.FAILED
        }
        ModuleLog.debug(TAG, "preview kind=${NativePresentationContract.kind(intent)} result=$result")
        if (result == NativePresentationResult.OWNERSHIP_UNAVAILABLE ||
            result == NativePresentationResult.HOST_UNAVAILABLE) reportHostAvailability("preview-$result")
        NativePresentationContract.previewId(intent)?.let { id ->
            BroadcastIdentity.send(context, NativePresentationContract.previewResult(id, result))
        }
    }

    private fun present(record: Record, mode: HeadsetConnectionPresentation, manual: Boolean = false): NativePresentationResult {
        return runCatching { presentResolved(record, mode, manual) }.getOrElse {
            ModuleLog.warn(TAG, "native presentation failed", it)
            NativePresentationResult.FAILED
        }
    }

    @SuppressLint("MissingPermission")
    private fun presentResolved(record: Record, mode: HeadsetConnectionPresentation, manual: Boolean): NativePresentationResult {
        if (settings.modulePaused || mode == HeadsetConnectionPresentation.OFF) return NativePresentationResult.PAUSED
        val p = NativeHeadsetProjection.from(record.state) ?: return NativePresentationResult.NO_DEVICE
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val user = context.getSystemService(UserManager::class.java)
        // fast_connect_show_dialog is also written by ignorePairDialog/pairing paths without a UI.
        // The native owner tag and per-device flow record represent presentation occupancy.
        val busy = !Settings.Global.getString(context.contentResolver, "miui_nearby_show_dialog").isNullOrEmpty()
        if (!user.isUserUnlocked || !user.isUserForeground || !power.isInteractive || keyguard.isKeyguardLocked) {
            ModuleLog.debug(TAG, "presentation skipped: unlocked=${user.isUserUnlocked} " +
                "foreground=${user.isUserForeground} interactive=${power.isInteractive} " +
                "locked=${keyguard.isKeyguardLocked} nativeWindow=$busy ownWindow=${activeWindow != null}")
            return NativePresentationResult.SCREEN_UNAVAILABLE
        }
        if (busy || activeWindow != null) return NativePresentationResult.BUSY
        if (Settings.Global.getInt(context.contentResolver, "miui_disable_fast_connect_bt_off", 1) != 0) {
            ModuleLog.debug(TAG, "presentation skipped: native FastConnect disabled")
            return NativePresentationResult.NATIVE_DISABLED
        }
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(p.address)
            ?: return NativePresentationResult.NO_DEVICE
        if (BluetoothDevice::class.java.getMethod("isConnected").invoke(device) != true) return NativePresentationResult.NO_DEVICE
        val circulation = flow?.read(device) ?: return NativePresentationResult.HOST_UNAVAILABLE
        if (circulation.blocks(manual)) {
            ModuleLog.debug(TAG, "presentation skipped: native flow=$circulation manual=$manual")
            return NativePresentationResult.BUSY
        }
        val onHomeOrCoverScreen = if (mode == HeadsetConnectionPresentation.SMART) {
            runCatching { scene?.isOnHomeOrCoverScreen(context) }
                .onFailure { ModuleLog.warn(TAG, "native scene query failed", it) }.getOrNull()
        } else null
        val target = resolveNativePresentationTarget(mode, onHomeOrCoverScreen) ?: run {
            ModuleLog.debug(TAG, "presentation skipped: native scene unavailable")
            return NativePresentationResult.HOST_UNAVAILABLE
        }
        ModuleLog.debug(TAG, "selected target=$target mode=$mode home=$onHomeOrCoverScreen")
        val selectedSetting = ModuleSettingsRuntime.current.headsetConnectionPresentation
        val registrations = ownershipStore
        return when (target) {
            NativePresentationTarget.ISLAND -> hint?.show(p, device) {
                val latest = records[p.address]
                if (ModuleSettingsRuntime.current.modulePaused ||
                    ModuleSettingsRuntime.current.headsetConnectionPresentation != selectedSetting ||
                    !record.producer.isBinderAlive || latest == null ||
                    latest.producer != record.producer || latest.session != record.session ||
                    latest.metadata.connection != record.metadata.connection ||
                    latest.metadata.admission != NativeAdmission.HYPEREARS || !latest.state.sessionActive ||
                    registrations?.read(p.address) != NativeAdmission.HYPEREARS
                ) null else NativeHeadsetProjection.from(latest.state)
            } ?: NativePresentationResult.HOST_UNAVAILABLE
            NativePresentationTarget.POPUP -> {
                if (!popupCompatible) {
                    ModuleLog.debug(TAG, "popup unavailable: host contract not ready")
                    return NativePresentationResult.HOST_UNAVAILABLE
                }
                if (windowKind == NativeWindowKind.LEGACY_XIAOMI && !legacyVerified) {
                    return NativePresentationResult.HOST_UNAVAILABLE
                }
                val id = UUID.randomUUID().toString()
                activeWindow = id
                windowDeadline = Runnable { if (activeWindow == id && windowLifetime == null) releaseWindow() }
                    .also { handler.postDelayed(it, 5_000) }
                runCatching { NativeWindowRequest.launch(context,
                    NativeWindowRequest(id, record.state, record.session, record.metadata, record.producer,
                        windowKind), device)
                }.getOrElse {
                    releaseWindow()
                    ModuleLog.warn(TAG, "popup launch failed", it)
                    return NativePresentationResult.FAILED
                }
                BroadcastIdentity.send(context,
                    ModuleContract.control(StandardControlRequest.Refresh, p.address, record.session))
                ModuleLog.debug(TAG, "popup submitted address=${maskBluetoothAddress(p.address)}")
                NativePresentationResult.SUBMITTED
            }
        }
    }

    private companion object { const val TAG = "NativePresentation" }
}
