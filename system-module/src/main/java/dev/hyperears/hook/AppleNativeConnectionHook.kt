package dev.hyperears.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.SystemClock
import dev.hyperears.bridge.AppleNativeAdmissionContract as Contract
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract
import java.lang.reflect.Method
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap
import dev.hyperears.hook.AppleNativeEpochLedger.Work
import dev.hyperears.hook.AppleNativeEpochLedger.Decision

/** Observes the real native decision; never invokes native connection code as a query. */
@SuppressLint("MissingPermission", "PrivateApi")
internal class AppleNativeConnectionHook(private val context: Context, private val outer: Method) : HookContext() {
    private val lock = Any()
    @Volatile private var active = false
    private val ledger = AppleNativeEpochLedger()
    private val requests = mutableMapOf<String, Contract.Request>()
    private val physicalHfpLinks = mutableSetOf<String>()
    private var earlyProbe: (Contract.Request) -> Unit = {}
    private val queued = WeakHashMap<Message, Work>()
    private var revision = 0L
    private fun nextRevision(): Long = maxOf(revision + 1, SystemClock.elapsedRealtimeNanos()).also { revision = it }
    private val dispatch = ThreadLocal<Work>()
    private val health = ThreadLocal<AppleNativeReadHealth>()
    private val probeModel = ThreadLocal<String?>()
    private val scope = AppleNativeDecisionScope()
    private val preferencesHooks = mutableSetOf<Method>()
    private var producer: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val retired = mutableSetOf<IBinder>()
    private val hfpField = outer.declaringClass.declaredFields.single {
        it.type == BluetoothHeadset::class.java
    }.apply { isAccessible = true }

    private fun connected(owner: Any, address: String): Boolean = runCatching {
        (hfpField.get(owner) as? BluetoothHeadset)?.connectedDevices?.any { it.address == address } == true
    }.getOrDefault(false)

    private val installation = HookInstallTransaction<XposedInterface.HookHandle> { it.unhook() }
    private fun observe(method: Method, block: (XposedInterface.Chain) -> Any?) {
        installation.record(module.hook(method).intercept { chain -> block(chain) })
    }

    override fun install() = installation.install(::installObservers)

    private fun installObservers() {
        val handlerClass = outer.declaringClass.declaredClasses.single {
            Handler::class.java.isAssignableFrom(it)
        }
        val ownerField = handlerClass.declaredFields.single { it.type == outer.declaringClass }
            .apply { isAccessible = true }
        val storage = findClass(AppleNativeConnectionResolver.STORAGE)
        val cloudState = storage.getDeclaredField("sCloudConfigState").apply { isAccessible = true }
        val cloudGate = storage.getDeclaredMethod("isFeatureCloudSupport", Context::class.java,
            String::class.java, String::class.java)
        val builtin = findClass("com.xiaomi.bluetooth.modules.airlibrary.utils.Config")
            .getDeclaredMethod("isSupportConnectL2cap", String::class.java)
        val gate = storage.getDeclaredMethod("isFeatureSupport", Context::class.java,
            BluetoothDevice::class.java, String::class.java)
        // Query only the original read-only feature gate. Do not call the native connection method.
        earlyProbe = { request ->
            if (request.address in physicalHfpLinks && requests[request.address] == request &&
                request.producer == producer && request.producer.isBinderAlive) {
                val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(request.address)
                // Prefer native identity. Otherwise use an authenticated previously verified model,
                // never the device's friendly name. Native ownership itself is queried anew.
                val model = context.getSharedPreferences("AirpodsModel", Context.MODE_MULTI_PROCESS)
                    .getString("${request.address}-type", "")?.takeIf { it.matches(Regex("[0-9a-fA-F]{4}")) }
                    ?: request.nativeModelType
                if (device?.bondState == BluetoothDevice.BOND_BONDED && model != null) {
                    probeModel.set(model)
                    try { scope.observe(request.address, { gate.invoke(null, context, device, "ConnectL2cap") }) { accepted ->
                        if (requests[request.address] == request && request.producer == producer && request.producer.isBinderAlive) {
                            BroadcastIdentity.send(context, Contract.decision(request, accepted, nextRevision()))
                            ModuleLog.debug(TAG, "early feature decision accepted=$accepted address=${maskBluetoothAddress(request.address)}")
                        }
                    } } finally { probeModel.remove() }
                }
            }
        }
        val enqueue = Handler::class.java.getDeclaredMethod("sendMessageAtTime", Message::class.java,
            Long::class.javaPrimitiveType)
        val handleMessage = handlerClass.getDeclaredMethod("handleMessage", Message::class.java)
        // Track the actual native queue's HFP epoch, including native service-rebind scheduling.
        observe(enqueue) { chain ->
            if (chain.thisObject?.javaClass == handlerClass) runCatching {
                val message = chain.args[0] as Message
                val device = message.obj as? BluetoothDevice
                val owner = ownerField.get(chain.thisObject) ?: return@runCatching
                if (device != null && connected(owner, device.address)) synchronized(lock) {
                    queued[message] = ledger.queued(device.address, owner, SystemClock.elapsedRealtime())
                }
            }
            chain.proceed()
        }
        observe(handleMessage) { chain ->
            val old = dispatch.get()
            val work = (chain.args[0] as? Message)?.let { synchronized(lock) { queued.remove(it) } }
            if (work == null) dispatch.remove() else dispatch.set(work)
            try { chain.proceed() } finally {
                if (old == null) dispatch.remove() else dispatch.set(old)
            }
        }
        observe(cloudGate) { chain ->
            val read = health.get()?.takeIf { chain.args[2] == "ConnectL2cap" }
            if (read == null) chain.proceed() else read.cloud { chain.proceed() }
        }
        observe(builtin) { chain ->
            val read = health.get()
            if (read == null) chain.proceed() else read.builtin { chain.proceed() }
        }
        observe(gate) { chain ->
            val device = chain.args[1] as? BluetoothDevice
            val callContext = chain.args[0] as? Context
            val exact = device != null && callContext != null && chain.args[2] == "ConnectL2cap" &&
                scope.current()?.address == device.address
            if (!exact) return@observe chain.proceed()
            val read = AppleNativeReadHealth("${device.address}-type")
            val old = health.get()
            health.set(read)
            runCatching {
                val prefs = callContext.getSharedPreferences("AirpodsModel", Context.MODE_MULTI_PROCESS)
                val method = prefs.javaClass.getMethod("getString", String::class.java, String::class.java)
                synchronized(preferencesHooks) {
                    if (method !in preferencesHooks) {
                        observe(method) { prefChain ->
                            val active = health.get()?.takeIf { it.key == prefChain.args[0] }
                            if (active == null) prefChain.proceed() else active.preference {
                                val original = prefChain.proceed()
                                if (original == null || original == "") probeModel.get() ?: original else original
                            }
                        }
                        preferencesHooks.add(method)
                    }
                }
            }.onFailure { read.failed() }
            try {
                val result = chain.proceed()
                val healthy = runCatching {
                    read.declineHealthy(cloudState.getInt(null)) { cloudFileAbsent(callContext) }
                }.getOrDefault(false)
                if (result is Boolean) scope.gate(device.address, result, healthy)
                result
            } finally {
                if (old == null) health.remove() else health.set(old)
            }
        }
        observe(outer) { chain ->
            // No dynamic preference hook may be added while installation can still roll back.
            if (!active) return@observe chain.proceed()
            val device = chain.args[0] as? BluetoothDevice ?: return@observe chain.proceed()
            val work = dispatch.get()?.takeIf {
                it.address == device.address && synchronized(lock) { ledger.current(it) } &&
                    connected(it.epoch.owner, it.address)
            }
            // Keep the actual native connection gate consistent with the early query when its
            // address-to-model preference is still empty. The verified identity is read-only here too.
            val previousModel = probeModel.get()
            val verifiedModel = work?.let { synchronized(lock) { requests[it.address]?.nativeModelType } }
            if (verifiedModel != null) probeModel.set(verifiedModel)
            try { scope.observe(device.address, original = { chain.proceed() }) { result ->
                if (work != null) synchronized(lock) {
                    if (connected(work.epoch.owner, work.address)) {
                        ledger.observe(work, result)?.let { decision ->
                            requests[work.address]?.let { deliver(it, decision) }
                        }
                    }
                }
            } } finally {
                if (previousModel == null) probeModel.remove() else probeModel.set(previousModel)
            }
        }

        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { synchronized(lock) {
                    when (intent.action) {
                        Contract.REQUEST, Contract.CANCEL -> {
                            if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid,
                                    ModuleContract.BLUETOOTH_PACKAGE)) return
                            val request = Contract.read(intent) ?: return
                            if (!adopt(request)) return
                            if (intent.action == Contract.CANCEL) {
                                if (requests[request.address] == request) requests.remove(request.address)
                            } else {
                                requests[request.address] = request
                                ledger.decision(request.address)?.let { deliver(request, it) } ?: earlyProbe(request)
                            }
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                            if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED &&
                                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) in
                                    setOf(BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED)) {
                                physicalHfpLinks.add(device.address)
                                requests[device.address]?.let(earlyProbe)
                                return
                            }
                            physicalHfpLinks.remove(device.address)
                            ledger.invalidate(device.address)
                            requests[device.address]?.let { BroadcastIdentity.send(context, Contract.decision(it, null, nextRevision())) }
                        }
                    }
                } }.onFailure { ModuleLog.warn(TAG, "native admission event failed", it) }
            }
        }, IntentFilter().apply {
            addAction(Contract.REQUEST); addAction(Contract.CANCEL)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        active = true
        runCatching { BroadcastIdentity.send(context, Intent(Contract.READY).setPackage(ModuleContract.BLUETOOTH_PACKAGE)) }
            .onFailure { ModuleLog.warn(TAG, "ready announcement failed", it) }
    }

    private fun adopt(request: Contract.Request): Boolean {
        val next = request.producer
        if (!next.isBinderAlive || next in retired) return false
        if (next == producer) return true
        val recipient = IBinder.DeathRecipient { synchronized(lock) {
            if (producer == next) { retired.add(next); clear(); producer = null }
        } }
        if (runCatching { next.linkToDeath(recipient, 0) }.isFailure) return false
        producer?.let { previous ->
            retired.add(previous)
            death?.let { runCatching { previous.unlinkToDeath(it, 0) } }
        }
        // AdapterService is created before profile work. Retain only native queue work
        // scheduled during this new Bluetooth producer, never a previous producer's decision.
        val fresh = queued.entries.filter { it.value.queuedAt >= request.producerStartedAt &&
            connected(it.value.epoch.owner, it.value.address) }
            .map { it.key to it.value }
        requests.clear()
        queued.clear()
        ledger.replaceProducer(request.producerStartedAt) { connected(it.epoch.owner, it.address) }
        fresh.forEach { (message, pending) ->
            queued[message] = ledger.queued(pending.address, pending.epoch.owner, pending.queuedAt)
        }
        producer = next
        death = recipient
        return true
    }
    private fun clear() { requests.clear(); ledger.clear(); queued.clear() }
    private fun deliver(request: Contract.Request, decision: Decision) {
        if (request.producer != producer || !request.producer.isBinderAlive ||
            !ledger.current(decision.work) ||
            !connected(decision.work.epoch.owner, request.address)) return
        BroadcastIdentity.send(context, Contract.decision(request, decision.accepted, nextRevision()))
    }
    private fun cloudFileAbsent(context: Context): Boolean = try {
        java.nio.file.Files.readAttributes(java.io.File(context.filesDir,
            "fc_resources/00000101/fc_support_airpods_additional_2_extended.json").toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java)
        false
    } catch (_: java.nio.file.NoSuchFileException) { true }
    private companion object { const val TAG = "AppleNativeAdmission" }
}
