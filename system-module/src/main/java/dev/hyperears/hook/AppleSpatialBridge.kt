package dev.hyperears.hook

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.*
import java.util.concurrent.ConcurrentHashMap

/** Typed, private process boundary: no Bluetooth packets, keys, JSON or independent service. */
private object SpatialBridgeWire {
    const val ACTION = "dev.hyperears.internal.SPATIAL_HOST"
    const val DESCRIPTOR = "dev.hyperears.SpatialHost.v1"
    const val READY = IBinder.FIRST_CALL_TRANSACTION
    const val QUERY = IBinder.FIRST_CALL_TRANSACTION
    const val FIXED = QUERY + 1
    const val START = QUERY + 2
    const val POSE = QUERY + 3
    const val STOP = QUERY + 4
    const val RELEASE = QUERY + 5
    fun uid(context: Context, uid: Int, expected: String) =
        expected in context.packageManager.getPackagesForUid(uid).orEmpty()
}

/** Installed only in com.xiaomi.bluetooth alongside authenticated native presentation. */
internal class AppleSpatialBridgeServer(private val context: Context, private val handler: Handler) {
    private data class Admission(val session: String, val producer: IBinder)
    private val admitted = ConcurrentHashMap<String, Admission>()
    private val wearing = ConcurrentHashMap<String, Boolean>()
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var loader = context.classLoader
    private val routeMethod = runCatching {
        AudioManager::class.java.getMethod("getAudioDevicesForAttributes", AudioAttributes::class.java)
            .also { check(it.returnType == List::class.java) }
    }.getOrNull()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private fun route(address: String): Boolean = runCatching {
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        val outputs = routeMethod?.invoke(audio, attributes) as? List<*> ?: return false
        outputs.isNotEmpty() && outputs.all {
            it is AudioDeviceInfo && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(address, true)
        }
    }.getOrDefault(false)
    private fun invalidateRoutes() { endpoints.values.toList().forEach { it.refreshAvailability() } }
    init {
        check(context.packageName == ModuleContract.NATIVE_HEADSET_PACKAGE)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED") { invalidateRoutes();return }
                if (!BroadcastIdentity.accepts(context, sentFromPackage, sentFromUid, ModuleContract.BLUETOOTH_PACKAGE)) return
                val address = intent.getStringExtra("address") ?: return
                val session = intent.getStringExtra("session") ?: return
                val producer = intent.extras?.getBinder("producer") ?: return
                val callback = intent.extras?.getBinder("callback") ?: return
                val age = SystemClock.elapsedRealtime() - intent.getLongExtra("created", -1)
                if (age !in 0..2500 || !BluetoothAdapter.checkBluetoothAddress(address) || session.length !in 1..128) return
                val admission = admitted[address] ?: return
                if (admission.session != session || admission.producer != producer || !producer.isBinderAlive) return
                endpoints.remove(address)?.close()
                val endpoint = Endpoint(address, admission, sentFromUid, callback)
                if (!endpoint.initialize()) return
                endpoints[address] = endpoint
                endpoint.announce()
            }
        }, IntentFilter(SpatialBridgeWire.ACTION).apply { addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED") }, Context.RECEIVER_EXPORTED)
        audio.spatializer.addOnSpatializerStateChangedListener({ handler.post(it) }, object : android.media.Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: android.media.Spatializer, enabled: Boolean) = invalidateRoutes()
            override fun onSpatializerAvailableChanged(spatializer: android.media.Spatializer, available: Boolean) = invalidateRoutes()
        })
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = invalidateRoutes()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = invalidateRoutes()
        }, handler)
        audio.addOnModeChangedListener({ handler.post(it) }, { invalidateRoutes() })
        audio.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) = invalidateRoutes()
        }, handler)
    }
    fun pluginReady(loader: ClassLoader) {
        this.loader = loader
        endpoints.values.toList().forEach { it.refreshCapability() }
    }
    fun update(state: EarbudState, session: String, producer: IBinder, permitted: Boolean) {
        val address = state.address ?: return
        val wear = state.features.get<AppleWearFeatureState>()
        if (!permitted || !producer.isBinderAlive || !state.sessionActive || !state.privateChannelConnected ||
            !state.handshakeAccepted || state.lifecycle.controlOwnership != ControlOwnership.MODULE) {
            wearing.remove(address);admitted.remove(address);endpoints.remove(address)?.close();return
        }
        wearing[address] = wear?.inEarCount != 0
        val next=Admission(session,producer)
        if (admitted.put(address,next) != next) endpoints.remove(address)?.close()
        invalidateRoutes()
    }
    fun clear() { wearing.clear();admitted.clear(); endpoints.values.toList().forEach { it.close() };endpoints.clear() }
    private inner class Endpoint(val address: String, val admission: Admission, val uid: Int, val callback: IBinder) : Binder(), AutoCloseable {
        private var host: NativeAppleSpatialHost? = null
        @Volatile private var closed = false
        private var sequence = -1L
        private val death = IBinder.DeathRecipient { close() }
        private fun producerAdmitted() = !closed && admitted[address] == admission && admission.producer.isBinderAlive
        fun eligible() = producerAdmitted() && wearing[address] != false && route(address)
        private val availability = AppleSpatialAvailabilityLease(
            releaseResources = { host?.close() },
            announce = { handler.post { if (!closed) announce() } },
            dispose = { close() },
        )
        @Synchronized fun refreshAvailability() {
            availability.update(producerAdmitted(), eligible() && host?.rendererAvailable == true)
        }
        fun initialize(): Boolean = runCatching {
            admission.producer.linkToDeath(death,0)
            host=NativeAppleSpatialHost.create(context,address,::eligible,loader)
            check(host != null)
            true
        }.getOrElse { close();false }
        fun announce() {
            val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(SpatialBridgeWire.DESCRIPTOR);data.writeStrongBinder(this)
                if(!callback.transact(SpatialBridgeWire.READY,data,reply,0)) close() else reply.readException()
            } catch(_:Exception) { close() } finally { data.recycle();reply.recycle() }
        }
        fun refreshCapability() {
            val changed = synchronized(this) {
                if(closed || host?.trackerAvailable == true) false
                else {
                    val next=NativeAppleSpatialHost.create(context,address,::eligible,loader)
                    if(next?.trackerAvailable == true) { host?.close();host=next;true } else false
                }
            }
            if(changed) announce()
        }
        @Synchronized override fun close() {
            if (closed) return
            closed=true;host?.close();host=null
            runCatching { admission.producer.unlinkToDeath(death,0) }
            endpoints.remove(address,this)
        }
        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(SpatialBridgeWire.DESCRIPTOR);return true }
            if (Binder.getCallingUid() != uid || !SpatialBridgeWire.uid(context,uid,ModuleContract.BLUETOOTH_PACKAGE)) throw SecurityException("Spatial producer UID")
            data.enforceInterface(SpatialBridgeWire.DESCRIPTOR)
            if (data.readString() != address || data.readString() != admission.session || data.readStrongBinder() != admission.producer)
                throw SecurityException("Spatial producer session")
            val captured: Long;val next: Long;val vector: List<Double>?
            if (code == SpatialBridgeWire.POSE) {
                captured=data.readLong();next=data.readLong()
                vector=listOf(data.readDouble(),data.readDouble(),data.readDouble())
            } else { captured=0;next=0;vector=null }
            data.enforceNoDataAvail()
            val identity=Binder.clearCallingIdentity()
            try {
                val target=host
                val ok = if (code == SpatialBridgeWire.RELEASE) { close();true }
                else if (code == SpatialBridgeWire.STOP) { target?.close();true }
                else if (!producerAdmitted() || target == null) { close();false }
                else if (!eligible() || !target.rendererAvailable) { refreshAvailability();target.close();false }
                else when(code) {
                    SpatialBridgeWire.QUERY -> true
                    SpatialBridgeWire.FIXED -> target.fixed()
                    SpatialBridgeWire.START -> target.start()
                    SpatialBridgeWire.POSE -> {
                        if(!AppleSpatialPoseGuard.accepts(SystemClock.elapsedRealtime(),captured,next,sequence,vector)) false
                        else target.pose(checkNotNull(vector)).also { if(it) sequence=next }
                    }
                    else -> false
                }
                reply?.writeNoException();reply?.writeBoolean(ok)
                reply?.writeBoolean(ok && host?.rendererAvailable == true)
                reply?.writeBoolean(ok && host?.trackerAvailable == true)
                reply?.writeBoolean(ok && host?.trackingActive == true)
            } finally { Binder.restoreCallingIdentity(identity) }
            return true
        }
    }
}

/** A session-owned proxy. Endpoint availability is asynchronously announced, never assumed. */
internal class RemoteAppleSpatialHost(
    private val context: Context,
    private val address: String,
    private val session: String,
    private val producer: IBinder,
    private val ready: () -> Unit,
) : AppleSpatialHost {
    @Volatile private var remote: IBinder? = null
    @Volatile private var renderer=false
    @Volatile private var tracker=false
    @Volatile private var active=false
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var connectGeneration=0L
    @Volatile private var disposed=false
    private var sequence=0L
    private val death=IBinder.DeathRecipient { remote=null;renderer=false;tracker=false;active=false;ready() }
    private val callback=object : Binder() {
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code!=SpatialBridgeWire.READY)return false
            if(!SpatialBridgeWire.uid(context,Binder.getCallingUid(),ModuleContract.NATIVE_HEADSET_PACKAGE))throw SecurityException("Spatial host UID")
            data.enforceInterface(SpatialBridgeWire.DESCRIPTOR)
            val endpoint=data.readStrongBinder();data.enforceNoDataAvail()
            if(endpoint!=null && producer.isBinderAlive && !disposed) {
                if (remote != endpoint) {
                    remote?.let { runCatching { it.unlinkToDeath(death,0) } }
                    remote=endpoint
                    runCatching { endpoint.linkToDeath(death,0) }.onFailure { remote=null }
                }
                // Callback only schedules owner work; do not synchronously reenter the server binder.
                ready()
            }
            reply?.writeNoException();return true
        }
    }
    fun connect() {
        if (disposed || remote != null && invoke(SpatialBridgeWire.QUERY)) return
        remote?.let { runCatching { it.unlinkToDeath(death,0) } };remote=null
        val generation=++connectGeneration
        // Bounded rendezvous retries accommodate asynchronous producer-state admission.
        for(delay in listOf(0L,250L,1000L)) handler.postDelayed({
            if(!disposed && remote==null && connectGeneration==generation) {
                BroadcastIdentity.send(context,Intent(SpatialBridgeWire.ACTION).setPackage(ModuleContract.NATIVE_HEADSET_PACKAGE)
                    .putExtra("address",address).putExtra("session",session).putExtra("created",SystemClock.elapsedRealtime())
                    .putExtras(Bundle().apply { putBinder("producer",producer);putBinder("callback",callback) }))
            }
        },delay)
    }
    private fun invoke(code:Int,vector:List<Double>?=null):Boolean {
        val endpoint=remote ?: return false
        val data=Parcel.obtain();val reply=Parcel.obtain()
        return try {
            data.writeInterfaceToken(SpatialBridgeWire.DESCRIPTOR)
            data.writeString(address);data.writeString(session);data.writeStrongBinder(producer)
            if(vector!=null) {
                require(vector.size==3 && vector.all { it.isFinite() })
                data.writeLong(SystemClock.elapsedRealtime());data.writeLong(++sequence);vector.forEach(data::writeDouble)
            }
            if(!endpoint.transact(code,data,reply,0)) return false
            reply.readException()
            val ok=reply.readBoolean();renderer=reply.readBoolean();tracker=reply.readBoolean();active=reply.readBoolean();ok
        } catch (_: Exception) { renderer=false;tracker=false;active=false;false }
        finally { data.recycle();reply.recycle() }
    }
    override val rendererAvailable get() = invoke(SpatialBridgeWire.QUERY) && renderer
    override val trackerAvailable get() = invoke(SpatialBridgeWire.QUERY) && tracker
    override val trackingActive get() = invoke(SpatialBridgeWire.QUERY) && active
    override fun fixed() = invoke(SpatialBridgeWire.FIXED)
    override fun start() = invoke(SpatialBridgeWire.START)
    override fun pose(vector:List<Double>) = invoke(SpatialBridgeWire.POSE,vector)
    override fun close() { invoke(SpatialBridgeWire.STOP);active=false }
    override fun dispose() {
        disposed=true;connectGeneration++;invoke(SpatialBridgeWire.RELEASE)
        remote?.let { runCatching { it.unlinkToDeath(death,0) } }
        remote=null;renderer=false;tracker=false;active=false
    }
}

/** Validates the numerical IPC boundary independently of native sensor availability. */
internal object AppleSpatialPoseGuard {
    fun accepts(now:Long,captured:Long,sequence:Long,previous:Long,vector:List<Double>?):Boolean =
        captured >= 0 && now >= captured && now-captured <= 250 && sequence > previous &&
            vector != null && vector.size == 3 && vector.all { it.isFinite() } && vector.sumOf { it*it } <= 10.0
}

/** Temporary route/call loss releases resources while preserving the authenticated endpoint. */
internal class AppleSpatialAvailabilityLease(
    private val releaseResources: () -> Unit,
    private val announce: () -> Unit,
    private val dispose: () -> Unit,
) {
    private var available: Boolean? = null
    private var retired = false
    fun update(admitted: Boolean, available: Boolean) {
        if (retired) return
        if (!admitted) { retired = true;dispose();return }
        if (this.available == available) return
        this.available = available
        if (!available) releaseResources()
        announce()
    }
}
