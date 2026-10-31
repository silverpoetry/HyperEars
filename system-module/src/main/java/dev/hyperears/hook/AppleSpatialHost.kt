package dev.hyperears.hook

import android.content.Context
import android.annotation.SuppressLint
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method

/** In-process port. The owner must supply an active-route AND call/ownership predicate. */
internal interface AppleSpatialHost : AutoCloseable {
    val rendererAvailable: Boolean
    val trackerAvailable: Boolean
    val trackingActive: Boolean
    fun dispose() = close()
    fun fixed(): Boolean
    fun start(): Boolean
    fun pose(vector: List<Double>): Boolean
}

/** Exact semantic signatures, transactionally resolved before exposing any native capability. */
@SuppressLint("PrivateApi") // Explicitly scoped native host; unresolved symbols fail closed in create().
internal class NativeAppleSpatialHost private constructor(
    private val context: Context,
    private val address: String,
    private val eligible: () -> Boolean,
    private val spatial: Any,
    private val attributes: Any,
    private val methods: Map<String, Method>,
    private val controllerClass: Class<*>?,
) : AppleSpatialHost {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var controller: Any? = null
    private var sensorCreated = false
    private var previousCompatible: Boolean? = null
    private var previousMode: Int? = null
    private var previousTracker: Boolean? = null
    private var ownedMode: Int? = null
    @Volatile private var lastPose = 0L
    private val hidAddress = "0x" + address.replace(":", "")
    private fun call(name: String, vararg args: Any): Any? = methods.getValue(name).invoke(spatial, *args)
    override val rendererAvailable: Boolean get() = runCatching {
        eligible() && call("getImmersiveAudioLevel") as Int > 0 && call("isEnabled") == true
    }.getOrDefault(false)
    override val trackerAvailable: Boolean get() = rendererAvailable && controllerClass != null
    override val trackingActive: Boolean get() = runCatching {
        controller != null && eligible() && call("isHeadTrackerAvailable") == true &&
            call("hasHeadTracker", attributes) == true && call("getHeadTrackingMode") == 1
    }.getOrDefault(false)
    private fun compatible() = (call("getCompatibleAudioDevices") as List<*>).contains(attributes)
    @Synchronized override fun fixed(): Boolean = runCatching {
        check(rendererAvailable)
        if (previousCompatible == null) {
            previousCompatible = compatible()
            previousMode = call("getDesiredHeadTrackingMode") as Int
        }
        if (!compatible()) call("addCompatibleAudioDevice", attributes)
        call("setDesiredHeadTrackingMode", -1); ownedMode = -1
        check(rendererAvailable && compatible() && call("isAvailable") == true && call("getDesiredHeadTrackingMode") == -1)
        true
    }.getOrElse { close(); false }
    @Synchronized override fun start(): Boolean = runCatching {
        check(trackerAvailable && controller == null && fixed())
        check(context.getSystemService(SensorManager::class.java).getDynamicSensorList(37).isEmpty())
        previousTracker = call("isHeadTrackerEnabled", attributes) as Boolean
        val type = checkNotNull(controllerClass)
        controller = type.getConstructor().newInstance()
        // Audited Spatialcontroller JNI returns false on successful UHID creation.
        check(type.getMethod("createspatialhid", String::class.java).invoke(controller, hidAddress) == false)
        sensorCreated = true
        call("setHeadTrackerEnabled", true, attributes)
        call("setDesiredHeadTrackingMode", 1); ownedMode = 1
        lastPose = SystemClock.elapsedRealtime()
        handler.postDelayed(watchdog, 800)
        true
    }.getOrElse { close(); false }
    @Synchronized override fun pose(vector: List<Double>): Boolean = runCatching {
        check(eligible() && controller != null && vector.size == 3 && vector.all { it.isFinite() })
        check(vector.sumOf { it * it } <= 10.0)
        val degrees = 180.0 / Math.PI
        val native = floatArrayOf((vector[2] * degrees).toFloat(), (vector[0] * degrees).toFloat(), (vector[1] * degrees).toFloat())
        controllerClass!!.getMethod("sendspatialdata", String::class.java, FloatArray::class.java)
            .invoke(controller, hidAddress, native)
        lastPose = SystemClock.elapsedRealtime()
        true
    }.getOrElse { close(); false }
    private val watchdog = object : Runnable {
        override fun run() {
            if (controller == null) return
            val remaining = 800 - (SystemClock.elapsedRealtime() - lastPose)
            if (remaining <= 0 || !eligible()) close() else handler.postDelayed(this, remaining)
        }
    }
    @Synchronized override fun close() {
        handler.removeCallbacks(watchdog)
        val owned = controller; controller = null
        if (owned != null && sensorCreated) runCatching {
            controllerClass!!.getMethod("destroyspatialhid", String::class.java).invoke(owned, hidAddress)
        }
        sensorCreated = false
        // Restore only values still equal to this lease's write; preserve intervening user changes.
        previousTracker?.let { before -> runCatching {
            if (call("isHeadTrackerEnabled", attributes) == true) call("setHeadTrackerEnabled", before, attributes)
        } }
        previousMode?.let { before -> runCatching {
            if (call("getDesiredHeadTrackingMode") == ownedMode) call("setDesiredHeadTrackingMode", before)
        } }
        if (previousCompatible == false) runCatching {
            if (compatible()) call("removeCompatibleAudioDevice", attributes)
        }
        previousTracker = null; previousMode = null; previousCompatible = null; ownedMode = null
    }
    companion object {
        fun create(context: Context, address: String, eligible: () -> Boolean, loader: ClassLoader = context.classLoader): NativeAppleSpatialHost? = runCatching {
            val spatial = context.getSystemService(AudioManager::class.java).spatializer
            val attr = Class.forName("android.media.AudioDeviceAttributes", false, context.classLoader)
            val attributes = attr.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .newInstance(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, address)
            val signatures = mapOf(
                "getImmersiveAudioLevel" to emptyArray(), "isEnabled" to emptyArray(), "isAvailable" to emptyArray(),
                "isHeadTrackerAvailable" to emptyArray(), "getHeadTrackingMode" to emptyArray(),
                "hasHeadTracker" to arrayOf(attr),
                "getCompatibleAudioDevices" to emptyArray(), "getDesiredHeadTrackingMode" to emptyArray(),
                "addCompatibleAudioDevice" to arrayOf(attr), "removeCompatibleAudioDevice" to arrayOf(attr),
                "isHeadTrackerEnabled" to arrayOf(attr),
                "setHeadTrackerEnabled" to arrayOf(Boolean::class.javaPrimitiveType!!, attr),
                "setDesiredHeadTrackingMode" to arrayOf(Int::class.javaPrimitiveType!!),
            )
            val methods = signatures.mapValues { (name, args) -> spatial.javaClass.getMethod(name, *args) }
            val native = runCatching {
                Class.forName("com.android.bluetooth.ble.app.headset.Spatialcontroller", false, loader).also {
                    it.getConstructor()
                    check(it.getMethod("createspatialhid", String::class.java).returnType == Boolean::class.javaPrimitiveType)
                    it.getMethod("sendspatialdata", String::class.java, FloatArray::class.java)
                    it.getMethod("destroyspatialhid", String::class.java)
                }
            }.getOrNull()
            NativeAppleSpatialHost(context, address, eligible, spatial, attributes, methods, native)
        }.getOrNull()
    }
}
